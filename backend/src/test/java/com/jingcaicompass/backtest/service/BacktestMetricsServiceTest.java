package com.jingcaicompass.backtest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.backtest.entity.BacktestCaseEntity;
import com.jingcaicompass.backtest.entity.BacktestMetricEntity;
import com.jingcaicompass.backtest.enums.BacktestResultStatusEnum;
import com.jingcaicompass.backtest.mapper.BacktestMetricMapper;
import com.jingcaicompass.prediction.enums.AsianHandicapPickEnum;
import com.jingcaicompass.prediction.enums.BaselinePredictionSkipReasonEnum;
import com.jingcaicompass.prediction.enums.TotalGoalsPickEnum;
import com.jingcaicompass.settlement.enums.SettlementStatusEnum;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 指标口径的验证。
 *
 * <p>三条最容易写错的地方各有一条断言盯着：走盘不进命中率分母但进 {@code settled_count}、
 * {@code ALL} 的收益不重复累加、{@code ALL} 的命中数等于两市场之和。
 * 任一写错都会破坏 {@code ck_backtest_metrics_counts} 的等式关系（或让它悄悄通过却算错）。
 */
class BacktestMetricsServiceTest {

    private BacktestMetricMapper metricMapper;

    private BacktestMetricsService service;

    @BeforeEach
    void setUp() {
        metricMapper = mock(BacktestMetricMapper.class);
        // 不 stub insert：它的返回值不参与任何判断，而 stub 本身会记一次 invocation，
        // 让后面的 verify(times(n)) 多算一次。
        service = new BacktestMetricsService(metricMapper, new ObjectMapper());
    }

    @Test
    void writesThreeScopesPerGroup() {
        int written = service.recompute(7L, List.of(
                handicapHit(),
                totalsHitWithPushHandicap(),
                skipped()
        ), "model-v6");

        assertThat(written).isEqualTo(3);
        verify(metricMapper, times(3)).insert(org.mockito.ArgumentMatchers.<BacktestMetricEntity>any());
    }

    @Test
    void allScopeAggregatesBothMarketsWithoutDoubleCountingProfit() {
        BacktestMetricEntity all = recomputeAndCapture("ALL");

        assertThat(all.getCaseCount()).isEqualTo(3);
        assertThat(all.getDecidedCount()).isEqualTo(2);
        assertThat(all.getSkippedCount()).isEqualTo(1);
        assertThat(all.getPickCount()).isEqualTo(4);
        // 让球 HIT + PUSH、大小球 MISS + HIT → 4 腿全部结算。
        assertThat(all.getSettledCount()).isEqualTo(4);
        assertThat(all.getHitCount()).isEqualTo(2);
        assertThat(all.getMissCount()).isEqualTo(1);
        assertThat(all.getPushCount()).isEqualTo(1);
        // 走盘不进命中率分母：2 / 3。
        assertThat(all.getHitRate()).isEqualByComparingTo("0.666667");
        // 收益按 scope 只累加一次：0.9000 − 1.0000 + 0 + 0.8800 = 0.7800。
        assertThat(all.getProfitSum()).isEqualByComparingTo("0.7800");
    }

    @Test
    void handicapScopeIgnoresTotalsLegs() {
        BacktestMetricEntity handicap = recomputeAndCapture("ASIAN_HANDICAP");

        assertThat(handicap.getPickCount()).isEqualTo(2);
        assertThat(handicap.getSettledCount()).isEqualTo(2);
        assertThat(handicap.getHitCount()).isEqualTo(1);
        assertThat(handicap.getPushCount()).isEqualTo(1);
        assertThat(handicap.getMissCount()).isZero();
        assertThat(handicap.getHitRate()).isEqualByComparingTo("1.000000");
        assertThat(handicap.getProfitSum()).isEqualByComparingTo("0.9000");
    }

    @Test
    void totalsScopeIgnoresHandicapLegs() {
        BacktestMetricEntity totals = recomputeAndCapture("ASIAN_TOTAL_GOALS");

        assertThat(totals.getPickCount()).isEqualTo(2);
        assertThat(totals.getSettledCount()).isEqualTo(2);
        assertThat(totals.getHitCount()).isEqualTo(1);
        assertThat(totals.getMissCount()).isEqualTo(1);
        assertThat(totals.getProfitSum()).isEqualByComparingTo("-0.1200");
    }

    @Test
    void countsSkipReasons() {
        BacktestMetricEntity all = recomputeAndCapture("ALL");

        assertThat(all.getSkipReasonCounts())
                .containsEntry(BaselinePredictionSkipReasonEnum.LOW_CONFIDENCE_ASIAN_MARKETS.getCode(), 1);
    }

