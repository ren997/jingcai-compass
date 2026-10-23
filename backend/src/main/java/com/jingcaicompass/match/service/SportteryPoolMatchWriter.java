package com.jingcaicompass.match.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jingcaicompass.data.dto.ProviderParseResult;
import com.jingcaicompass.match.dto.SportteryPoolSyncItemDto;
import com.jingcaicompass.match.entity.MatchEntity;
import com.jingcaicompass.match.entity.SportteryPoolSnapshot;
import com.jingcaicompass.match.mapper.LeagueMapper;
import com.jingcaicompass.match.mapper.MatchMapper;
import com.jingcaicompass.match.mapper.MatchResultFactMapper;
import com.jingcaicompass.match.mapper.SportteryPoolSnapshotMapper;
import com.jingcaicompass.match.mapper.TeamMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 体彩比赛池写库：按体彩编号+业务日幂等写 matches，盘口变化时追加快照。
 */
@Component
@ConditionalOnBean(DataSource.class)
public class SportteryPoolMatchWriter {

    private final MatchMapper matchMapper;
    private final SportteryPoolSnapshotMapper snapshotMapper;
    private final MatchResultFactMapper matchResultFactMapper;
    private final LeagueMapper leagueMapper;
    private final TeamMapper teamMapper;

    public SportteryPoolMatchWriter(
            MatchMapper matchMapper,
            SportteryPoolSnapshotMapper snapshotMapper,
            MatchResultFactMapper matchResultFactMapper,
            LeagueMapper leagueMapper,
            TeamMapper teamMapper
    ) {
        this.matchMapper = matchMapper;
        this.snapshotMapper = snapshotMapper;
        this.matchResultFactMapper = matchResultFactMapper;
        this.leagueMapper = leagueMapper;
        this.teamMapper = teamMapper;
    }

    /**
     * 批量写入；单场失败不影响其余场次。
     *
     * @param items 已解析的同步条目
     * @param rawPayloadHash 关联原始载荷哈希
     */
    public WriteResult writeAll(List<SportteryPoolSyncItemDto> items, String rawPayloadHash) {
        if (items == null || items.isEmpty()) {
            return WriteResult.empty();
        }

        int successCount = 0;
        int failureCount = 0;
        int matchUpsertCount = 0;
        int snapshotInsertCount = 0;
        List<String> errors = new ArrayList<>();
        // 字典缓存活一次写库：联赛/球队名 → 标准 ID，避免每场重复查字典表
        MatchReferenceDictionary dictionary = new MatchReferenceDictionary(leagueMapper, teamMapper);

        for (SportteryPoolSyncItemDto item : items) {
            try {
                // 1) 幂等 upsert 比赛主表
                MatchEntity match = upsertMatch(item, dictionary);
                matchUpsertCount++;
                // 2) 与最新快照比对；有变化才追加
                if (appendSnapshotIfChanged(match, item, rawPayloadHash)) {
                    snapshotInsertCount++;
                }
                successCount++;
            } catch (RuntimeException exception) {
                failureCount++;
                errors.add(summarize(item, exception));
            }
        }

        String errorMessage = errors.isEmpty() ? null : String.join("; ", errors);
        return new WriteResult(
                new ProviderParseResult(successCount, failureCount, truncate(errorMessage)),
                matchUpsertCount,
                snapshotInsertCount
        );
    }

    /**
     * 幂等 upsert：外部 ID 优先，竞彩编号+业务日兜底。
     *
     * <p>两个键并存是过渡期的必然：{@code external_match_id} 是单源后的权威键，
     * 但历史数据与手工造数的测试行没有它。查找顺序因此是「先外部 ID，再编号+日期」，
     * 若两者指向不同的行则直接报错——那说明同一编号被两个数据源同时占用，
     * 静默合并会让后续赔率挂到错误的比赛上。</p>
     */
    private MatchEntity upsertMatch(SportteryPoolSyncItemDto item, MatchReferenceDictionary dictionary) {
        MatchEntity byExternal = findByExternalMatchId(item.externalMatchId());
        MatchEntity byLotteryNo = findByLotteryMatchNo(item);

        if (byExternal != null && byLotteryNo != null && !byExternal.getId().equals(byLotteryNo.getId())) {
            throw new IllegalStateException(
                    "external_match_id=%s 与 %s/%s 指向不同比赛（%d vs %d），拒绝静默合并"
                            .formatted(
                                    item.externalMatchId(),
                                    item.lotteryMatchNo(),
                                    item.lotteryDate(),
                                    byExternal.getId(),
                                    byLotteryNo.getId()
                            )
            );
        }

        MatchEntity existing = byExternal != null ? byExternal : byLotteryNo;
        if (existing == null) {
            MatchEntity created = new MatchEntity();
            applyMatchFields(created, item, dictionary);
            matchMapper.insert(created);
            return created;
        }

        applyMatchFields(existing, item, dictionary);
        matchMapper.updateById(existing);
        return existing;
    }

    private MatchEntity findByExternalMatchId(String externalMatchId) {
        if (externalMatchId == null || externalMatchId.isBlank()) {
            return null;
        }
        return matchMapper.selectOne(new LambdaQueryWrapper<MatchEntity>()
                .eq(MatchEntity::getExternalMatchId, externalMatchId)
                .last("LIMIT 1"));
    }

