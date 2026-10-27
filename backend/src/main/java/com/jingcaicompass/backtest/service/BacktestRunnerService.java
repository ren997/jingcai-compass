package com.jingcaicompass.backtest.service;

import com.jingcaicompass.backtest.BacktestProperties;
import com.jingcaicompass.backtest.dto.BacktestCandidateDto;
import com.jingcaicompass.backtest.entity.BacktestCaseEntity;
import com.jingcaicompass.backtest.entity.BacktestRunEntity;
import com.jingcaicompass.backtest.entity.OddsHistoryArchiveEntity;
import com.jingcaicompass.backtest.enums.BacktestArchiveStatusEnum;
import com.jingcaicompass.backtest.enums.BacktestRunStatusEnum;
import com.jingcaicompass.backtest.enums.BacktestStageEnum;
import com.jingcaicompass.backtest.mapper.BacktestCandidateMapper;
import com.jingcaicompass.backtest.mapper.BacktestCaseMapper;
import com.jingcaicompass.backtest.mapper.BacktestRunMapper;
import com.jingcaicompass.backtest.service.BacktestArchiveService.ArchiveOutcome;
import com.jingcaicompass.backtest.service.BacktestEvaluationService.GroupEngine;
import com.jingcaicompass.backtest.service.BacktestParameterSets.ParamGroup;
import com.jingcaicompass.backtest.service.BacktestSettlementService.Settlement;
import com.jingcaicompass.odds.client.AsianModelProperties;
import com.jingcaicompass.odds.client.AsianOddsAggregationProperties;
import com.jingcaicompass.prediction.service.AsianDecisionEngine;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 回测 worker：抢一个 {@code QUEUED} 的 run，按状态机把它推到终态。
 *
 * <h2>为什么是「抢单 + 四段推进」</h2>
 *
 * <p><b>抢单</b>用 {@code FOR UPDATE SKIP LOCKED} 在一个短事务里完成，且状态推进与加锁同事务提交。
 * 长事务持锁跑 40 分钟会让第二个实例永远抢不到（业务上无害）却也让
 * 「这个 run 是否有人在跑」无法靠锁判断；短事务把「谁在跑」固化成 {@code run_status} 这个可读状态。</p>
 *
 * <p><b>四段</b>对应 {@code ARCHIVING → EVALUATING → SETTLING → AGGREGATING} 四个状态。
 * 每段的输入都能从库里重建（候选来自 {@code matches}、样本来自 {@code backtest_cases}），
 * 所以进程被杀之后可以续跑：已归档的复用、已写入的样本被唯一键跳过、已结算的不再结算。</p>
 *
 * <p><b>心跳</b>每 {@code batchSize} 场写一次，而不是每场：逐场写会把 2000 场变成 2000 次往返，
 * 而心跳的用途只是让启动自愈能区分「正在跑」与「进程已死」——这个判断的精度不需要到秒。</p>
 *
 * <p><b>本类绝不写</b> {@code settlements} / {@code predictions}：回测结论只能留在
 * {@code backtest_*} 三表里，否则线上统计会被历史实验污染，而且这种污染事后无法区分。</p>
 */
@Service
@ConditionalOnBean(DataSource.class)
public class BacktestRunnerService {

    private static final Logger log = LoggerFactory.getLogger(BacktestRunnerService.class);

    private final BacktestRunMapper runMapper;
    private final BacktestCaseMapper caseMapper;
    private final BacktestCandidateMapper candidateMapper;
    private final BacktestEvaluationService evaluationService;
    private final BacktestSettlementService settlementService;
    private final BacktestMetricsService metricsService;
    private final BacktestProperties properties;
    private final AsianOddsAggregationProperties aggregationProperties;
    private final AsianModelProperties modelProperties;
    private final TransactionTemplate transactionTemplate;