    @Test
    void reportsBrierOverTheSameSampleForModelAndMarket() {
        BacktestMetricEntity all = recomputeAndCapture("ALL");

        // 四个腿的连续化 outcome：让球 HIT=1.0、让球 PUSH=0.5、大小球 MISS=0.0、大小球 HIT=1.0。
        // 落库概率是首边（主队/大球）0.55；押次边时统计侧取 1−p = 0.45，这一层换算不能省——
        // 否则押客队的场次会按「押主队」的概率打分，模型胜率会凭空涨到 60% 以上。
        // 模型：0.45² + 0.45² + 0.55² + 0.05² = 0.81 → /4 = 0.2025
        // 市场（恒 0.50，押次边时也是 0.50）：0.25 + 0 + 0.25 + 0.25 = 0.75 → /4 = 0.1875
        // 走盘记 0.5 而不是剔除，恰好让「市场恒定 0.5」在这一腿拿满分——这是口径而非巧合。
        assertThat(all.getBrierSampleSize()).isEqualTo(4);
        assertThat(all.getBrierModel()).isEqualByComparingTo("0.202500");
        assertThat(all.getBrierMarket()).isEqualByComparingTo("0.187500");
        // brierDelta = market − model：负值表示这组样本上模型不如市场，不能因为它是「我们的模型」就取绝对值。
        assertThat(all.getBrierDelta()).isEqualByComparingTo("-0.015000");
    }

    private BacktestMetricEntity recomputeAndCapture(String scope) {
        service.recompute(7L, List.of(handicapHit(), totalsHitWithPushHandicap(), skipped()), "model-v6");
        ArgumentCaptor<BacktestMetricEntity> captor = ArgumentCaptor.forClass(BacktestMetricEntity.class);
        verify(metricMapper, times(3)).insert(captor.capture());
        List<BacktestMetricEntity> captured = new ArrayList<>(captor.getAllValues());
        return captured.stream()
                .filter(row -> scope.equals(row.getMarketScope()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少 scope=" + scope));
    }

    /** 让球 HIT（+0.9000），大小球 MISS（−1.0000）。 */
    private static BacktestCaseEntity handicapHit() {
        BacktestCaseEntity entity = base(1L);
        entity.setHandicapPick(AsianHandicapPickEnum.HOME_COVER);
        entity.setHandicapStatus(SettlementStatusEnum.HIT);
        entity.setHandicapProfit(new BigDecimal("0.9000"));
        entity.setTotalsPick(TotalGoalsPickEnum.OVER);
        entity.setTotalsStatus(SettlementStatusEnum.MISS);
        entity.setTotalsProfit(new BigDecimal("-1.0000"));
        return entity;
    }

    /** 让球 PUSH（0），大小球 HIT（+0.8800）。 */
    private static BacktestCaseEntity totalsHitWithPushHandicap() {
        BacktestCaseEntity entity = base(2L);
        entity.setHandicapPick(AsianHandicapPickEnum.AWAY_COVER);
        entity.setHandicapStatus(SettlementStatusEnum.PUSH);
        entity.setHandicapProfit(new BigDecimal("0.0000"));
        entity.setTotalsPick(TotalGoalsPickEnum.UNDER);
        entity.setTotalsStatus(SettlementStatusEnum.HIT);
        entity.setTotalsProfit(new BigDecimal("0.8800"));
        return entity;
    }

    /** 两个市场都没方向：计数进 skipped，不进 pick/settled。 */
    private static BacktestCaseEntity skipped() {
        BacktestCaseEntity entity = base(3L);
        entity.setSkipReason(BaselinePredictionSkipReasonEnum.LOW_CONFIDENCE_ASIAN_MARKETS);
        return entity;
    }

    private static BacktestCaseEntity base(Long id) {
        BacktestCaseEntity entity = new BacktestCaseEntity();
        entity.setId(id);
        entity.setRunId(7L);
        entity.setGroupKey("baseline");
        entity.setExternalMatchId("204215" + id);
        entity.setMatchId(10L + id);
        entity.setDecisionAt(Instant.parse("2026-06-20T09:00:00Z"));
        entity.setKickoffTime(Instant.parse("2026-06-20T12:00:00Z"));
        entity.setResultStatus(BacktestResultStatusEnum.FINAL);
        entity.setHomeScore(2);
        entity.setAwayScore(1);
        entity.setSettledAt(Instant.parse("2026-06-20T14:00:00Z"));
        // 概率首边口径：0.55 与 0.50 恒定，便于手算 Brier。
        entity.setModelCoverProb(new BigDecimal("0.550000"));
        entity.setMarketCoverProb(new BigDecimal("0.500000"));
        entity.setModelOverProb(new BigDecimal("0.550000"));
        entity.setMarketOverProb(new BigDecimal("0.500000"));
        return entity;
    }
}
