package com.jingcaicompass.backtest.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.backtest.BacktestProperties;
import com.jingcaicompass.backtest.dto.BacktestCandidateDto;
import com.jingcaicompass.backtest.entity.BacktestCaseEntity;
import com.jingcaicompass.backtest.entity.OddsHistoryArchiveEntity;
import com.jingcaicompass.backtest.enums.BacktestArchiveStatusEnum;
import com.jingcaicompass.backtest.mapper.OddsHistoryArchiveMapper;
import com.jingcaicompass.backtest.service.BacktestArchiveService.ArchiveOutcome;
import com.jingcaicompass.backtest.service.BacktestArchiveService.ArchiveRequest;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import com.jingcaicompass.odds.entity.AsianOddsSnapshot;
import com.jingcaicompass.odds.service.OddsHistorySnapshotBuilder;
import com.jingcaicompass.prediction.service.AsianDecision;
import com.jingcaicompass.prediction.service.AsianDecisionEngine;
import com.jingcaicompass.prediction.service.AsianDecisionInput;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

/**
 * 一场比赛的完整回测判定：归档 → 切片 → 每个参数组各判一次。
 *
 * <p>三个阶段的顺序不能换、也不能合并：</p>
 * <ol>
 *   <li><b>归档</b>与参数组无关，跑一次；</li>
 *   <li><b>切片</b>与参数组无关（它只依赖决策时刻），跑一次——把它放进参数组循环里会让
 *       一个 4 组的 run 对同一份载荷解析 4 遍；</li>
 *   <li><b>判定</b>每个参数组一次，因为阈值不同。</li>
 * </ol>
 *
 * <p><b>状态必须传 {@code SCHEDULED}</b>：{@code matches.match_status} 在库里早已是
 * {@code FINISHED}，直接传会命中引擎的第一个出口 {@code NOT_SCHEDULED}，于是每一场都被
 * 判定为「非可生成状态」——回测跑完、没有任何报错、样本全空。我们模拟的是赛前那一刻，
 * 那一刻的语义状态就是 {@code SCHEDULED}。</p>
 *
 * <p>同样地 {@code decisionAt = kickoffTime − leadMinutes}，且 {@code leadMinutes >= 1}
 * 由 {@code BacktestRunService} 保证，因此 {@code decision_at < kickoff_time} 这条
 * DB 级无泄漏约束天然成立。</p>
 */
