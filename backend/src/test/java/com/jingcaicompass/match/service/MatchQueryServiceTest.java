package com.jingcaicompass.match.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jingcaicompass.data.mapper.RawDataPayloadMapper;
import com.jingcaicompass.match.dto.MatchDetailQueryDto;
import com.jingcaicompass.match.dto.MatchListQueryDto;
import com.jingcaicompass.match.entity.MatchEntity;
import com.jingcaicompass.match.entity.MatchSourceMapping;
import com.jingcaicompass.match.entity.SportteryPoolSnapshot;
import com.jingcaicompass.match.enums.MappingStatusEnum;
import com.jingcaicompass.match.enums.MatchDataAvailabilityEnum;
import com.jingcaicompass.match.enums.MatchListSortEnum;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import com.jingcaicompass.match.mapper.MatchMapper;
import com.jingcaicompass.match.mapper.MatchSourceMappingMapper;
import com.jingcaicompass.match.mapper.SportteryPoolSnapshotMapper;
import com.jingcaicompass.odds.entity.AsianOddsSnapshot;
import com.jingcaicompass.odds.mapper.AsianOddsSnapshotMapper;
import com.jingcaicompass.prediction.entity.Prediction;
import com.jingcaicompass.prediction.enums.ConfidenceLevelEnum;
import com.jingcaicompass.prediction.enums.HandicapPickEnum;
import com.jingcaicompass.prediction.enums.AsianHandicapPickEnum;
import com.jingcaicompass.prediction.enums.PredictionStatusEnum;
import com.jingcaicompass.prediction.enums.TotalGoalsPickEnum;
import com.jingcaicompass.prediction.mapper.PredictionMapper;
import com.jingcaicompass.system.config.properties.PaginationProperties;
import com.jingcaicompass.system.exception.BusinessException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MatchQueryServiceTest {

    private MatchMapper matchMapper;
    private SportteryPoolSnapshotMapper sportterySnapshotMapper;
    private AsianOddsSnapshotMapper asianOddsSnapshotMapper;
    private PredictionMapper predictionMapper;
    private MatchSourceMappingMapper matchSourceMappingMapper;
    private RawDataPayloadMapper rawDataPayloadMapper;
    private MatchQueryService matchQueryService;

    @BeforeEach
    void setUp() {
        matchMapper = mock(MatchMapper.class);
        sportterySnapshotMapper = mock(SportteryPoolSnapshotMapper.class);
        asianOddsSnapshotMapper = mock(AsianOddsSnapshotMapper.class);
        predictionMapper = mock(PredictionMapper.class);
        matchSourceMappingMapper = mock(MatchSourceMappingMapper.class);
        rawDataPayloadMapper = mock(RawDataPayloadMapper.class);
        matchQueryService = new MatchQueryServiceImpl(
                matchMapper,
                sportterySnapshotMapper,
                asianOddsSnapshotMapper,
                predictionMapper,
                matchSourceMappingMapper,
                rawDataPayloadMapper,
                new PaginationProperties(50)
        );
    }

    @Test
    void readsCompatibilityDailyListFromMatchesAndNeverCallsProvider() {
        MatchEntity match = match(101L, "周三201");
        SportteryPoolSnapshot snapshot = sportterySnapshot(101L, "2026-07-22T09:00:00Z", "-1");
        when(matchMapper.selectPublicDailyMatches(match.getLotteryDate())).thenReturn(List.of(match));
        when(sportterySnapshotMapper.selectLatestByMatchIds(anyCollection())).thenReturn(List.of(snapshot));
        when(rawDataPayloadMapper.selectList(any())).thenReturn(List.of());

        var matches = matchQueryService.findDailyMatches(match.getLotteryDate());

        assertThat(matches).singleElement().satisfies(item -> {
            assertThat(item.matchId()).isEqualTo("101");
            assertThat(item.officialHandicap()).isEqualByComparingTo("-1");
            assertThat(item.dataSource()).isEqualTo("PERSISTED_SPORTTERY");
        });
        verify(matchMapper).selectPublicDailyMatches(match.getLotteryDate());
        verify(matchMapper, never()).selectPublicPage(any());
    }

    @Test
    void normalizesPaginationCapsSizeAndMapsLatestSportterySnapshot() {
        MatchEntity match = match(102L, "周三202");
        SportteryPoolSnapshot snapshot = sportterySnapshot(102L, "2026-07-22T10:00:00Z", "0.5");
        when(matchMapper.countPublicPage(any())).thenReturn(1L);
        when(matchMapper.selectPublicPage(any())).thenReturn(List.of(match));
        when(sportterySnapshotMapper.selectLatestByMatchIds(anyCollection())).thenReturn(List.of(snapshot));
        when(rawDataPayloadMapper.selectList(any())).thenReturn(List.of());
        when(asianOddsSnapshotMapper.selectLatestCompleteConfirmedLinesByMatchIds(anyCollection())).thenReturn(List.of());
        when(predictionMapper.selectCurrentPublishedByMatchIds(anyCollection())).thenReturn(List.of());

        var page = matchQueryService.list(new MatchListQueryDto(
                match.getLotteryDate(), null, null, MatchListSortEnum.KICKOFF_DESC, 0, 500
        ));

        assertThat(page.pageNo()).isEqualTo(1);
        assertThat(page.pageSize()).isEqualTo(50);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.records()).singleElement().satisfies(item -> {
            assertThat(item.matchId()).isEqualTo(102L);
            assertThat(item.officialHandicap()).isEqualByComparingTo("0.5");
            assertThat(item.sportteryAvailability()).isEqualTo(MatchDataAvailabilityEnum.AVAILABLE);
        });
    }

    @Test
    void batchesCurrentMarketsAndPublishedPredictionsForThePublicList() {
        MatchEntity match = match(105L, "周三205");
        SportteryPoolSnapshot sporttery = sportterySnapshot(105L, "2026-07-22T10:00:00Z", "-1");
        sporttery.setHadHomeSp(BigDecimal.valueOf(2.36));
        sporttery.setHadDrawSp(BigDecimal.valueOf(2.9));
        sporttery.setHadAwaySp(BigDecimal.valueOf(2.77));
        sporttery.setHhadHomeSp(BigDecimal.valueOf(5.65));
        sporttery.setHhadDrawSp(BigDecimal.valueOf(3.8));
        sporttery.setHhadAwaySp(BigDecimal.valueOf(1.45));
        AsianOddsSnapshot asianMain = asianOddsSnapshot("BOOK_A", "0", "2026-07-22T10:01:00Z");
        asianMain.setId(9001L);
        asianMain.setMatchId(105L);
        asianMain.setTotalLine(BigDecimal.valueOf(2.5));
        asianMain.setOverOdds(BigDecimal.valueOf(1.82));
        asianMain.setUnderOdds(BigDecimal.valueOf(2.02));
        Prediction prediction = publishedPrediction(105L, "baseline-v1");
        prediction.setAsianOddsSnapshotId(9001L);
        prediction.setAsianHandicapPick(AsianHandicapPickEnum.HOME_COVER);
        prediction.setTotalGoalsPick(TotalGoalsPickEnum.UNDER);
        Prediction lockedPrediction = publishedPrediction(105L, "baseline-v2");
        lockedPrediction.setPredictionStatus(PredictionStatusEnum.LOCKED);
        Prediction draftPrediction = publishedPrediction(105L, "draft-v1");
        draftPrediction.setPredictionStatus(PredictionStatusEnum.DRAFT);
        when(matchMapper.countPublicPage(any())).thenReturn(1L);
        when(matchMapper.selectPublicPage(any())).thenReturn(List.of(match));
        when(sportterySnapshotMapper.selectLatestByMatchIds(anyCollection())).thenReturn(List.of(sporttery));
        when(rawDataPayloadMapper.selectList(any())).thenReturn(List.of());
        when(asianOddsSnapshotMapper.selectLatestCompleteConfirmedLinesByMatchIds(anyCollection()))
                .thenReturn(List.of(asianMain));
        when(asianOddsSnapshotMapper.selectBatchIds(List.of(9001L))).thenReturn(List.of(asianMain));
        when(predictionMapper.selectCurrentPublishedByMatchIds(anyCollection()))
                .thenReturn(List.of(prediction, lockedPrediction, draftPrediction));

        var page = matchQueryService.list(new MatchListQueryDto(
                match.getLotteryDate(), null, null, MatchListSortEnum.KICKOFF_ASC, 1, 20
        ));

        assertThat(page.records()).singleElement().satisfies(item -> {
            assertThat(item.sportteryMarket().hadHomeSp()).isEqualByComparingTo("2.36");
            assertThat(item.sportteryMarket().hhadAwaySp()).isEqualByComparingTo("1.45");
            assertThat(item.asianMainMarket()).satisfies(market -> {
                assertThat(market.bookmakerCode()).isEqualTo("BOOK_A");
                assertThat(market.handicapLine()).isEqualByComparingTo("0");
                assertThat(market.totalLine()).isEqualByComparingTo("2.5");
            });
            assertThat(item.publishedPredictions()).extracting(summary -> summary.modelVersion())
                    .containsExactly("baseline-v1", "baseline-v2");
            assertThat(item.publishedPredictions().getFirst()).satisfies(summary -> {
                assertThat(summary.predictionStatus()).isEqualTo(PredictionStatusEnum.PUBLISHED);
                assertThat(summary.homeWinProb()).isEqualByComparingTo("0.37");
                assertThat(summary.asianHandicapPick()).isEqualTo(AsianHandicapPickEnum.HOME_COVER);
                assertThat(summary.totalGoalsPick()).isEqualTo(TotalGoalsPickEnum.UNDER);
                assertThat(summary.asianMarket().bookmakerCode()).isEqualTo("BOOK_A");
            });
        });
        verify(asianOddsSnapshotMapper).selectLatestCompleteConfirmedLinesByMatchIds(anyCollection());
        verify(predictionMapper).selectCurrentPublishedByMatchIds(anyCollection());
    }

    @Test
    void exposesLatestAsianLinePerProviderBookmakerAndHandicapWithMappingStatus() {
        MatchEntity match = match(103L, "周三203");
        AsianOddsSnapshot first = asianOddsSnapshot("BOOK_A", "-0.5", "2026-07-22T10:00:00Z");
        AsianOddsSnapshot secondLine = asianOddsSnapshot("BOOK_A", "-0.25", "2026-07-22T10:01:00Z");
        MatchSourceMapping mapping = new MatchSourceMapping();
        mapping.setProviderCode("ASIAN_TEST");
        mapping.setExternalMatchId("provider-match-103");
        mapping.setMappingStatus(MappingStatusEnum.MANUAL_CONFIRMED);
        mapping.setUpdatedAt(Instant.parse("2026-07-22T10:02:00Z"));
        when(matchMapper.selectById(103L)).thenReturn(match);
        when(sportterySnapshotMapper.selectLatestByMatchIds(anyCollection())).thenReturn(List.of());
        when(asianOddsSnapshotMapper.selectLatestPublicLinesByMatchId(103L)).thenReturn(List.of(first, secondLine));
        when(matchSourceMappingMapper.selectList(any())).thenReturn(List.of(mapping));

        var detail = matchQueryService.detail(new MatchDetailQueryDto(103L));

        assertThat(detail.sportteryMarket().availability()).isEqualTo(MatchDataAvailabilityEnum.NO_SPORTTERY_SNAPSHOT);
        assertThat(detail.asianOddsAvailability()).isEqualTo(MatchDataAvailabilityEnum.AVAILABLE);
        assertThat(detail.asianOddsMarkets()).extracting(item -> item.handicapLine())
                .containsExactly(BigDecimal.valueOf(-0.5), BigDecimal.valueOf(-0.25));
        assertThat(detail.mappingAvailability()).isEqualTo(MatchDataAvailabilityEnum.AVAILABLE);
        assertThat(detail.sourceMappings()).singleElement()
                .satisfies(item -> assertThat(item.mappingStatus()).isEqualTo(MappingStatusEnum.MANUAL_CONFIRMED));
    }

    @Test
    void distinguishesMissingAsianOddsAndMissingMappings() {
        MatchEntity match = match(104L, "周三204");
        when(matchMapper.selectById(104L)).thenReturn(match);
        when(sportterySnapshotMapper.selectLatestByMatchIds(anyCollection())).thenReturn(List.of());
        when(asianOddsSnapshotMapper.selectLatestPublicLinesByMatchId(104L)).thenReturn(List.of());
        when(matchSourceMappingMapper.selectList(any())).thenReturn(List.of());

        var detail = matchQueryService.detail(new MatchDetailQueryDto(104L));

        assertThat(detail.asianOddsAvailability()).isEqualTo(MatchDataAvailabilityEnum.NO_ASIAN_ODDS_SNAPSHOT);
        assertThat(detail.mappingAvailability()).isEqualTo(MatchDataAvailabilityEnum.NO_SOURCE_MAPPING);
    }

    @Test
    void rejectsUnknownMatchWithStableNotFoundCode() {
        when(matchMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> matchQueryService.detail(new MatchDetailQueryDto(999L)))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).errorCode().code())
                        .isEqualTo("MATCH_NOT_FOUND"));
    }

    private MatchEntity match(long id, String lotteryMatchNo) {
        MatchEntity result = new MatchEntity();
        result.setId(id);
        result.setLotteryDate(LocalDate.of(2026, 7, 22));
        result.setLotteryMatchNo(lotteryMatchNo);
        result.setLeagueId(8L);
        result.setLeagueName("测试联赛");
        result.setHomeTeamName("主队");
        result.setAwayTeamName("客队");
        result.setKickoffTime(Instant.parse("2026-07-22T12:00:00Z"));
        result.setMatchStatus(MatchStatusEnum.SCHEDULED);
        return result;
    }

    private SportteryPoolSnapshot sportterySnapshot(long matchId, String capturedAt, String handicap) {
        SportteryPoolSnapshot result = new SportteryPoolSnapshot();
        result.setMatchId(matchId);
        result.setOfficialHandicap(new BigDecimal(handicap));
        result.setCapturedAt(Instant.parse(capturedAt));
        result.setRawPayloadHash("a".repeat(64));
        return result;
    }

    private AsianOddsSnapshot asianOddsSnapshot(String bookmaker, String handicap, String capturedAt) {
        AsianOddsSnapshot result = new AsianOddsSnapshot();
        result.setProviderCode("ASIAN_TEST");
        result.setBookmakerCode(bookmaker);
        result.setHandicapLine(new BigDecimal(handicap));
        result.setHomeOdds(BigDecimal.valueOf(1.8));
        result.setAwayOdds(BigDecimal.valueOf(2.1));
        result.setCapturedAt(Instant.parse(capturedAt));
        return result;
    }

    private Prediction publishedPrediction(long matchId, String modelVersion) {
        Prediction result = new Prediction();
        result.setMatchId(matchId);
        result.setModelVersion(modelVersion);
        result.setPredictionStatus(PredictionStatusEnum.PUBLISHED);
        result.setHomeWinProb(BigDecimal.valueOf(0.37));
        result.setDrawProb(BigDecimal.valueOf(0.31));
        result.setAwayWinProb(BigDecimal.valueOf(0.32));
        result.setHandicapPick(HandicapPickEnum.AWAY_WIN);
        result.setExpectedTotalGoals(BigDecimal.valueOf(2.5));
        result.setConfidenceLevel(ConfidenceLevelEnum.MEDIUM);
        return result;
    }
}
