package com.jingcaicompass.match.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jingcaicompass.data.entity.RawDataPayload;
import com.jingcaicompass.data.enums.ProviderDataTypeEnum;
import com.jingcaicompass.data.mapper.RawDataPayloadMapper;
import com.jingcaicompass.match.dto.MatchDetailQueryDto;
import com.jingcaicompass.match.dto.MatchListCriteriaDto;
import com.jingcaicompass.match.dto.MatchListQueryDto;
import com.jingcaicompass.match.entity.MatchEntity;
import com.jingcaicompass.match.entity.SportteryPoolSnapshot;
import com.jingcaicompass.match.enums.MatchDataAvailabilityEnum;
import com.jingcaicompass.match.enums.MatchListSortEnum;
import com.jingcaicompass.match.mapper.MatchMapper;
import com.jingcaicompass.match.mapper.SportteryPoolSnapshotMapper;
import com.jingcaicompass.match.vo.AsianOddsMarketVo;
import com.jingcaicompass.match.vo.MatchDetailVo;
import com.jingcaicompass.match.vo.MatchListItemVo;
import com.jingcaicompass.match.vo.MatchPredictionSummaryVo;
import com.jingcaicompass.match.vo.MatchSummaryVo;
import com.jingcaicompass.match.vo.SportteryMarketVo;
import com.jingcaicompass.odds.client.AsianOddsAggregationProperties;
import com.jingcaicompass.odds.entity.AsianOddsSnapshot;
import com.jingcaicompass.odds.mapper.AsianOddsSnapshotMapper;
import com.jingcaicompass.odds.service.ActiveAsianOddsProvider;
import com.jingcaicompass.odds.service.AsianFreezeSnapshotSelector;
import com.jingcaicompass.odds.service.AsianMarketConsensusAggregator;
import com.jingcaicompass.prediction.entity.Prediction;
import com.jingcaicompass.prediction.enums.PredictionStatusEnum;
import com.jingcaicompass.prediction.mapper.PredictionMapper;
import com.jingcaicompass.prediction.vo.PredictionAsianMarketVo;
import com.jingcaicompass.prediction.vo.PredictionAsianModelVo;
import com.jingcaicompass.system.api.PageResult;
import com.jingcaicompass.system.config.properties.PaginationProperties;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

