package com.jingcaicompass.odds.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.data.dto.ProviderFetchResult;
import com.jingcaicompass.data.dto.ProviderParseResult;
import com.jingcaicompass.data.dto.ProviderSyncOutcome;
import com.jingcaicompass.data.entity.DataSyncRun;
import com.jingcaicompass.data.enums.ProviderDataTypeEnum;
import com.jingcaicompass.data.mapper.DataSyncRunMapper;
import com.jingcaicompass.data.service.ProviderSyncTemplate;
import com.jingcaicompass.match.dto.EntityNormalizeRequestDto;
import com.jingcaicompass.match.dto.EntityNormalizeResultDto;
import com.jingcaicompass.match.dto.MatchMapRequestDto;
import com.jingcaicompass.match.dto.MatchMapResultDto;
import com.jingcaicompass.match.entity.MatchEntity;
import com.jingcaicompass.match.enums.EntityNormalizeOutcomeEnum;
import com.jingcaicompass.match.enums.MappingStatusEnum;
import com.jingcaicompass.match.enums.MatchMapOutcomeEnum;
import com.jingcaicompass.match.mapper.MatchMapper;
import com.jingcaicompass.match.service.LeagueNormalizationService;
import com.jingcaicompass.match.service.MatchMappingService;
import com.jingcaicompass.match.service.TeamNormalizationService;
import com.jingcaicompass.match.support.ProviderEntityKeySupport;
import com.jingcaicompass.odds.client.AsianOddsProviderProperties;
import com.jingcaicompass.odds.dto.AsianOddsLineDto;
import com.jingcaicompass.odds.dto.AsianOddsMatchOddsDto;
import com.jingcaicompass.odds.dto.AsianOddsQueryDto;
import com.jingcaicompass.odds.dto.AsianOddsSyncRequestDto;
import com.jingcaicompass.odds.dto.AsianOddsSyncResultDto;
import com.jingcaicompass.odds.entity.AsianOddsSnapshot;
import com.jingcaicompass.odds.enums.AsianOddsProviderTypeEnum;
import com.jingcaicompass.odds.mapper.AsianOddsSnapshotMapper;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import com.jingcaicompass.system.provider.ProviderErrorCategory;
import com.jingcaicompass.system.provider.ProviderException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 亚盘同步实现：额度门禁 → ProviderSyncTemplate → 比赛解析门禁 → 追加快照。
 *
 * <p>比赛解析有两条互斥路径，由配置的 provider 决定：</p>
 * <ul>
 *   <li><b>NovaAPI（单源）</b>：赔率响应里的 {@code matchId} 就是赛后结算用的同一个竞彩场次 ID，
 *       直接按 {@code matches.external_match_id} 定位，<b>不经过任何映射表</b>。</li>
 *   <li><b>The Odds API（回退）</b>：仍是「供应商赛事 ID → 已确认映射」，因为它的 ID 体系与竞彩无关。</li>
 * </ul>
 *
 * <p>两条路径并存是刻意的：单源改造期间必须能靠一次配置回退，而映射层要到交付末尾（阶段 5）
 * 才物理删除。不要把 Nova 分支写成「映射查不到就自动确认」——那会让中英文队名的场次
 * 永远拿不到分（打分为精确相等比较），表现为「同步成功、快照 0 条」。</p>
 */
