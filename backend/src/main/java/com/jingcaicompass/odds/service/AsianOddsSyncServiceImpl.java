package com.jingcaicompass.odds.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.data.dto.ProviderParseResult;
import com.jingcaicompass.data.dto.ProviderSyncOutcome;
import com.jingcaicompass.data.entity.DataSyncRun;
import com.jingcaicompass.data.enums.ProviderDataTypeEnum;
import com.jingcaicompass.data.mapper.DataSyncRunMapper;
import com.jingcaicompass.data.service.ProviderSyncTemplate;
import com.jingcaicompass.match.dto.MatchMapRequestDto;
import com.jingcaicompass.match.dto.MatchMapResultDto;
import com.jingcaicompass.match.entity.MatchEntity;
import com.jingcaicompass.match.enums.MatchMapOutcomeEnum;
import com.jingcaicompass.match.enums.MappingStatusEnum;
import com.jingcaicompass.match.mapper.MatchMapper;
import com.jingcaicompass.match.service.MatchMappingService;
import com.jingcaicompass.odds.client.AsianOddsProviderProperties;
import com.jingcaicompass.odds.dto.AsianOddsMatchOddsDto;
import com.jingcaicompass.odds.dto.AsianOddsQueryDto;
import com.jingcaicompass.odds.dto.AsianOddsSyncRequestDto;
import com.jingcaicompass.odds.dto.AsianOddsSyncResultDto;
import com.jingcaicompass.odds.entity.AsianOddsSnapshot;
import com.jingcaicompass.odds.mapper.AsianOddsSnapshotMapper;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