/** 从 PostgreSQL 比赛与快照装配公开比赛查询结果。 */
@Service
@ConditionalOnBean(DataSource.class)
public class MatchQueryServiceImpl implements MatchQueryService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final MatchMapper matchMapper;
    private final SportteryPoolSnapshotMapper sportterySnapshotMapper;
    private final AsianOddsSnapshotMapper asianOddsSnapshotMapper;
    private final PredictionMapper predictionMapper;
    private final RawDataPayloadMapper rawDataPayloadMapper;
    private final PaginationProperties paginationProperties;
    private final ActiveAsianOddsProvider activeAsianOddsProvider;
    /** 与生成侧同一套跨庄聚合与选行规则，保证首页展示的「主盘」就是模型真正用的那条线。 */
    private final AsianMarketConsensusAggregator consensusAggregator;
    private final AsianFreezeSnapshotSelector freezeSnapshotSelector;

    public MatchQueryServiceImpl(
            MatchMapper matchMapper,
            SportteryPoolSnapshotMapper sportterySnapshotMapper,
            AsianOddsSnapshotMapper asianOddsSnapshotMapper,
            PredictionMapper predictionMapper,
            RawDataPayloadMapper rawDataPayloadMapper,
            PaginationProperties paginationProperties,
            ActiveAsianOddsProvider activeAsianOddsProvider
    ) {
        this(
                matchMapper,
                sportterySnapshotMapper,
                asianOddsSnapshotMapper,
                predictionMapper,
                rawDataPayloadMapper,
                paginationProperties,
                activeAsianOddsProvider,
                AsianOddsAggregationProperties.defaults()
        );
    }

    public MatchQueryServiceImpl(
            MatchMapper matchMapper,
            SportteryPoolSnapshotMapper sportterySnapshotMapper,
            AsianOddsSnapshotMapper asianOddsSnapshotMapper,
            PredictionMapper predictionMapper,
            RawDataPayloadMapper rawDataPayloadMapper,
            PaginationProperties paginationProperties,
            ActiveAsianOddsProvider activeAsianOddsProvider,
            AsianOddsAggregationProperties aggregationProperties
    ) {
        this.matchMapper = matchMapper;
        this.sportterySnapshotMapper = sportterySnapshotMapper;
        this.asianOddsSnapshotMapper = asianOddsSnapshotMapper;
        this.predictionMapper = predictionMapper;
        this.rawDataPayloadMapper = rawDataPayloadMapper;
        this.paginationProperties = paginationProperties;
        this.activeAsianOddsProvider = activeAsianOddsProvider;
        AsianOddsAggregationProperties effective = aggregationProperties == null
                ? AsianOddsAggregationProperties.defaults()
                : aggregationProperties;
        this.consensusAggregator = new AsianMarketConsensusAggregator(effective);
        this.freezeSnapshotSelector = new AsianFreezeSnapshotSelector(effective);
    }

    @Override
    public List<MatchSummaryVo> findDailyMatches(LocalDate lotteryDate) {
        // 1) 从持久化比赛池读取稳定的每日比赛顺序。
        List<MatchEntity> matches = matchMapper.selectPublicDailyMatches(
                lotteryDate == null ? currentLotteryDate() : lotteryDate
        );

        // 2) 批量补齐最新体彩让球和来源，保持旧 GET 响应兼容。
        Map<Long, SportteryPresentation> sporttery = loadLatestSporttery(matches);
        return matches.stream().map(match -> toSummaryVo(match, sporttery.get(match.getId()))).toList();
    }

    @Override
    public PageResult<MatchListItemVo> list(MatchListQueryDto query) {
        // 1) 归一化日期、分页和固定排序，拒绝调用方直接控制 SQL 排序字段。
        MatchListCriteriaDto criteria = normalize(query);
        long total = matchMapper.countPublicPage(criteria);
        List<MatchEntity> matches = total == 0 ? List.of() : matchMapper.selectPublicPage(criteria);

        // 2) 批量装配市场和当前公开预测，避免分页记录逐条查询。
        Map<Long, SportteryPresentation> sporttery = loadLatestSporttery(matches);
        Map<Long, AsianOddsSnapshot> asianMainMarkets = loadLatestAsianMainMarkets(matches);
        Map<Long, List<MatchPredictionSummaryVo>> publishedPredictions = loadCurrentPublishedPredictions(matches);
        List<MatchListItemVo> records = matches.stream()
                .map(match -> toListItemVo(
                        match,
                        sporttery.get(match.getId()),
                        asianMainMarkets.get(match.getId()),
                        publishedPredictions.getOrDefault(match.getId(), List.of())
                ))
                .toList();
        return new PageResult<>(records, criteria.offset() / criteria.pageSize() + 1, criteria.pageSize(), total);
    }

    @Override
    public MatchDetailVo detail(MatchDetailQueryDto query) {
        if (query == null || query.matchId() == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "matchId is required");
        }

        // 1) 查询比赛主体；不存在时返回稳定的 404 业务错误。
        MatchEntity match = matchMapper.selectById(query.matchId());
        if (match == null) {
            throw new BusinessException(ErrorCode.MATCH_NOT_FOUND);
        }

        // 2) 读取当前体彩与全部当前亚盘线，不调用任何外部 Provider。
        SportteryPresentation sporttery = loadLatestSporttery(List.of(match)).get(match.getId());
        List<AsianOddsSnapshot> asianSnapshots = asianOddsSnapshotMapper
                .selectLatestPublicLinesByMatchId(match.getId());
        return toDetailVo(match, sporttery, asianSnapshots);
    }

    @Override
    public LocalDate currentLotteryDate() {
        return LocalDate.now(SHANGHAI);
    }

    private MatchListCriteriaDto normalize(MatchListQueryDto query) {
        int pageNo = query == null || query.pageNo() == null || query.pageNo() < 1 ? 1 : query.pageNo();
        long requestedSize = query == null || query.pageSize() == null || query.pageSize() < 1
                ? 20
                : query.pageSize();
        long pageSize = Math.min(requestedSize, paginationProperties.maxPageSize());
        return new MatchListCriteriaDto(
                query == null || query.lotteryDate() == null ? currentLotteryDate() : query.lotteryDate(),
                query == null ? null : query.leagueId(),
                query == null || query.matchStatuses() == null ? Set.of() : Set.copyOf(query.matchStatuses()),
                query != null && Boolean.TRUE.equals(query.hasPublishedPrediction()),
                query == null || query.sort() == null ? MatchListSortEnum.KICKOFF_ASC : query.sort(),
                pageSize,
                (long) (pageNo - 1) * pageSize
        );
    }

    private Map<Long, SportteryPresentation> loadLatestSporttery(Collection<MatchEntity> matches) {
        List<Long> matchIds = matches.stream()
                .map(MatchEntity::getId)
                .filter(Objects::nonNull)
                .toList();
        if (matchIds.isEmpty()) {
            return Map.of();
        }
        List<SportteryPoolSnapshot> snapshots = sportterySnapshotMapper.selectLatestByMatchIds(matchIds);
        Map<String, String> dataSources = loadSportteryDataSources(snapshots);
        return snapshots.stream().collect(Collectors.toMap(
                SportteryPoolSnapshot::getMatchId,
                snapshot -> new SportteryPresentation(snapshot, dataSources.get(snapshot.getRawPayloadHash()))
        ));
    }

    private Map<String, String> loadSportteryDataSources(List<SportteryPoolSnapshot> snapshots) {
        Set<String> payloadHashes = snapshots.stream()
                .map(SportteryPoolSnapshot::getRawPayloadHash)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (payloadHashes.isEmpty()) {
            return Map.of();
        }
        List<RawDataPayload> payloads = rawDataPayloadMapper.selectList(
                new LambdaQueryWrapper<RawDataPayload>()
                        .eq(RawDataPayload::getDataType, ProviderDataTypeEnum.SPORTTERY_POOL)
                        .in(RawDataPayload::getPayloadHash, payloadHashes)
                        .orderByDesc(RawDataPayload::getRequestedAt)
        );
        Map<String, String> result = new HashMap<>();
        for (RawDataPayload payload : payloads) {
            result.putIfAbsent(payload.getPayloadHash(), payload.getProviderCode());
        }
        return result;
    }

    /**
     * 装配每场的「当前亚盘主盘」。
     *
     * <p><b>不能再取「最新一条快照」</b>：落库是 (让球线 × 大小球线) 的交叉积，同一次同步里
     * 最后写入的那一行只是表里的最大 {@code id}，与哪条线最平衡无关。此前用
     * {@code selectLatestCompleteLinesByMatchIds + DISTINCT ON (match_id) ORDER BY captured_at DESC}
     * 取单行，首页因此长期显示错误的档位——2026-10-25 实测：福图纳锡塔德 vs SBV 精英
     * 真实主盘是 0（主 1.87 / 客 1.80），首页显示 +0.5（主 1.48 / 客 2.41）；国际米兰 vs
     * 佛罗伦萨真实主盘 -1.5，首页显示 -2.5。两场都恰好是 betvictor 在交叉积里最后写入的那一行。</p>
     *
     * <p>这里复用生成侧的 {@link AsianMarketConsensusAggregator} 与
     * {@link AsianFreezeSnapshotSelector}，而不是另写一套「挑最平衡的线」：首页展示的主盘必须是
     * 模型实际决策并结算的那一行，否则用户看到的盘口与预测绑定的盘口会是两条线。</p>
     *
     * @return 每场的主盘快照行；共识线上没有任何机构报出完整水位时该场缺席（宁可显示「无亚盘」，
     *         也不显示一条不会用于决策的线）
     */
    private Map<Long, AsianOddsSnapshot> loadLatestAsianMainMarkets(Collection<MatchEntity> matches) {
        List<Long> matchIds = matchIds(matches);
        if (matchIds.isEmpty()) {
            return Map.of();
        }
        List<AsianOddsSnapshot> rows = asianOddsSnapshotMapper.selectLatestCrossProductLinesByMatchIds(
                matchIds, activeAsianOddsProvider.providerCode());
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<AsianOddsSnapshot>> byMatch = rows.stream()
                .filter(row -> row.getMatchId() != null)
                .collect(Collectors.groupingBy(
                        AsianOddsSnapshot::getMatchId, java.util.LinkedHashMap::new, Collectors.toList()));
        Map<Long, AsianOddsSnapshot> mainMarkets = new HashMap<>();
        for (Map.Entry<Long, List<AsianOddsSnapshot>> entry : byMatch.entrySet()) {
            selectMainMarket(entry.getValue())
                    .ifPresent(row -> mainMarkets.put(entry.getKey(), row));
        }
        return mainMarkets;
    }

    /** 跨庄聚合定共识线，再落到真实报出该线的某一行上。 */
    private java.util.Optional<AsianOddsSnapshot> selectMainMarket(List<AsianOddsSnapshot> rows) {
        List<AsianMarketConsensusAggregator.Quote> handicapQuotes = rows.stream()
                .filter(row -> row.getHandicapLine() != null)
                .map(row -> new AsianMarketConsensusAggregator.Quote(
                        row.getProviderCode(),
                        row.getBookmakerCode(),
                        row.getHandicapLine(),
                        row.getHomeOdds(),
                        row.getAwayOdds(),
                        row.getCapturedAt(),
                        row.getId()))
                .toList();
        AsianMarketConsensusAggregator.MarketAggregate handicap = consensusAggregator.aggregate(handicapQuotes);
        AsianMarketConsensusAggregator.LineConsensus handicapBest = handicap.best().orElse(null);
        if (handicapBest == null) {
            return java.util.Optional.empty();
        }
        List<AsianMarketConsensusAggregator.Quote> totalsQuotes = rows.stream()
                .filter(row -> row.getTotalLine() != null)
                .map(row -> new AsianMarketConsensusAggregator.Quote(
                        row.getProviderCode(),
                        row.getBookmakerCode(),
                        row.getTotalLine(),
                        row.getOverOdds(),
                        row.getUnderOdds(),
                        row.getCapturedAt(),
                        row.getId()))
                .toList();
        AsianMarketConsensusAggregator.MarketAggregate totals = consensusAggregator.aggregate(totalsQuotes);
        return freezeSnapshotSelector.select(rows, handicapBest, totals.best());
    }

    private Map<Long, List<MatchPredictionSummaryVo>> loadCurrentPublishedPredictions(Collection<MatchEntity> matches) {
        List<Long> matchIds = matchIds(matches);
        if (matchIds.isEmpty()) {
            return Map.of();
        }
        List<Prediction> predictions = predictionMapper.selectCurrentPublishedByMatchIds(matchIds).stream()
                .filter(prediction -> prediction.getPredictionStatus() == PredictionStatusEnum.PUBLISHED
                        || prediction.getPredictionStatus() == PredictionStatusEnum.LOCKED)
                .toList();
        Map<Long, AsianOddsSnapshot> asianSnapshots = loadPredictionAsianMarkets(predictions);
        return predictions.stream()
                .collect(Collectors.groupingBy(
                        Prediction::getMatchId,
                        java.util.LinkedHashMap::new,
                        Collectors.mapping(
                                prediction -> toPredictionSummaryVo(
                                        prediction,
                                        prediction.getAsianOddsSnapshotId() == null
                                                ? null
                                                : asianSnapshots.get(prediction.getAsianOddsSnapshotId())
                                ),
                                Collectors.toList()
                        )
                ));
    }

    private Map<Long, AsianOddsSnapshot> loadPredictionAsianMarkets(List<Prediction> predictions) {
        List<Long> snapshotIds = predictions.stream()
                .map(Prediction::getAsianOddsSnapshotId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (snapshotIds.isEmpty()) {
            return Map.of();
        }
        return asianOddsSnapshotMapper.selectBatchIds(snapshotIds).stream()
                .collect(Collectors.toMap(AsianOddsSnapshot::getId, snapshot -> snapshot));
    }

    private List<Long> matchIds(Collection<MatchEntity> matches) {
        return matches.stream()
                .map(MatchEntity::getId)
                .filter(Objects::nonNull)
                .toList();
    }

    private MatchSummaryVo toSummaryVo(MatchEntity match, SportteryPresentation sporttery) {
        SportteryPoolSnapshot snapshot = sporttery == null ? null : sporttery.snapshot();
        return new MatchSummaryVo(
                String.valueOf(match.getId()),
                match.getLotteryDate(),
                match.getLotteryMatchNo(),
                match.getLeagueName(),
                match.getHomeTeamName(),
                match.getAwayTeamName(),
                toShanghaiTime(match.getKickoffTime()),
                snapshot == null ? null : snapshot.getOfficialHandicap(),
                match.getMatchStatus(),
                sporttery == null || sporttery.dataSource() == null ? "PERSISTED_SPORTTERY" : sporttery.dataSource()
        );
    }

    private MatchListItemVo toListItemVo(
            MatchEntity match,
            SportteryPresentation sporttery,
            AsianOddsSnapshot asianMainMarket,
            List<MatchPredictionSummaryVo> publishedPredictions
    ) {
        SportteryPoolSnapshot snapshot = sporttery == null ? null : sporttery.snapshot();
        return new MatchListItemVo(
                match.getId(),
                match.getLotteryDate(),
                match.getLotteryMatchNo(),
                match.getLeagueId(),
                match.getLeagueName(),
                match.getHomeTeamName(),
                match.getAwayTeamName(),
                toShanghaiTime(match.getKickoffTime()),
                match.getMatchStatus(),
                snapshot == null ? null : snapshot.getOfficialHandicap(),
                snapshot == null ? MatchDataAvailabilityEnum.NO_SPORTTERY_SNAPSHOT : MatchDataAvailabilityEnum.AVAILABLE,
                sporttery == null || sporttery.dataSource() == null ? null : sporttery.dataSource(),
                snapshot == null ? null : toShanghaiTime(snapshot.getCapturedAt()),
                snapshot == null ? null : toShanghaiTime(snapshot.getProviderUpdatedAt()),
                toSportteryMarketVo(sporttery),
                asianMainMarket == null ? null : toAsianOddsMarketVo(asianMainMarket),
                List.copyOf(publishedPredictions)
        );
    }

    private MatchDetailVo toDetailVo(
            MatchEntity match,
            SportteryPresentation sporttery,
            List<AsianOddsSnapshot> asianSnapshots
    ) {
        SportteryMarketVo sportteryMarket = toSportteryMarketVo(sporttery);
        List<AsianOddsMarketVo> asianMarkets = asianSnapshots.stream().map(this::toAsianOddsMarketVo).toList();
        return new MatchDetailVo(
                match.getId(),
                match.getLotteryDate(),
                match.getLotteryMatchNo(),
                match.getLeagueId(),
                match.getLeagueName(),
                match.getHomeTeamName(),
                match.getAwayTeamName(),
                toShanghaiTime(match.getKickoffTime()),
                match.getMatchStatus(),
                match.getHomeScore(),
                match.getAwayScore(),
                sportteryMarket,
                asianMarkets.isEmpty() ? MatchDataAvailabilityEnum.NO_ASIAN_ODDS_SNAPSHOT : MatchDataAvailabilityEnum.AVAILABLE,
                asianMarkets
        );
    }

    private SportteryMarketVo toSportteryMarketVo(SportteryPresentation sporttery) {
        SportteryPoolSnapshot snapshot = sporttery == null ? null : sporttery.snapshot();
        return snapshot == null
                ? new SportteryMarketVo(MatchDataAvailabilityEnum.NO_SPORTTERY_SNAPSHOT, null, null, null,
                null, null, null, null, null, null, null, null)
                : new SportteryMarketVo(
                MatchDataAvailabilityEnum.AVAILABLE,
                sporttery.dataSource() == null ? "PERSISTED_SPORTTERY" : sporttery.dataSource(),
                toShanghaiTime(snapshot.getCapturedAt()),
                toShanghaiTime(snapshot.getProviderUpdatedAt()),
                snapshot.getOfficialHandicap(),
                snapshot.getHadHomeSp(),
                snapshot.getHadDrawSp(),
                snapshot.getHadAwaySp(),
                snapshot.getHhadHomeSp(),
                snapshot.getHhadDrawSp(),
                snapshot.getHhadAwaySp(),
                snapshot.getSellStatus()
        );
    }

    private AsianOddsMarketVo toAsianOddsMarketVo(AsianOddsSnapshot snapshot) {
        return new AsianOddsMarketVo(
                snapshot.getProviderCode(),
                snapshot.getBookmakerCode(),
                snapshot.getHandicapLine(),
                snapshot.getHomeOdds(),
                snapshot.getAwayOdds(),
                snapshot.getTotalLine(),
                snapshot.getOverOdds(),
                snapshot.getUnderOdds(),
                snapshot.getSnapshotType(),
                toShanghaiTime(snapshot.getCapturedAt()),
                toShanghaiTime(snapshot.getProviderUpdatedAt())
        );
    }

    private MatchPredictionSummaryVo toPredictionSummaryVo(Prediction prediction, AsianOddsSnapshot asianMarket) {
        return new MatchPredictionSummaryVo(
                prediction.getModelVersion(),
                prediction.getPredictionStatus(),
                prediction.getHomeWinProb(),
                prediction.getDrawProb(),
                prediction.getAwayWinProb(),
                prediction.getHandicapPick(),
                prediction.getExpectedTotalGoals(),
                prediction.getAsianHandicapPick(),
                prediction.getTotalGoalsPick(),
                asianMarket == null ? null : toPredictionAsianMarketVo(asianMarket),
                prediction.getPredictionType(),
                prediction.getAsianHandicapConfidenceLevel(),
                prediction.getTotalGoalsConfidenceLevel(),
                prediction.getConfidenceLevel(),
                PredictionAsianModelVo.from(prediction)
        );
    }

    private PredictionAsianMarketVo toPredictionAsianMarketVo(AsianOddsSnapshot snapshot) {
        return new PredictionAsianMarketVo(
                snapshot.getId(),
                snapshot.getProviderCode(),
                snapshot.getBookmakerCode(),
                snapshot.getHandicapLine(),
                snapshot.getHomeOdds(),
                snapshot.getAwayOdds(),
                snapshot.getTotalLine(),
                snapshot.getOverOdds(),
                snapshot.getUnderOdds(),
                snapshot.getSnapshotType(),
                snapshot.getCapturedAt(),
                snapshot.getProviderUpdatedAt()
        );
    }

    private OffsetDateTime toShanghaiTime(Instant value) {
        return value == null ? null : value.atZone(SHANGHAI).toOffsetDateTime();
    }

    private record SportteryPresentation(SportteryPoolSnapshot snapshot, String dataSource) {
    }
}