    public BacktestRunnerService(
            BacktestRunMapper runMapper,
            BacktestCaseMapper caseMapper,
            BacktestCandidateMapper candidateMapper,
            BacktestEvaluationService evaluationService,
            BacktestSettlementService settlementService,
            BacktestMetricsService metricsService,
            BacktestProperties properties,
            AsianOddsAggregationProperties aggregationProperties,
            AsianModelProperties modelProperties,
            PlatformTransactionManager transactionManager
    ) {
        this.runMapper = runMapper;
        this.caseMapper = caseMapper;
        this.candidateMapper = candidateMapper;
        this.evaluationService = evaluationService;
        this.settlementService = settlementService;
        this.metricsService = metricsService;
        this.properties = properties;
        this.aggregationProperties = aggregationProperties;
        this.modelProperties = modelProperties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 取一个待跑的 run 并执行完。
     *
     * @return true 表示本次确实跑了一个 run；false 表示队列为空
     */
    public boolean pollOnce() {
        Long claimed = claimNextRun();
        if (claimed == null) {
            return false;
        }
        execute(claimed);
        return true;
    }

    /** 抢单：加锁与状态推进在同一个短事务里。 */
    public Long claimNextRun() {
        return transactionTemplate.execute(status -> {
            BacktestRunEntity run = runMapper.lockNextQueued();
            if (run == null) {
                return null;
            }
            Instant now = Instant.now();
            runMapper.transition(
                    run.getId(),
                    BacktestRunStatusEnum.ARCHIVING.getCode(),
                    BacktestStageEnum.ARCHIVING.getCode(),
                    false,
                    now,
                    now,
                    null,
                    null
            );
            log.info("回测开始 runKey={} 场次={}", run.getRunKey(), run.getTotalMatchCount());
            return run.getId();
        });
    }

    /** 把 run 推到终态。任何异常都记为 FAILED，不让它变成永久 RUNNING。 */
    public void execute(Long runId) {
        BacktestRunEntity run = runMapper.selectById(runId);
        if (run == null) {
            return;
        }
        try {
            List<GroupEngine> engines = buildEngines(run);
            List<BacktestCandidateDto> candidates = candidateMapper.selectCandidates(
                    run.getDateFrom(), run.getDateTo(), run.getLeagueNames());

            ArchiveStats archiveStats = runArchiving(runId, candidates);
            if (archiveStats.cancelled()) {
                finish(runId, BacktestRunStatusEnum.CANCELLED, "CANCELLED", "用户取消", true);
                return;
            }

            transition(runId, BacktestRunStatusEnum.EVALUATING, BacktestStageEnum.EVALUATING);
            CaseStats caseStats = runEvaluation(runId, candidates, engines);
            if (caseStats.cancelled()) {
                finish(runId, BacktestRunStatusEnum.CANCELLED, "CANCELLED", "用户取消", true);
                return;
            }

            transition(runId, BacktestRunStatusEnum.SETTLING, BacktestStageEnum.SETTLING);
            int settled = runSettlement(runId);

            transition(runId, BacktestRunStatusEnum.AGGREGATING, BacktestStageEnum.AGGREGATING);
            List<BacktestCaseEntity> allCases = caseMapper.selectByRunId(runId);
            int metrics = metricsService.recompute(runId, allCases, run.getModelVersion());
            long stillPending = caseMapper.countPending(runId);

            // 有补采失败、有场次没有可用序列、或有样本赛果还没到 → PARTIAL。三者都不是「跑挂了」，
            // 但也都不是「结论完整」，混成 SUCCEEDED 会让对比页把一个残缺批次当成完整批次。
            BacktestRunStatusEnum terminal =
                    archiveStats.failed() > 0 || archiveStats.unavailable() > 0 || stillPending > 0
                            ? BacktestRunStatusEnum.PARTIAL
                            : BacktestRunStatusEnum.SUCCEEDED;
            String note = terminal == BacktestRunStatusEnum.PARTIAL
                    ? "补采失败 " + archiveStats.failed() + " 场 / 上游无可用序列 "
                            + archiveStats.unavailable() + " 场 / 待结算样本 " + stillPending
                    : null;
            finish(runId, terminal, null, note, true);
            log.info("回测结束 runKey={} 状态={} 归档(复用{} 新采{} 失败{} 无序列{}) 样本={} 结算={} 指标={} 待结算={}",
                    run.getRunKey(), terminal, archiveStats.reused(), archiveStats.fetched(),
                    archiveStats.failed(), archiveStats.unavailable(),
                    caseStats.inserted(), settled, metrics, stillPending);
        } catch (RuntimeException exception) {
            log.error("回测执行失败 runKey={}", run.getRunKey(), exception);
            fail(runId, exception);
        }
    }

    /**
     * 启动自愈：把心跳超时的非终态 run 标为 {@code INTERRUPTED}。
     *
     * <p>判据是 {@code last_heartbeat_at}（为空时退回 {@code started_at}）而不是「状态不是 QUEUED」：
     * 后者会把另一个实例正在跑的 run 一起标成中断，然后两个实例都去抢同一批场次。
     * 这是 {@code sync_runs} 那次「孤儿 RUNNING」的同类问题，
     * 区别在于那次只能人工改库，这次有可续跑的状态。</p>
     *
     * @return 被标为中断的行数
     */
    public int recoverStaleRuns() {
        Instant threshold = Instant.now().minus(properties.worker().heartbeatTimeout());
        int recovered = 0;
        for (BacktestRunEntity run : runMapper.selectInFlight()) {
            if (run.getRunStatus() == BacktestRunStatusEnum.QUEUED) {
                // 排队中还没有心跳，不归自愈管：它可能是刚入队、正被别的实例抢走。
                continue;
            }
            Instant last = run.getLastHeartbeatAt() != null ? run.getLastHeartbeatAt() : run.getStartedAt();
            if (last != null && last.isAfter(threshold)) {
                continue;
            }
            runMapper.transition(
                    run.getId(),
                    BacktestRunStatusEnum.INTERRUPTED.getCode(),
                    run.getRunStage() == null ? BacktestStageEnum.NONE.getCode() : run.getRunStage().getCode(),
                    true,
                    run.getStartedAt(),
                    Instant.now(),
                    "STALE_RUN_RECOVERED",
                    "心跳超时，进程可能已退出；可按续跑继续"
            );
            recovered++;
            log.warn("回测心跳超时已标记中断 runKey={} lastHeartbeat={}", run.getRunKey(), last);
        }
        return recovered;
    }

    private List<GroupEngine> buildEngines(BacktestRunEntity run) {
        List<ParamGroup> groups = BacktestParameterSets.fromStored(run.getParameterSets(), modelProperties);
        return groups.stream()
                .map(group -> new GroupEngine(
                        group.groupKey(),
                        // meterRegistry 传 null：回测的指标是结果表里的数字，不是 Micrometer 计数器。
                        // 引擎的 5 个 record 方法已按可空实现，传 null 不会 NPE。
                        new AsianDecisionEngine(aggregationProperties, group.model(), null)))
                .toList();
    }

    private ArchiveStats runArchiving(Long runId, List<BacktestCandidateDto> candidates) {
        // 两套计数：batch 只用于「这一批的增量」刷进 addProgress，total 用于终态判断。
        // 只保留一套的话，终态会按<b>最后一批</b>的失败数判断，前面批次的失败被静默丢掉，
        // 表现是「明明有 30 场没采到，run 却报 SUCCEEDED」。
        ArchiveCounter total = new ArchiveCounter();
        ArchiveCounter batch = new ArchiveCounter();
        int batchSize = properties.worker().batchSize();

        for (BacktestCandidateDto candidate : candidates) {
            if (isCancelled(runId)) {
                flushArchiveProgress(runId, batch);
                return new ArchiveStats(total.reused(), total.fetched(), total.failed(),
                        total.unavailable(), true);
            }
            ArchiveOutcome outcome = evaluationService.archive(candidate);
            total.add(outcome);
            batch.add(outcome);
            if (batch.processed() >= batchSize) {
                flushArchiveProgress(runId, batch);
                batch.resetQuotaAndCounts();
            }
        }
        flushArchiveProgress(runId, batch);
        return new ArchiveStats(total.reused(), total.fetched(), total.failed(), total.unavailable(), false);
    }

    private CaseStats runEvaluation(
            Long runId,
            List<BacktestCandidateDto> candidates,
            List<GroupEngine> engines
    ) {
        int totalInserted = 0;
        int batchInserted = 0;
        int batchProcessed = 0;
        int batchSize = properties.worker().batchSize();
        // processed_match_count 只由归档阶段推进，语义是「已归档完成的场次」。
        // 判定阶段绝不能再加一次：同一批场次会被两个阶段各算一遍，10 场变成 20 场，
        // 直接撞上 ck_backtest_runs_counts（processed <= total）——而且是在真实库里跑
        // 到判定阶段才撞，因为归档刚结束时这个值恰好还等于 total。
        // 判定阶段的进展用 case_count 与 run_stage 表达。
        for (BacktestCandidateDto candidate : candidates) {
            if (isCancelled(runId)) {
                flushEvaluationProgress(runId, batchInserted);
                return new CaseStats(totalInserted, true);
            }
            OddsHistoryArchiveEntity archive = evaluationService.loadArchive(candidate);
            if (archive == null || archive.getFetchStatus() != BacktestArchiveStatusEnum.SUCCESS) {
                // 归档不可用的场次不写样本：否则同一场会出现 N 组重复的「无数据」行，
                // 把参数组对比的分母污染成「大部分场次是空的」。
                continue;
            }
            for (BacktestCaseEntity entity : evaluationService.decide(runId, candidate, archive, engines)) {
                int written = caseMapper.insertIgnore(entity);
                batchInserted += written;
                totalInserted += written;
            }
            batchProcessed++;
            if (batchProcessed % batchSize == 0) {
                flushEvaluationProgress(runId, batchInserted);
                batchProcessed = 0;
                batchInserted = 0;
            }
        }
        flushEvaluationProgress(runId, batchInserted);
        return new CaseStats(totalInserted, false);
    }

    private int runSettlement(Long runId) {
        int settled = 0;
        for (BacktestCaseEntity entity : caseMapper.selectPendingByRunId(runId)) {
            Settlement settlement = settlementService.settle(entity);
            if (!settlement.settled()) {
                continue;
            }
            settled += caseMapper.settleOnce(
                    entity.getId(),
                    settlement.resultStatus().getCode(),
                    settlement.homeScore(),
                    settlement.awayScore(),
                    settlement.handicapStatus() == null ? null : settlement.handicapStatus().getCode(),
                    settlement.handicapProfit(),
                    settlement.totalsStatus() == null ? null : settlement.totalsStatus().getCode(),
                    settlement.totalsProfit(),
                    settlement.settledAt()
            );
        }
        return settled;
    }

    /**
     * 归档阶段的进度：推进「已归档场次」与归档计数。
     *
     * <p>{@code processed_match_count} <b>只在这里增加</b>。它被 DB 的
     * {@code ck_backtest_runs_counts}（{@code processed <= total}）保护，而归档刚结束时
     * 它恰好等于 total——同一批场次若在判定阶段再被加一遍，10 场会变成 20 场，
     * 撞约束的时机是「归档成功、判定开始时」，看上去像判定坏了。</p>
     */
    private void flushArchiveProgress(Long runId, ArchiveCounter counter) {
        writeProgress(runId, counter.processed(), counter.reused(), counter.fetched(), counter.failed(),
                0, counter.quota(), BacktestStageEnum.ARCHIVING);
    }

    /**
     * 判定阶段的进度：只推进样本数。
     *
     * <p>签名里<b>没有</b> processed 参数，是为了让「判定阶段又加了一次场次」这件事
     * 在编译期就写不出来，而不是靠注释提醒。</p>
     */
    private void flushEvaluationProgress(Long runId, int cases) {
        writeProgress(runId, 0, 0, 0, 0, cases, 0, BacktestStageEnum.EVALUATING);
    }

    private void writeProgress(
            Long runId,
            int processed,
            int reused,
            int fetched,
            int failed,
            int cases,
            int quota,
            BacktestStageEnum stage
    ) {
        Instant now = Instant.now();
        if (processed == 0 && reused == 0 && fetched == 0 && failed == 0 && cases == 0 && quota == 0) {
            // 没有可汇报的数字，但心跳必须续：判定阶段整批都被跳过时，这里是唯一的存活证据。
            // 心跳一停，重启自愈会把还在跑的 run 判成 INTERRUPTED。
            runMapper.touchHeartbeat(runId, now);
            return;
        }
        runMapper.addProgress(runId, processed, reused, fetched, failed, cases, quota, stage.getCode(), now);
    }

    private boolean isCancelled(Long runId) {
        BacktestRunEntity current = runMapper.selectById(runId);
        return current != null && Boolean.TRUE.equals(current.getCancelRequested());
    }

    private void transition(Long runId, BacktestRunStatusEnum status, BacktestStageEnum stage) {
        runMapper.transition(runId, status.getCode(), stage.getCode(), false, null, Instant.now(), null, null);
    }

    private void finish(
            Long runId,
            BacktestRunStatusEnum status,
            String errorCode,
            String errorMessage,
            boolean terminal
    ) {
        runMapper.transition(
                runId,
                status.getCode(),
                BacktestStageEnum.DONE.getCode(),
                terminal,
                null,
                Instant.now(),
                errorCode,
                errorMessage
        );
    }

    private void fail(Long runId, RuntimeException exception) {
        String message = exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
        if (message.length() > 1000) {
            message = message.substring(0, 1000);
        }
        try {
            finish(runId, BacktestRunStatusEnum.FAILED, "EXECUTION_FAILED", message, true);
        } catch (RuntimeException second) {
            // 连标记失败都失败（例如 DB 已断）：至少留下日志，不要吞掉原始异常。
            log.error("回测标记失败也失败 runId={}", runId, second);
        }
    }

    /** 归档阶段计数。 */
    /**
     * 归档阶段的计数器。
     *
     * <p><b>三个桶必须恰好划分 {@code processed}</b>：DB 的 {@code ck_backtest_runs_counts}
     * 要求 {@code reused + fetched + failed = processed_match_count}。这意味着「失败的补采」
     * 只能进 {@code failed}，不能再进 {@code fetched}——用两个独立 {@code if} 写就会得到
     * 「复用 10 + 新采 40 + 失败 1 = 51，而 processed = 50」，且**只在真有失败时才成立**：
     * 全成功或全复用的批次三个桶天然吻合，这个错误藏得住。把互斥关系收进 {@link #add}
     * 之后，调用方写不出第四种组合。</p>
     *
     * <p>{@code unavailable} 是第四个数，但它<b>不落库</b>：负缓存（上游无该场记录 / 无变快点）
     * 走的是复用，已经算在 {@code reused} 里，再落一列就会破坏上面那条等式。
     * 它只用来让终态判定知道「这批有场次没有可用序列」，不该报 SUCCEEDED。</p>
     */
    static final class ArchiveCounter {

        private int processed;
        private int reused;
        private int fetched;
        private int failed;
        private int unavailable;
        private int quota;

        void add(ArchiveOutcome outcome) {
            processed++;
            quota += outcome.quotaCost();
            if (outcome.reused()) {
                reused++;
                if (outcome.status() != BacktestArchiveStatusEnum.SUCCESS) {
                    unavailable++;
                }
            } else if (outcome.status() == BacktestArchiveStatusEnum.SUCCESS) {
                fetched++;
            } else {
                failed++;
            }
        }

        /** 刷进库之后把这一批清零；{@code unavailable} 与累计语义无关，一并清零即可。 */
        void resetQuotaAndCounts() {
            processed = 0;
            reused = 0;
            fetched = 0;
            failed = 0;
            unavailable = 0;
            quota = 0;
        }

        int processed() {
            return processed;
        }

        int reused() {
            return reused;
        }

        int fetched() {
            return fetched;
        }

        int failed() {
            return failed;
        }

        int unavailable() {
            return unavailable;
        }

        int quota() {
            return quota;
        }
    }

    /**
     * 归档阶段的结论。
     *
     * @param reused      复用已有归档的场次（任意状态，含负缓存）
     * @param fetched     本次新采且成功的场次
     * @param failed      本次新采且失败的场次
     * @param unavailable 复用到的负缓存场次（上游无记录 / 无变快点）——**只用于终态判定，不落库**，
     *                    因为它已经算在 {@code reused} 里，再落一列就会破坏「三桶恰好划分 processed」
     * @param cancelled   是否因取消而提前结束
     */
    private record ArchiveStats(int reused, int fetched, int failed, int unavailable, boolean cancelled) {
    }

    /** 判定阶段计数。 */
    private record CaseStats(int inserted, boolean cancelled) {
    }
}
