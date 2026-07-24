package com.jingcaicompass.odds.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jingcaicompass.data.dto.ProviderParseResult;
import com.jingcaicompass.odds.dto.AsianOddsLineDto;
import com.jingcaicompass.odds.entity.AsianOddsSnapshot;
import com.jingcaicompass.odds.enums.OddsSnapshotTypeEnum;
import com.jingcaicompass.odds.mapper.AsianOddsSnapshotMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 亚盘快照写库：已确认比赛下按博彩公司+让球线幂等追加；有完整 totals 则同写入。
 */
@Component
@ConditionalOnBean(DataSource.class)
public class AsianOddsSnapshotWriter {

    private final AsianOddsSnapshotMapper asianOddsSnapshotMapper;

    public AsianOddsSnapshotWriter(AsianOddsSnapshotMapper asianOddsSnapshotMapper) {
        this.asianOddsSnapshotMapper = asianOddsSnapshotMapper;
    }

    /**
     * 写入多条 line；单条失败不影响其余。
     *
     * @return 写入结果与不完整 line 跳过数
     */
    public WriteResult writeLines(
            Long matchId,
            String providerCode,
            List<AsianOddsLineDto> lines,
            String rawPayloadHash
    ) {
        if (matchId == null || !StringUtils.hasText(providerCode) || lines == null || lines.isEmpty()) {
            return WriteResult.empty();
        }

        int successCount = 0;
        int failureCount = 0;
        int snapshotInsertCount = 0;
        int skippedIncomplete = 0;
        List<String> errors = new ArrayList<>();

        for (AsianOddsLineDto line : lines) {
            try {
                if (!isAhComplete(line)) {
                    skippedIncomplete++;
                    continue;
                }
                BigDecimal totalLine = null;
                BigDecimal overOdds = null;
                BigDecimal underOdds = null;
                if (isTotalsComplete(line)) {
                    totalLine = line.totalLine();
                    overOdds = line.overOdds();
                    underOdds = line.underOdds();
                } else if (hasPartialTotals(line)) {
                    skippedIncomplete++;
                    // AH 仍写入，totals 置空
                }

                if (appendIfChanged(
                        matchId,
                        providerCode.trim(),
                        line,
                        totalLine,
                        overOdds,
                        underOdds,
                        rawPayloadHash
                )) {
                    snapshotInsertCount++;
                }
                successCount++;
            } catch (RuntimeException exception) {
                failureCount++;
                errors.add(summarize(line, exception));
            }
        }

        String errorMessage = errors.isEmpty() ? null : String.join("; ", errors);
        return new WriteResult(
                new ProviderParseResult(successCount, failureCount, truncate(errorMessage)),
                snapshotInsertCount,
                skippedIncomplete
        );
    }

    private boolean appendIfChanged(
            Long matchId,
            String providerCode,
            AsianOddsLineDto line,
            BigDecimal totalLine,
            BigDecimal overOdds,
            BigDecimal underOdds,
            String rawPayloadHash
    ) {
        AsianOddsSnapshot latest = asianOddsSnapshotMapper.selectOne(new LambdaQueryWrapper<AsianOddsSnapshot>()
                .eq(AsianOddsSnapshot::getMatchId, matchId)
                .eq(AsianOddsSnapshot::getProviderCode, providerCode)
                .eq(AsianOddsSnapshot::getBookmakerCode, line.bookmakerCode().trim())
                .eq(AsianOddsSnapshot::getHandicapLine, line.handicapLine())
                .orderByDesc(AsianOddsSnapshot::getCapturedAt)
                .last("LIMIT 1"));

        if (latest != null && sameContent(latest, line, totalLine, overOdds, underOdds)) {
            return false;
        }

        AsianOddsSnapshot snapshot = new AsianOddsSnapshot();
        snapshot.setMatchId(matchId);
        snapshot.setProviderCode(providerCode);
        snapshot.setBookmakerCode(line.bookmakerCode().trim());
        snapshot.setHandicapLine(line.handicapLine());
        snapshot.setHomeOdds(line.homeOdds());
        snapshot.setAwayOdds(line.awayOdds());
        snapshot.setTotalLine(totalLine);
        snapshot.setOverOdds(overOdds);
        snapshot.setUnderOdds(underOdds);
        snapshot.setSnapshotType(latest == null ? OddsSnapshotTypeEnum.FIRST_SEEN : OddsSnapshotTypeEnum.PRE_KICKOFF);
        snapshot.setCapturedAt(Instant.now());
        if (line.providerUpdatedAt() != null) {
            snapshot.setProviderUpdatedAt(line.providerUpdatedAt().toInstant());
        }
        snapshot.setRawPayloadHash(rawPayloadHash);
        asianOddsSnapshotMapper.insert(snapshot);
        return true;
    }

    private static boolean sameContent(
            AsianOddsSnapshot latest,
            AsianOddsLineDto line,
            BigDecimal totalLine,
            BigDecimal overOdds,
            BigDecimal underOdds
    ) {
        return decimalEquals(latest.getHomeOdds(), line.homeOdds())
                && decimalEquals(latest.getAwayOdds(), line.awayOdds())
                && decimalEquals(latest.getTotalLine(), totalLine)
                && decimalEquals(latest.getOverOdds(), overOdds)
                && decimalEquals(latest.getUnderOdds(), underOdds);
    }

    private static boolean isAhComplete(AsianOddsLineDto line) {
        return line != null
                && StringUtils.hasText(line.bookmakerCode())
                && line.handicapLine() != null
                && line.homeOdds() != null
                && line.awayOdds() != null;
    }

    private static boolean isTotalsComplete(AsianOddsLineDto line) {
        return line.totalLine() != null && line.overOdds() != null && line.underOdds() != null;
    }

    private static boolean hasPartialTotals(AsianOddsLineDto line) {
        int present = 0;
        if (line.totalLine() != null) {
            present++;
        }
        if (line.overOdds() != null) {
            present++;
        }
        if (line.underOdds() != null) {
            present++;
        }
        return present > 0 && present < 3;
    }

    private static boolean decimalEquals(BigDecimal left, BigDecimal right) {
        if (left == null && right == null) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return left.compareTo(right) == 0;
    }

    private static String summarize(AsianOddsLineDto line, RuntimeException exception) {
        String bookmaker = line == null || line.bookmakerCode() == null ? "?" : line.bookmakerCode();
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        return bookmaker + ":" + message;
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    public record WriteResult(
            ProviderParseResult parseResult,
            int snapshotInsertCount,
            int skippedIncomplete
    ) {
        public static WriteResult empty() {
            return new WriteResult(ProviderParseResult.empty(), 0, 0);
        }
    }
}