    private MatchEntity findByLotteryMatchNo(SportteryPoolSyncItemDto item) {
        if (item.lotteryMatchNo() == null || item.lotteryDate() == null) {
            return null;
        }
        return matchMapper.selectOne(new LambdaQueryWrapper<MatchEntity>()
                .eq(MatchEntity::getLotteryMatchNo, item.lotteryMatchNo())
                .eq(MatchEntity::getLotteryDate, item.lotteryDate())
                .last("LIMIT 1"));
    }

    private void applyMatchFields(
            MatchEntity entity,
            SportteryPoolSyncItemDto item,
            MatchReferenceDictionary dictionary
    ) {
        entity.setLotteryMatchNo(item.lotteryMatchNo());
        entity.setLotteryDate(item.lotteryDate());
        if (item.externalMatchId() != null && !item.externalMatchId().isBlank()) {
            entity.setExternalMatchId(item.externalMatchId());
        }
        entity.setLeagueName(item.leagueName());
        entity.setHomeTeamName(item.homeTeamName());
        entity.setAwayTeamName(item.awayTeamName());
        // 字典 ID 由展示名派生：单源后名字就是身份。解析不出（名字为空）时保留既有值，
        // 否则某次响应缺了联赛名就会把已经建好的归属清空，公开列表的联赛筛选随之失效。
        Long leagueId = dictionary.leagueId(item.leagueName());
        if (leagueId != null) {
            entity.setLeagueId(leagueId);
        }
        Long homeTeamId = dictionary.teamId(item.homeTeamName());
        if (homeTeamId != null) {
            entity.setHomeTeamId(homeTeamId);
        }
        Long awayTeamId = dictionary.teamId(item.awayTeamName());
        if (awayTeamId != null) {
            entity.setAwayTeamId(awayTeamId);
        }
        entity.setKickoffTime(item.kickoffTime().toInstant());
        // 已有当前赛果事实时不覆盖 match_status：赛程接口没有「比赛是否结束」这一字段，
        // 每日重跑若照写会把已完赛的比赛打回 SCHEDULED，结算候选随即整体消失。
        if (entity.getId() == null || !matchResultFactMapper.existsCurrentByMatchId(entity.getId())) {
            entity.setMatchStatus(item.matchStatus());
        }
    }

    /** 让球/HAD/HHAD/销售状态与最新快照一致则跳过，否则插入新快照行。 */
    private boolean appendSnapshotIfChanged(
            MatchEntity match,
            SportteryPoolSyncItemDto item,
            String rawPayloadHash
    ) {
        SportteryPoolSnapshot latest = snapshotMapper.selectOne(new LambdaQueryWrapper<SportteryPoolSnapshot>()
                .eq(SportteryPoolSnapshot::getMatchId, match.getId())
                .orderByDesc(SportteryPoolSnapshot::getCapturedAt)
                .last("LIMIT 1"));

        if (latest != null && sameSnapshotContent(latest, item)) {
            return false;
        }

        SportteryPoolSnapshot snapshot = new SportteryPoolSnapshot();
        snapshot.setMatchId(match.getId());
        snapshot.setLotteryMatchNo(item.lotteryMatchNo());
        snapshot.setLotteryDate(item.lotteryDate());
        snapshot.setOfficialHandicap(item.officialHandicap());
        snapshot.setHadHomeSp(item.hadHomeSp());
        snapshot.setHadDrawSp(item.hadDrawSp());
        snapshot.setHadAwaySp(item.hadAwaySp());
        snapshot.setHhadHomeSp(item.hhadHomeSp());
        snapshot.setHhadDrawSp(item.hhadDrawSp());
        snapshot.setHhadAwaySp(item.hhadAwaySp());
        snapshot.setSellStatus(item.sellStatus());
        snapshot.setCapturedAt(Instant.now());
        snapshot.setRawPayloadHash(rawPayloadHash);
        snapshotMapper.insert(snapshot);
        return true;
    }

    private boolean sameSnapshotContent(SportteryPoolSnapshot latest, SportteryPoolSyncItemDto item) {
        return decimalEquals(latest.getOfficialHandicap(), item.officialHandicap())
                && decimalEquals(latest.getHadHomeSp(), item.hadHomeSp())
                && decimalEquals(latest.getHadDrawSp(), item.hadDrawSp())
                && decimalEquals(latest.getHadAwaySp(), item.hadAwaySp())
                && decimalEquals(latest.getHhadHomeSp(), item.hhadHomeSp())
                && decimalEquals(latest.getHhadDrawSp(), item.hhadDrawSp())
                && decimalEquals(latest.getHhadAwaySp(), item.hhadAwaySp())
                && Objects.equals(latest.getSellStatus(), item.sellStatus());
    }

    private boolean decimalEquals(BigDecimal left, BigDecimal right) {
        if (left == null && right == null) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return left.compareTo(right) == 0;
    }

    private String summarize(SportteryPoolSyncItemDto item, RuntimeException exception) {
        String matchNo = item == null ? "?" : item.lotteryMatchNo();
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        return matchNo + ":" + message;
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    public record WriteResult(
            ProviderParseResult parseResult,
            int matchUpsertCount,
            int snapshotInsertCount
    ) {
        public static WriteResult empty() {
            return new WriteResult(ProviderParseResult.empty(), 0, 0);
        }
    }
}