@Service
@ConditionalOnBean(DataSource.class)
public class AsianOddsSyncServiceImpl implements AsianOddsSyncService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final AsianOddsProvider asianOddsProvider;
    private final ProviderSyncTemplate providerSyncTemplate;
    private final AsianOddsPayloadMapper payloadMapper;
    private final AsianOddsSnapshotWriter snapshotWriter;
    private final MatchMappingService matchMappingService;
    private final LeagueNormalizationService leagueNormalizationService;
    private final TeamNormalizationService teamNormalizationService;
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
            LeagueNormalizationService leagueNormalizationService,
            TeamNormalizationService teamNormalizationService,
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
        this.leagueNormalizationService = leagueNormalizationService;
        this.teamNormalizationService = teamNormalizationService;
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

        // 当天没有比赛时直接收尾：NovaAPI 的赔率按场次拉取，没有场次就没有可请求的对象，
        // 继续走下去只会在 Provider 里抛「没有任何比赛」，把「今天没球」记成同步失败。
        if (dayMatches.isEmpty()) {
            return emptyResult(businessDate, sportteryMatchCount, 0);
        }

        SportKeySelection sportKeys = resolveSportKeys(dayMatches);
        AsianOddsQueryDto query = buildQuery(businessDate, dayMatches, sportKeys.sportKeys());
        // 2) 额度门禁：只有 The Odds API 需要「全历史累计 vs 固定预算」的验证期约束。
        //    必须写成 if/else 而不是「先赋 0 再覆盖」：下面门禁分支的 lambda 要捕获这两个值，
        //    二次赋值会让它们不再是 effectively final，编译直接失败。
        int usedQuota;
        int quotaBudget;
        if (asianOddsProviderProperties.provider() == AsianOddsProviderTypeEnum.THE_ODDS_API) {
            usedQuota = sumValidationQuotaCost(asianOddsProvider.providerCode());
            quotaBudget = asianOddsProviderProperties.theOdds().quotaBudget();
        } else {
            usedQuota = 0;
            quotaBudget = 0;
        }
        int estimatedQuotaCost = Math.max(asianOddsProvider.estimateQuotaCost(query), 0);
        if (quotaBudget > 0 && usedQuota + estimatedQuotaCost > quotaBudget) {
            ProviderSyncOutcome blockedOutcome = providerSyncTemplate.execute(
                    asianOddsProvider.providerCode(),
                    ProviderDataTypeEnum.ASIAN_ODDS,
                    () -> {
                        throw new ProviderException(
                                asianOddsProvider.providerCode(),
                                ProviderErrorCategory.QUOTA_EXCEEDED,
                                "validation quota preflight blocked: used=%d estimate=%d budget=%d"
                                        .formatted(usedQuota, estimatedQuotaCost, quotaBudget)
                        );
                    },
                    (dataType, requestKey, payload) -> ProviderParseResult.empty()
            );
            return new AsianOddsSyncResultDto(
                    blockedOutcome,
                    true,
                    0,
                    0,
                    0,
                    0,
                    sportteryMatchCount,
                    sportKeys.unconfiguredLeagueMatchCount(),
                    countCoveredMatches(dayMatches),
                    coverageRate(sportteryMatchCount, countCoveredMatches(dayMatches)),
                    usedQuota
            );
        }

        AtomicInteger snapshotInsertCount = new AtomicInteger();
        AtomicInteger skippedUnmapped = new AtomicInteger();
        AtomicInteger skippedLive = new AtomicInteger();
        AtomicInteger skippedIncomplete = new AtomicInteger();
        Map<String, MatchEntity> matchesByExternalId = indexByExternalMatchId(dayMatches);
        Instant now = Instant.now();

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
                            if (StringUtils.hasText(matchOdds.parseError())) {
                                failure++;
                                appendError(errors, matchOdds.providerMatchId(), matchOdds.parseError());
                                continue;
                            }
                            // 4) 解析比赛：单源按键直连，回退路径仍走映射
                            MatchLookup lookup = lookupMatch(matchOdds, matchesByExternalId, now);
                            if (lookup.skipReason() == SkipReason.LIVE) {
                                skippedLive.incrementAndGet();
                                success++;
                                continue;
                            }
                            if (lookup.skipReason() == SkipReason.UNMAPPED) {
                                skippedUnmapped.incrementAndGet();
                                success++;
                                continue;
                            }

                            // 5) 盘口不完整时只计数，不写快照
                            List<AsianOddsLineDto> lines =
                                    matchOdds.lines() == null ? List.of() : matchOdds.lines();
                            if (lines.isEmpty()) {
                                skippedIncomplete.incrementAndGet();
                                success++;
                                continue;
                            }
                            AsianOddsSnapshotWriter.WriteResult writeResult = snapshotWriter.writeLines(
                                    lookup.matchId(),
                                    asianOddsProvider.providerCode(),
                                    lines,
                                    payload.getPayloadHash()
                            );
                            snapshotInsertCount.addAndGet(writeResult.snapshotInsertCount());
                            skippedIncomplete.addAndGet(writeResult.skippedIncomplete());
                            success += writeResult.parseResult().successCount();
                            failure += writeResult.parseResult().failureCount();
                            if (writeResult.parseResult().errorMessage() != null) {
                                appendError(errors, matchOdds.providerMatchId(), writeResult.parseResult().errorMessage());
                            }
                        } catch (RuntimeException exception) {
                            failure++;
                            appendError(errors, matchOdds.providerMatchId(), exception.getClass().getSimpleName());
                        }
                    }

                    String errorMessage = errors.isEmpty() ? null : truncate(errors.toString());
                    if (success == 0 && failure == 0) {
                        return ProviderParseResult.empty();
                    }
                    return new ProviderParseResult(success, failure, errorMessage);
                }
        );

        // 6) 汇总新增快照、覆盖比赛数和本次额度消耗
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
                sportKeys.unconfiguredLeagueMatchCount(),
                covered,
                coverageRate(sportteryMatchCount, covered),
                quotaCostUsed
        );
    }

    /**
     * 当日无比赛：仍然走一次同步模板，落一条「零场次」的成功记录。
     *
     * <p>不要在这里抛异常：NovaAPI 的赔率按场次拉取，没有场次就没有可请求的对象，
     * 把它记成同步失败会让「今天没球」和「数据源坏了」在运维视图里长得一模一样。</p>
     */
    private AsianOddsSyncResultDto emptyResult(
            LocalDate businessDate,
            int sportteryMatchCount,
            int quotaCostUsed
    ) {
        ProviderFetchResult empty = new ProviderFetchResult(
                asianOddsProvider.providerCode() + "-odds-empty:" + businessDate,
                "{\"provider\":\"" + asianOddsProvider.providerCode() + "\",\"responses\":[]}",
                200,
                Instant.now(),
                0,
                0
        );
        ProviderSyncOutcome outcome = providerSyncTemplate.execute(
                asianOddsProvider.providerCode(),
                ProviderDataTypeEnum.ASIAN_ODDS,
                () -> empty,
                (dataType, requestKey, payload) -> ProviderParseResult.empty()
        );
        return new AsianOddsSyncResultDto(
                outcome,
                false,
                0,
                0,
                0,
                0,
                sportteryMatchCount,
                0,
                0,
                coverageRate(sportteryMatchCount, 0),
                quotaCostUsed
        );
    }

    private AsianOddsQueryDto buildQuery(
            LocalDate businessDate,
            List<MatchEntity> dayMatches,
            List<String> sportKeys
    ) {
        List<String> matchExternalIds = dayMatches.stream()
                .map(MatchEntity::getExternalMatchId)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        if (dayMatches == null || dayMatches.isEmpty()) {
            OffsetDateTime from = businessDate.atStartOfDay(SHANGHAI).toOffsetDateTime();
            OffsetDateTime to = businessDate.plusDays(1).atStartOfDay(SHANGHAI).toOffsetDateTime().minusNanos(1);
            return new AsianOddsQueryDto(null, from, to, null, sportKeys, matchExternalIds);
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
                null,
                sportKeys,
                matchExternalIds
        );
    }

    private SportKeySelection resolveSportKeys(List<MatchEntity> dayMatches) {
        if (asianOddsProviderProperties.provider() != AsianOddsProviderTypeEnum.THE_ODDS_API
                || dayMatches == null || dayMatches.isEmpty()) {
            return SportKeySelection.empty();
        }
        Map<String, String> configured = asianOddsProviderProperties.theOdds().leagueSportKeys();
        Set<String> sportKeys = new LinkedHashSet<>();
        int unconfigured = 0;
        for (MatchEntity match : dayMatches) {
            String leagueName = match == null ? null : match.getLeagueName();
            String sportKey = StringUtils.hasText(leagueName) ? configured.get(leagueName.trim()) : null;
            if (!StringUtils.hasText(sportKey)) {
                unconfigured++;
                continue;
            }
            sportKeys.add(sportKey.trim());
        }
        return new SportKeySelection(List.copyOf(sportKeys), unconfigured);
    }

    /** 单源下 {@code external_match_id} 是权威键，提前建索引避免逐场回表。 */
    private static Map<String, MatchEntity> indexByExternalMatchId(List<MatchEntity> dayMatches) {
        Map<String, MatchEntity> index = new HashMap<>();
        for (MatchEntity match : dayMatches) {
            if (match == null || !StringUtils.hasText(match.getExternalMatchId())) {
                continue;
            }
            index.putIfAbsent(match.getExternalMatchId().trim(), match);
        }
        return index;
    }

    /**
     * 定位这场赔率对应的比赛。
     *
     * <p>NovaAPI 分支多一道<b>开赛判定</b>：{@code /nova/tf/odds} 不返回「是否滚球」标志，
     * 而本表写入的是赛前快照，冻结行按它计账。开赛后仍拿到的赔率若照写，模型会把
     * 「已经在踢的盘」当成赛前共识，这是靠任何下游校验都发现不了的污染，只能在这里拦。</p>
     */
    private MatchLookup lookupMatch(
            AsianOddsMatchOddsDto matchOdds,
            Map<String, MatchEntity> matchesByExternalId,
            Instant now
    ) {
        if (asianOddsProviderProperties.provider() == AsianOddsProviderTypeEnum.NOVA_API) {
            String externalMatchId = matchOdds.providerMatchId();
            MatchEntity match = StringUtils.hasText(externalMatchId)
                    ? matchesByExternalId.get(externalMatchId.trim())
                    : null;
            if (match == null) {
                return MatchLookup.unmapped();
            }
            if (match.getKickoffTime() != null && !match.getKickoffTime().isAfter(now)) {
                return MatchLookup.live();
            }
            return MatchLookup.resolved(match.getId());
        }

        if (matchOdds.live()) {
            return MatchLookup.live();
        }
        MatchMapResultDto mapped = matchMappingService.findConfirmed(
                asianOddsProvider.providerCode(),
                matchOdds.providerMatchId()
        );
        if (mapped == null) {
            mapped = matchMappingService.resolve(toMapRequest(matchOdds));
        }
        if (!isConfirmed(mapped)) {
            return MatchLookup.unmapped();
        }
        return MatchLookup.resolved(mapped.matchId());
    }

    private MatchMapRequestDto toMapRequest(AsianOddsMatchOddsDto matchOdds) {
        Instant kickoff = matchOdds.kickoffTime() == null ? null : matchOdds.kickoffTime().toInstant();
        boolean theOdds = asianOddsProviderProperties.provider() == AsianOddsProviderTypeEnum.THE_ODDS_API;
        String sportKey = theOdds ? matchOdds.providerLeagueId() : null;
        String externalHomeTeamId = theOdds
                ? ProviderEntityKeySupport.scopedNameKey(sportKey, matchOdds.homeTeamName())
                : ProviderEntityKeySupport.nameKey(matchOdds.homeTeamName());
        String externalAwayTeamId = theOdds
                ? ProviderEntityKeySupport.scopedNameKey(sportKey, matchOdds.awayTeamName())
                : ProviderEntityKeySupport.nameKey(matchOdds.awayTeamName());
        Long leagueId = theOdds ? resolveLeagueId(sportKey) : null;
        Long homeTeamId = theOdds ? resolveTeamId(externalHomeTeamId, matchOdds.homeTeamName(), sportKey) : null;
        Long awayTeamId = theOdds ? resolveTeamId(externalAwayTeamId, matchOdds.awayTeamName(), sportKey) : null;
        return new MatchMapRequestDto(
                asianOddsProvider.providerCode(),
                matchOdds.providerMatchId(),
                theOdds ? sportKey : matchOdds.providerLeagueId(),
                externalHomeTeamId,
                externalAwayTeamId,
                matchOdds.homeTeamName(),
                matchOdds.awayTeamName(),
                leagueId,
                homeTeamId,
                awayTeamId,
                sportKey,
                matchOdds.homeTeamName(),
                matchOdds.awayTeamName(),
                kickoff
        );
    }

    private Long resolveLeagueId(String sportKey) {
        if (!StringUtils.hasText(sportKey)) {
            return null;
        }
        EntityNormalizeResultDto result = leagueNormalizationService.resolve(new EntityNormalizeRequestDto(
                asianOddsProvider.providerCode(),
                sportKey,
                sportKey,
                null
        ));
        return confirmedEntityId(result);
    }

    private Long resolveTeamId(String externalTeamId, String displayName, String sportKey) {
        EntityNormalizeResultDto result = teamNormalizationService.resolve(new EntityNormalizeRequestDto(
                asianOddsProvider.providerCode(),
                externalTeamId,
                displayName,
                sportKey
        ));
        return confirmedEntityId(result);
    }

    private static Long confirmedEntityId(EntityNormalizeResultDto result) {
        if (result == null
                || result.entityId() == null
                || result.outcome() != EntityNormalizeOutcomeEnum.RESOLVED
                || result.mappingStatus() != MappingStatusEnum.MANUAL_CONFIRMED) {
            return null;
        }
        return result.entityId();
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

    /**
     * 汇总当前 The Odds API 验证周期已记录的实际 credits，而不是按单日重新放宽预算。
     *
     * <p>只对 The Odds API 生效。NovaAPI 的额度是「每轮实际请求数」，累计历史没有意义——
     * 这个累加口径一旦套到 Nova 上，几天后就会永久超过预算并把同步整条阻断，且不会自动恢复。</p>
     */
    private int sumValidationQuotaCost(String providerCode) {
        List<DataSyncRun> runs = dataSyncRunMapper.selectList(new LambdaQueryWrapper<DataSyncRun>()
                .eq(DataSyncRun::getProviderCode, providerCode)
                .eq(DataSyncRun::getDataType, ProviderDataTypeEnum.ASIAN_ODDS));
        return runs.stream()
                .mapToInt(run -> run.getQuotaCost() == null ? 0 : run.getQuotaCost())
                .sum();
    }

    /** 覆盖数必须按当前来源统计，否则历史 STUB 行会让覆盖率虚高到 100%。 */
    private int countCoveredMatches(List<MatchEntity> dayMatches) {
        if (dayMatches == null || dayMatches.isEmpty()) {
            return 0;
        }
        String providerCode = asianOddsProvider.providerCode();
        Set<Long> matchIds = new HashSet<>();
        for (MatchEntity match : dayMatches) {
            if (match.getId() == null) {
                continue;
            }
            Long count = asianOddsSnapshotMapper.selectCount(new LambdaQueryWrapper<AsianOddsSnapshot>()
                    .eq(AsianOddsSnapshot::getMatchId, match.getId())
                    .eq(AsianOddsSnapshot::getProviderCode, providerCode));
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

    private static void appendError(StringBuilder target, String matchId, String reason) {
        if (!target.isEmpty()) {
            target.append("; ");
        }
        String safeMatchId = StringUtils.hasText(matchId) ? matchId : "unknown";
        String safeReason = StringUtils.hasText(reason) ? reason : "PARSE_FAILURE";
        target.append(safeMatchId).append(':').append(safeReason);
    }

    private record SportKeySelection(List<String> sportKeys, int unconfiguredLeagueMatchCount) {
        private static SportKeySelection empty() {
            return new SportKeySelection(List.of(), 0);
        }
    }

    /** 比赛解析结果：命中 id、或因为哪条原因跳过。 */
    private record MatchLookup(Long matchId, SkipReason skipReason) {

        private static MatchLookup resolved(Long matchId) {
            return new MatchLookup(matchId, null);
        }

        private static MatchLookup unmapped() {
            return new MatchLookup(null, SkipReason.UNMAPPED);
        }

        private static MatchLookup live() {
            return new MatchLookup(null, SkipReason.LIVE);
        }
    }

    private enum SkipReason {
        UNMAPPED,
        LIVE
    }
}