/** 亚盘同步实现：额度门禁 → ProviderSyncTemplate → 映射门禁 → 追加快照。 */
@Service
@ConditionalOnBean(DataSource.class)
public class AsianOddsSyncServiceImpl implements AsianOddsSyncService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final AsianOddsProvider asianOddsProvider;
    private final ProviderSyncTemplate providerSyncTemplate;
    private final AsianOddsPayloadMapper payloadMapper;
    private final AsianOddsSnapshotWriter snapshotWriter;
    private final MatchMappingService matchMappingService;
    private final MatchMapper matchMapper;
    private final AsianOddsSnapshotMapper asianOddsSnapshotMapper;
    private final DataSyncRunMapper dataSyncRunMapper;
    private final AsianOddsProviderProperties asianOddsProviderProperties;
    private final ObjectMapper objectMapper;

    public AsianOddsSyncServiceImpl(
            AsianOddsProvider asianOddsProvider,
            ProviderSyncTemplate providerSyncTemplate,
            AsianOddsPayloadMapper payloadMapper,
            AsianOddsSnapshotWriter snapshotWriter,
            MatchMappingService matchMappingService,
            MatchMapper matchMapper,
            AsianOddsSnapshotMapper asianOddsSnapshotMapper,
            DataSyncRunMapper dataSyncRunMapper,
            AsianOddsProviderProperties asianOddsProviderProperties,
            ObjectMapper objectMapper
    ) {
        this.asianOddsProvider = asianOddsProvider;
        this.providerSyncTemplate = providerSyncTemplate;
        this.payloadMapper = payloadMapper;
        this.snapshotWriter = snapshotWriter;
        this.matchMappingService = matchMappingService;
        this.matchMapper = matchMapper;
        this.asianOddsSnapshotMapper = asianOddsSnapshotMapper;
        this.dataSyncRunMapper = dataSyncRunMapper;
        this.asianOddsProviderProperties = asianOddsProviderProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public AsianOddsSyncResultDto sync(AsianOddsSyncRequestDto request) {
        // 1) 业务日与查询时间窗
        LocalDate businessDate = request == null || request.businessDate() == null
                ? LocalDate.now(SHANGHAI)
                : request.businessDate();

        List<MatchEntity> dayMatches = matchMapper.selectList(new LambdaQueryWrapper<MatchEntity>()
                .eq(MatchEntity::getLotteryDate, businessDate));
        int sportteryMatchCount = dayMatches.size();

        AsianOddsQueryDto query = buildQuery(businessDate, dayMatches);
        Instant dayStart = businessDate.atStartOfDay(SHANGHAI).toInstant();
        Instant dayEnd = businessDate.plusDays(1).atStartOfDay(SHANGHAI).toInstant();

        // 2) 额度门禁
        int usedQuota = sumQuotaCost(asianOddsProvider.providerCode(), dayStart, dayEnd);
        int threshold = asianOddsProviderProperties.quotaWarningThreshold();
        if (threshold > 0 && usedQuota >= threshold) {
            return new AsianOddsSyncResultDto(
                    null,
                    true,
                    0,
                    0,
                    0,
                    0,
                    sportteryMatchCount,
                    countCoveredMatches(dayMatches),
                    coverageRate(sportteryMatchCount, countCoveredMatches(dayMatches)),
                    usedQuota
            );
        }

        AtomicInteger snapshotInsertCount = new AtomicInteger();
        AtomicInteger skippedUnmapped = new AtomicInteger();
        AtomicInteger skippedLive = new AtomicInteger();
        AtomicInteger skippedIncomplete = new AtomicInteger();

        // 3) 模板：拉 raw → 幂等入库 → 解析写快照
        ProviderSyncOutcome outcome = providerSyncTemplate.execute(
                asianOddsProvider.providerCode(),
                ProviderDataTypeEnum.ASIAN_ODDS,
                () -> asianOddsProvider.fetchPreMatchOddsRaw(query),
                (dataType, requestKey, payload) -> {
                    List<AsianOddsMatchOddsDto> matches = payloadMapper.parseMatches(toJson(payload.getPayload()));
                    int success = 0;
                    int failure = 0;
                    StringBuilder errors = new StringBuilder();

                    for (AsianOddsMatchOddsDto matchOdds : matches) {
                        try {
                            if (matchOdds.live()) {
                                skippedLive.incrementAndGet();
                                success++;
                                continue;
                            }
                            MatchMapResultDto mapped = matchMappingService.resolve(toMapRequest(matchOdds));
                            if (!isConfirmed(mapped)) {
                                skippedUnmapped.incrementAndGet();
                                success++;
                                continue;
                            }
                            List<com.jingcaicompass.odds.dto.AsianOddsLineDto> lines =
                                    matchOdds.lines() == null ? List.of() : matchOdds.lines();
                            if (lines.isEmpty()) {
                                skippedIncomplete.incrementAndGet();
                                success++;
                                continue;
                            }
                            AsianOddsSnapshotWriter.WriteResult writeResult = snapshotWriter.writeLines(
                                    mapped.matchId(),
                                    asianOddsProvider.providerCode(),
                                    lines,
                                    payload.getPayloadHash()
                            );
                            snapshotInsertCount.addAndGet(writeResult.snapshotInsertCount());
                            skippedIncomplete.addAndGet(writeResult.skippedIncomplete());
                            success += writeResult.parseResult().successCount();
                            failure += writeResult.parseResult().failureCount();
                            if (writeResult.parseResult().errorMessage() != null) {
                                if (!errors.isEmpty()) {
                                    errors.append("; ");
                                }
                                errors.append(writeResult.parseResult().errorMessage());
                            }
                        } catch (RuntimeException exception) {
                            failure++;
                            if (!errors.isEmpty()) {
                                errors.append("; ");
                            }
                            errors.append(matchOdds.providerMatchId()).append(':').append(exception.getMessage());
                        }
                    }

                    String errorMessage = errors.isEmpty() ? null : truncate(errors.toString());
                    if (success == 0 && failure == 0) {
                        return ProviderParseResult.empty();
                    }
                    return new ProviderParseResult(success, failure, errorMessage);
                }
        );

        int covered = countCoveredMatches(dayMatches);
        int quotaCostUsed = usedQuota + (outcome.syncRun() == null || outcome.syncRun().getQuotaCost() == null
                ? 0
                : outcome.syncRun().getQuotaCost());

        return new AsianOddsSyncResultDto(
                outcome,
                false,
                snapshotInsertCount.get(),
                skippedUnmapped.get(),
                skippedLive.get(),
                skippedIncomplete.get(),
                sportteryMatchCount,
                covered,
                coverageRate(sportteryMatchCount, covered),
                quotaCostUsed
        );
    }

    private AsianOddsQueryDto buildQuery(LocalDate businessDate, List<MatchEntity> dayMatches) {
        if (dayMatches == null || dayMatches.isEmpty()) {
            OffsetDateTime from = businessDate.atStartOfDay(SHANGHAI).toOffsetDateTime();
            OffsetDateTime to = businessDate.plusDays(1).atStartOfDay(SHANGHAI).toOffsetDateTime().minusNanos(1);
            return new AsianOddsQueryDto(null, from, to, null);
        }
        Instant minKickoff = dayMatches.stream()
                .map(MatchEntity::getKickoffTime)
                .filter(Objects::nonNull)
                .min(Instant::compareTo)
                .orElse(businessDate.atStartOfDay(SHANGHAI).toInstant());
        Instant maxKickoff = dayMatches.stream()
                .map(MatchEntity::getKickoffTime)
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(businessDate.plusDays(1).atStartOfDay(SHANGHAI).toInstant().minusNanos(1));
        return new AsianOddsQueryDto(
                null,
                OffsetDateTime.ofInstant(minKickoff, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(maxKickoff, ZoneOffset.UTC),
                null
        );
    }

    private MatchMapRequestDto toMapRequest(AsianOddsMatchOddsDto matchOdds) {
        Instant kickoff = matchOdds.kickoffTime() == null ? null : matchOdds.kickoffTime().toInstant();
        return new MatchMapRequestDto(
                asianOddsProvider.providerCode(),
                matchOdds.providerMatchId(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                matchOdds.homeTeamName(),
                matchOdds.awayTeamName(),
                kickoff
        );
    }

    private static boolean isConfirmed(MatchMapResultDto mapped) {
        if (mapped == null || mapped.matchId() == null) {
            return false;
        }
        if (mapped.outcome() == MatchMapOutcomeEnum.REUSED
                || mapped.outcome() == MatchMapOutcomeEnum.AUTO_CONFIRMED) {
            return true;
        }
        MappingStatusEnum status = mapped.mappingStatus();
        return status == MappingStatusEnum.AUTO_CONFIRMED || status == MappingStatusEnum.MANUAL_CONFIRMED;
    }

    private int sumQuotaCost(String providerCode, Instant fromInclusive, Instant toExclusive) {
        List<DataSyncRun> runs = dataSyncRunMapper.selectList(new LambdaQueryWrapper<DataSyncRun>()
                .eq(DataSyncRun::getProviderCode, providerCode)
                .eq(DataSyncRun::getDataType, ProviderDataTypeEnum.ASIAN_ODDS)
                .ge(DataSyncRun::getStartedAt, fromInclusive)
                .lt(DataSyncRun::getStartedAt, toExclusive));
        return runs.stream()
                .mapToInt(run -> run.getQuotaCost() == null ? 0 : run.getQuotaCost())
                .sum();
    }

    private int countCoveredMatches(List<MatchEntity> dayMatches) {
        if (dayMatches == null || dayMatches.isEmpty()) {
            return 0;
        }
        Set<Long> matchIds = new HashSet<>();
        for (MatchEntity match : dayMatches) {
            if (match.getId() == null) {
                continue;
            }
            Long count = asianOddsSnapshotMapper.selectCount(new LambdaQueryWrapper<AsianOddsSnapshot>()
                    .eq(AsianOddsSnapshot::getMatchId, match.getId()));
            if (count != null && count > 0) {
                matchIds.add(match.getId());
            }
        }
        return matchIds.size();
    }

    private static BigDecimal coverageRate(int sportteryMatchCount, int coveredMatchCount) {
        if (sportteryMatchCount <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(coveredMatchCount)
                .divide(BigDecimal.valueOf(sportteryMatchCount), 4, RoundingMode.HALF_UP);
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.DATA_SOURCE_PARSE_FAILED, "无法序列化亚盘原始载荷", exception);
        }
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
