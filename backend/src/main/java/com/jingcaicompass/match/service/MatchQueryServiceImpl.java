package com.jingcaicompass.match.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jingcaicompass.data.entity.RawDataPayload;
import com.jingcaicompass.data.enums.ProviderDataTypeEnum;
import com.jingcaicompass.data.mapper.RawDataPayloadMapper;
import com.jingcaicompass.match.dto.MatchDetailQueryDto;
import com.jingcaicompass.match.dto.MatchListCriteriaDto;
import com.jingcaicompass.match.dto.MatchListQueryDto;
import com.jingcaicompass.match.entity.MatchEntity;
import com.jingcaicompass.match.entity.MatchSourceMapping;
import com.jingcaicompass.match.entity.SportteryPoolSnapshot;
import com.jingcaicompass.match.enums.MappingStatusEnum;
import com.jingcaicompass.match.enums.MatchDataAvailabilityEnum;
import com.jingcaicompass.match.enums.MatchListSortEnum;
import com.jingcaicompass.match.mapper.MatchMapper;
import com.jingcaicompass.match.mapper.MatchSourceMappingMapper;
import com.jingcaicompass.match.mapper.SportteryPoolSnapshotMapper;
import com.jingcaicompass.match.vo.AsianOddsMarketVo;
import com.jingcaicompass.match.vo.MatchDetailVo;
import com.jingcaicompass.match.vo.MatchListItemVo;
import com.jingcaicompass.match.vo.MatchSourceMappingVo;
import com.jingcaicompass.match.vo.MatchSummaryVo;
import com.jingcaicompass.match.vo.SportteryMarketVo;
import com.jingcaicompass.odds.entity.AsianOddsSnapshot;
import com.jingcaicompass.odds.mapper.AsianOddsSnapshotMapper;
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

/** 从 PostgreSQL 比赛、快照和映射表装配公开比赛查询结果。 */
@Service
@ConditionalOnBean(DataSource.class)
public class MatchQueryServiceImpl implements MatchQueryService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final MatchMapper matchMapper;
    private final SportteryPoolSnapshotMapper sportterySnapshotMapper;
    private final AsianOddsSnapshotMapper asianOddsSnapshotMapper;
    private final MatchSourceMappingMapper matchSourceMappingMapper;
    private final RawDataPayloadMapper rawDataPayloadMapper;
    private final PaginationProperties paginationProperties;

    public MatchQueryServiceImpl(
            MatchMapper matchMapper,
            SportteryPoolSnapshotMapper sportterySnapshotMapper,
            AsianOddsSnapshotMapper asianOddsSnapshotMapper,
            MatchSourceMappingMapper matchSourceMappingMapper,
            RawDataPayloadMapper rawDataPayloadMapper,
            PaginationProperties paginationProperties
    ) {
        this.matchMapper = matchMapper;
        this.sportterySnapshotMapper = sportterySnapshotMapper;
        this.asianOddsSnapshotMapper = asianOddsSnapshotMapper;
        this.matchSourceMappingMapper = matchSourceMappingMapper;
        this.rawDataPayloadMapper = rawDataPayloadMapper;
        this.paginationProperties = paginationProperties;
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

        // 2) 批量装配每场比赛的最新体彩快照，避免分页记录逐条查询。
        Map<Long, SportteryPresentation> sporttery = loadLatestSporttery(matches);
        List<MatchListItemVo> records = matches.stream()
                .map(match -> toListItemVo(match, sporttery.get(match.getId())))
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

        // 2) 读取当前体彩、全部当前亚盘线与映射解释，不调用任何外部 Provider。
        SportteryPresentation sporttery = loadLatestSporttery(List.of(match)).get(match.getId());
        List<AsianOddsSnapshot> asianSnapshots = asianOddsSnapshotMapper
                .selectLatestPublicLinesByMatchId(match.getId());
        List<MatchSourceMapping> mappings = matchSourceMappingMapper.selectList(
                new LambdaQueryWrapper<MatchSourceMapping>()
                        .eq(MatchSourceMapping::getMatchId, match.getId())
                        .orderByAsc(MatchSourceMapping::getProviderCode)
                        .orderByAsc(MatchSourceMapping::getId)
        );
        return toDetailVo(match, sporttery, asianSnapshots, mappings);
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

    private MatchListItemVo toListItemVo(MatchEntity match, SportteryPresentation sporttery) {
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
                snapshot == null ? null : toShanghaiTime(snapshot.getProviderUpdatedAt())
        );
    }

    private MatchDetailVo toDetailVo(
            MatchEntity match,
            SportteryPresentation sporttery,
            List<AsianOddsSnapshot> asianSnapshots,
            List<MatchSourceMapping> mappings
    ) {
        SportteryPoolSnapshot snapshot = sporttery == null ? null : sporttery.snapshot();
        SportteryMarketVo sportteryMarket = snapshot == null
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
        List<AsianOddsMarketVo> asianMarkets = asianSnapshots.stream().map(this::toAsianOddsMarketVo).toList();
        List<MatchSourceMappingVo> sourceMappings = mappings.stream().map(this::toSourceMappingVo).toList();
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
                asianMarkets,
                mappingAvailability(mappings),
                sourceMappings
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

    private MatchSourceMappingVo toSourceMappingVo(MatchSourceMapping mapping) {
        return new MatchSourceMappingVo(
                mapping.getProviderCode(),
                mapping.getExternalMatchId(),
                mapping.getMappingStatus(),
                mapping.getMappingConfidence(),
                mapping.getMappingMethod(),
                mapping.getMappingExplanation(),
                toShanghaiTime(mapping.getUpdatedAt())
        );
    }

    private MatchDataAvailabilityEnum mappingAvailability(List<MatchSourceMapping> mappings) {
        if (mappings.isEmpty()) {
            return MatchDataAvailabilityEnum.NO_SOURCE_MAPPING;
        }
        boolean hasConfirmed = mappings.stream().map(MatchSourceMapping::getMappingStatus)
                .anyMatch(status -> status == MappingStatusEnum.AUTO_CONFIRMED
                        || status == MappingStatusEnum.MANUAL_CONFIRMED);
        return hasConfirmed ? MatchDataAvailabilityEnum.AVAILABLE : MatchDataAvailabilityEnum.MAPPING_UNCONFIRMED;
    }

    private OffsetDateTime toShanghaiTime(Instant value) {
        return value == null ? null : value.atZone(SHANGHAI).toOffsetDateTime();
    }

    private record SportteryPresentation(SportteryPoolSnapshot snapshot, String dataSource) {
    }
}