@Service
@ConditionalOnBean(DataSource.class)
public class BacktestEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(BacktestEvaluationService.class);

    private final BacktestArchiveService archiveService;
    private final OddsHistoryArchiveMapper archiveMapper;
    private final ObjectMapper objectMapper;
    private final BacktestProperties properties;

    /**
     * 切片器无状态、无配置依赖，直接持有实例。
     *
     * <p>它刻意不是 Spring bean：切片是纯函数，一旦变成 bean，任何想「换一份实现」的改动
     * 都会通过注入点悄悄生效，而回测最怕的正是「同一份输入在不同路径下切出不同截面」。</p>
     */
    private final OddsHistorySnapshotBuilder snapshotBuilder = new OddsHistorySnapshotBuilder();

    public BacktestEvaluationService(
            BacktestArchiveService archiveService,
            OddsHistoryArchiveMapper archiveMapper,
            ObjectMapper objectMapper,
            BacktestProperties properties
    ) {
        this.archiveService = archiveService;
        this.archiveMapper = archiveMapper;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * 阶段一：确保这一场有可用的历史序列归档。
     *
     * <p>与判定分成两个方法，是为了让「补采」与「判定」各自对应 run 状态机的一个阶段。
     * 合成一个循环的做法会让 {@code EVALUATING} 这个状态永远只闪一下，
     * 而进度页无法区分「还在等 API」与「正在算」——两者的等待时长差一个数量级。</p>
     */
    public ArchiveOutcome archive(BacktestCandidateDto candidate) {
        return archiveService.ensureArchived(new ArchiveRequest(
                properties.providerCode(),
                candidate.externalMatchId(),
                candidate.matchId(),
                candidate.lotteryDate(),
                candidate.leagueName(),
                candidate.homeTeamName(),
                candidate.awayTeamName(),
                candidate.kickoffTime()
        ));
    }

    /**
     * 阶段一之后重新取一次归档行。
     *
     * <p>续跑时这里命中率接近 100%（阶段一刚刚 ensure 过），所以不是 N+1 浪费：
     * 阶段一记的是统计量，阶段二需要的是<b>载荷本身</b>，而载荷体积大到不适合在内存里留 2000 份。</p>
     */
    public OddsHistoryArchiveEntity loadArchive(BacktestCandidateDto candidate) {
        return archiveMapper.selectFinalByExternalMatchId(
                properties.providerCode(), candidate.externalMatchId());
    }

    /**
     * 阶段二：切片并逐参数组判定。
     *
     * @param archive 必须是通过 {@link #loadArchive} 取到的 SUCCESS 行
     * @return 每组一条样本
     */
    public List<BacktestCaseEntity> decide(
            Long runId,
            BacktestCandidateDto candidate,
            OddsHistoryArchiveEntity archive,
            List<GroupEngine> groups
    ) {
        Instant decisionAt = candidate.kickoffTime()
                .minus(properties.decisionLeadMinutes(), ChronoUnit.MINUTES);
        JsonNode payload = payloadOf(archive);
        if (payload == null) {
            // 归档行存在但载荷读不出：当作「没有可用报价」处理，不让一场脏数据把整个 run 打断。
            return emptyCases(runId, candidate, decisionAt, groups, archive);
        }
        OddsHistorySnapshotBuilder.HistorySnapshot snapshot = snapshotBuilder.build(
                payload,
                decisionAt,
                candidate.matchId(),
                properties.providerCode(),
                archive.getPayloadHash()
        );
        if (snapshot.isEmpty()) {
            // 该时点一行报价都没有（决策时刻早于首个变快点，或整场停盘）。
            // 仍然逐组产出样本：这是「这段时间确实没开价」的证据，跳过记录会让数据可用性分页看不出边界。
            return emptyCases(runId, candidate, decisionAt, groups, archive);
        }

        List<AsianOddsSnapshot> handicapRows = distinctHandicapRows(snapshot.rows());
        List<BacktestCaseEntity> cases = new ArrayList<>(groups.size());
        for (GroupEngine group : groups) {
            AsianDecision decision = group.engine().decide(new AsianDecisionInput(
                    candidate.matchId(),
                    // 赛前时点的语义状态；见类注释——传库里的当前状态会让所有场次被 NOT_SCHEDULED 吃掉。
                    MatchStatusEnum.SCHEDULED,
                    candidate.kickoffTime(),
                    decisionAt,
                    handicapRows,
                    snapshot.totalsQuotes(),
                    snapshot.rows()
            ));
            cases.add(BacktestCaseBuilder.build(
                    runId, group.groupKey(), candidate, decisionAt, archive.getId(), decision));
        }
        return List.copyOf(cases);
    }

    /**
     * 取载荷原文并解析。
     *
     * <p>绕开实体的 {@code payload} 字段是必须的，不是洁癖：{@code @TableField(typeHandler)}
     * 只在 MyBatis-Plus 自己拼 SQL 时生效，{@code @Select} 注解查询会忽略它，{@code Map} 字段
     * 落到默认 handler 上读回空 Map。那时切片恒为 0 行、判定恒为 {@code MISSING_ROWS}，
     * 而归档状态与点数全部正常——最像「这段时间上游没开价」，实际是自己没读到。</p>
     *
     * @return 解析失败的载荷返回 {@code null}（由调用方按「无可用报价」处理）
     */
    JsonNode payloadOf(OddsHistoryArchiveEntity archive) {
        String text = archiveMapper.selectPayloadTextById(archive.getId());
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(text);
        } catch (JsonProcessingException exception) {
            log.warn("event=backtest_archive_payload_unreadable archiveId={} matchId={}",
                    archive.getId(), archive.getExternalMatchId(), exception);
            return null;
        }
    }

    private List<BacktestCaseEntity> emptyCases(
            Long runId,
            BacktestCandidateDto candidate,
            Instant decisionAt,
            List<GroupEngine> groups,
            OddsHistoryArchiveEntity archive
    ) {
        List<BacktestCaseEntity> cases = new ArrayList<>(groups.size());
        for (GroupEngine group : groups) {
            cases.add(BacktestCaseBuilder.build(
                    runId, group.groupKey(), candidate, decisionAt, archive.getId(),
                    group.engine().decide(emptyInput(candidate, decisionAt))
            ));
        }
        return List.copyOf(cases);
    }

    private static AsianDecisionInput emptyInput(BacktestCandidateDto candidate, Instant decisionAt) {
        return new AsianDecisionInput(
                candidate.matchId(),
                MatchStatusEnum.SCHEDULED,
                candidate.kickoffTime(),
                decisionAt,
                List.of(),
                List.of(),
                List.of()
        );
    }

    /**
     * 让球行去重到「每家每档让球线一行」。
     *
     * <p>切片产物是<b>交叉积</b>：同一家庄的同一条让球线会按保留的大小球档数重复出现
     * （实测最多 4 份）。共识聚合按「每条线取各家去水概率的中位」计算，
     * 重复行会让同一家庄在样本里出现 4 次——中位数没变，但 σ 与家数门槛都被扭曲，
     * 而两者都会改变判定结果。</p>
     *
     * <p>取第一行而不是取「大小球线最平衡的那行」：让球共识只用让球列，
     * 行与行之间让球列完全相同，选哪行不影响结果。</p>
     */
    static List<AsianOddsSnapshot> distinctHandicapRows(List<AsianOddsSnapshot> rows) {
        Map<String, AsianOddsSnapshot> unique = new LinkedHashMap<>();
        for (AsianOddsSnapshot row : rows) {
            if (row.getHandicapLine() == null || row.getHomeOdds() == null || row.getAwayOdds() == null) {
                continue;
            }
            String key = row.getBookmakerCode() + "|" + row.getHandicapLine().stripTrailingZeros().toPlainString();
            unique.putIfAbsent(key, row);
        }
        return List.copyOf(unique.values());
    }

    /** 一组参数及其引擎。 */
    public record GroupEngine(String groupKey, AsianDecisionEngine engine) {
    }

}
