package com.jingcaicompass.match.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jingcaicompass.data.dto.ProviderParseResult;
import com.jingcaicompass.match.dto.SportteryPoolSyncItemDto;
import com.jingcaicompass.match.entity.MatchEntity;
import com.jingcaicompass.match.entity.SportteryPoolSnapshot;
import com.jingcaicompass.match.mapper.MatchMapper;
import com.jingcaicompass.match.mapper.SportteryPoolSnapshotMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 将同步条目幂等写入 matches，并按盘口变化追加快照。
 */
@Component
@ConditionalOnBean(DataSource.class)
public class SportteryPoolMatchWriter {

    private final MatchMapper matchMapper;
    private final SportteryPoolSnapshotMapper snapshotMapper;

    public SportteryPoolMatchWriter(MatchMapper matchMapper, SportteryPoolSnapshotMapper snapshotMapper) {
        this.matchMapper = matchMapper;
        this.snapshotMapper = snapshotMapper;
    }

    public WriteResult writeAll(List<SportteryPoolSyncItemDto> items, String rawPayloadHash) {
        if (items == null || items.isEmpty()) {
            return WriteResult.empty();
        }

        int successCount = 0;
        int failureCount = 0;
        int matchUpsertCount = 0;
        int snapshotInsertCount = 0;
        List<String> errors = new ArrayList<>();

        for (SportteryPoolSyncItemDto item : items) {
            try {
                MatchEntity match = upsertMatch(item);
                matchUpsertCount++;
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

    private MatchEntity upsertMatch(SportteryPoolSyncItemDto item) {
        MatchEntity existing = matchMapper.selectOne(new LambdaQueryWrapper<MatchEntity>()
                .eq(MatchEntity::getLotteryMatchNo, item.lotteryMatchNo())
                .eq(MatchEntity::getLotteryDate, item.lotteryDate())
                .last("LIMIT 1"));

        if (existing == null) {
            MatchEntity created = new MatchEntity();
            applyMatchFields(created, item);
            matchMapper.insert(created);
            return created;
        }

        applyMatchFields(existing, item);
        matchMapper.updateById(existing);
        return existing;
    }

    private void applyMatchFields(MatchEntity entity, SportteryPoolSyncItemDto item) {
        entity.setLotteryMatchNo(item.lotteryMatchNo());
        entity.setLotteryDate(item.lotteryDate());
        entity.setLeagueName(item.leagueName());
        entity.setHomeTeamName(item.homeTeamName());
        entity.setAwayTeamName(item.awayTeamName());
        entity.setKickoffTime(item.kickoffTime().toInstant());
        entity.setMatchStatus(item.matchStatus());
    }

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
