package com.jingcaicompass.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jingcaicompass.admin.vo.AdminOddsLadderVo;
import com.jingcaicompass.odds.client.AsianOddsAggregationProperties;
import com.jingcaicompass.odds.entity.AsianOddsSnapshot;
import com.jingcaicompass.odds.mapper.AsianOddsSnapshotMapper;
import com.jingcaicompass.odds.service.ActiveAsianOddsProvider;
import com.jingcaicompass.prediction.entity.Prediction;
import com.jingcaicompass.prediction.enums.PredictionTypeEnum;
import com.jingcaicompass.prediction.mapper.PredictionMapper;
import com.jingcaicompass.system.exception.BusinessException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdminOddsLadderQueryServiceTest {

    private static final Instant CAPTURED_AT = Instant.parse("2026-10-21T06:00:00Z");

    private PredictionMapper predictionMapper;
    private AsianOddsSnapshotMapper snapshotMapper;
    private AdminOddsLadderQueryService service;

    @BeforeEach
    void setUp() {
        predictionMapper = mock(PredictionMapper.class);
        snapshotMapper = mock(AsianOddsSnapshotMapper.class);
        service = new AdminOddsLadderQueryService(
                predictionMapper,
                snapshotMapper,
                AsianOddsAggregationProperties.defaults(),
                new ActiveAsianOddsProvider("NOVA_API")
        );
    }

    @Test
    void rejectsUnknownPrediction() {
        when(predictionMapper.selectById(7L)).thenReturn(null);

        assertThatThrownBy(() -> service.query(7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("prediction not found");
    }

    /** 竞彩预测没有亚盘冻结行，给空阶梯比给一个「看起来一样」的表更危险。 */
    @Test
    void rejectsSportteryPrediction() {
        Prediction sporttery = new Prediction();
        sporttery.setId(8L);
        sporttery.setMatchId(80L);
        sporttery.setPredictionType(PredictionTypeEnum.SPORTTERY);
        when(predictionMapper.selectById(8L)).thenReturn(sporttery);

        assertThatThrownBy(() -> service.query(8L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("only available for ASIAN");
    }

    /**
     * 一条快照同时带让球与大小球字段，两条查询会返回同一行；
     * 不去重会让表格里每条报价出现两次，家数看起来翻倍。
     */
    @Test
    void aggregatesBothMarketsAndDeduplicatesRowsBySnapshotId() {
        Prediction prediction = asianPrediction(11L, 101L, 900L);
        when(predictionMapper.selectById(11L)).thenReturn(prediction);
        AsianOddsSnapshot shared = snapshot(900L, "pinnacle", "0.5", "2.5",
                "1.95", "1.95", "1.90", "1.95");
        AsianOddsSnapshot handicapOnly = snapshot(901L, "bet365", "0.5", null,
                "1.98", "1.92", null, null);
        AsianOddsSnapshot totalsOnly = snapshot(902L, "williamhill", null, "2.5",
                null, null, "1.85", "2.00");
        when(snapshotMapper.selectLatestHandicapLinesByMatchIds(List.of(101L), "NOVA_API"))
                .thenReturn(List.of(shared, handicapOnly));
        when(snapshotMapper.selectLatestTotalsLinesByMatchIds(List.of(101L), "NOVA_API"))
                .thenReturn(List.of(shared, totalsOnly));

        AdminOddsLadderVo ladder = service.query(11L);

        assertThat(ladder.matchId()).isEqualTo(101L);
        assertThat(ladder.frozenSnapshotId()).isEqualTo(900L);
        // 让球：两家报到 0.5，主盘即该线
        assertThat(ladder.handicap().bestLine()).isEqualByComparingTo("0.5");
        assertThat(ladder.handicap().bestSampleCount()).isEqualTo(2);
        assertThat(ladder.handicap().bestFirstProbability()).isNotNull();
        // 大小球：两家报到 2.5
        assertThat(ladder.totals().bestLine()).isEqualByComparingTo("2.5");
        assertThat(ladder.totals().bestSampleCount()).isEqualTo(2);
        // 行按快照去重：900 共享只出现一次
        assertThat(ladder.rows()).extracting(AdminOddsLadderVo.RowVo::snapshotId)
                .containsExactly(900L, 901L, 902L);
        assertThat(ladder.rows().get(0).frozen()).isTrue();
        assertThat(ladder.rows()).filteredOn(row -> !row.snapshotId().equals(900L))
                .allMatch(row -> !row.frozen());
    }

    /** 冻结行必须置顶：运营打开这个页面的第一件事是确认结算按哪一行计账。 */
    @Test
    void putsFrozenRowFirstEvenWhenItSortsLaterByLine() {
        Prediction prediction = asianPrediction(12L, 102L, 905L);
        when(predictionMapper.selectById(12L)).thenReturn(prediction);
        when(snapshotMapper.selectLatestHandicapLinesByMatchIds(List.of(102L), "NOVA_API"))
                .thenReturn(List.of(
                        snapshot(903L, "a-book", "-0.25", null, "1.90", "2.00", null, null),
                        snapshot(904L, "b-book", "-0.25", null, "1.92", "1.98", null, null),
                        // 冻结行单独在 0.75：只有一家报到该线，达不到最少家数，不参与主盘竞争
                        snapshot(905L, "z-book", "0.75", null, "2.05", "1.85", null, null)
                ));
        when(snapshotMapper.selectLatestTotalsLinesByMatchIds(List.of(102L), "NOVA_API"))
                .thenReturn(List.of());

        AdminOddsLadderVo ladder = service.query(12L);

        assertThat(ladder.rows()).extracting(AdminOddsLadderVo.RowVo::snapshotId)
                .startsWith(905L);
        // 冻结行本身未必在主盘线上：主盘是最平衡、且样本达标的线，两者不是同一概念
        assertThat(ladder.handicap().bestLine()).isEqualByComparingTo("-0.25");
        assertThat(ladder.rows()).extracting(AdminOddsLadderVo.RowVo::snapshotId)
                .containsExactly(905L, 903L, 904L);
        assertThat(ladder.handicap().lines()).filteredOn(AdminOddsLadderVo.LineVo::best).hasSize(1);
    }

    @Test
    void returnsEmptyLaddersInsteadOfFailingWhenNoConfirmedQuotesExist() {
        Prediction prediction = asianPrediction(13L, 103L, null);
        when(predictionMapper.selectById(13L)).thenReturn(prediction);
        when(snapshotMapper.selectLatestHandicapLinesByMatchIds(List.of(103L), "NOVA_API")).thenReturn(List.of());
        when(snapshotMapper.selectLatestTotalsLinesByMatchIds(List.of(103L), "NOVA_API")).thenReturn(List.of());

        AdminOddsLadderVo ladder = service.query(13L);

        assertThat(ladder.frozenSnapshotId()).isNull();
        assertThat(ladder.handicap().lines()).isEmpty();
        assertThat(ladder.handicap().bestLine()).isNull();
        assertThat(ladder.totals().lines()).isEmpty();
        assertThat(ladder.rows()).isEmpty();
    }

    private Prediction asianPrediction(Long id, Long matchId, Long frozenSnapshotId) {
        Prediction prediction = new Prediction();
        prediction.setId(id);
        prediction.setMatchId(matchId);
        prediction.setPredictionType(PredictionTypeEnum.ASIAN);
        prediction.setAsianOddsSnapshotId(frozenSnapshotId);
        return prediction;
    }

    private AsianOddsSnapshot snapshot(
            Long id,
            String bookmakerCode,
            String handicapLine,
            String totalLine,
            String homeOdds,
            String awayOdds,
            String overOdds,
            String underOdds
    ) {
        AsianOddsSnapshot snapshot = new AsianOddsSnapshot();
        snapshot.setId(id);
        snapshot.setMatchId(101L);
        snapshot.setProviderCode("THE_ODDS_API");
        snapshot.setBookmakerCode(bookmakerCode);
        snapshot.setHandicapLine(decimal(handicapLine));
        snapshot.setHomeOdds(decimal(homeOdds));
        snapshot.setAwayOdds(decimal(awayOdds));
        snapshot.setTotalLine(decimal(totalLine));
        snapshot.setOverOdds(decimal(overOdds));
        snapshot.setUnderOdds(decimal(underOdds));
        snapshot.setCapturedAt(CAPTURED_AT);
        return snapshot;
    }

    private BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
