package com.jingcaicompass.odds.service;

import com.jingcaicompass.odds.client.AsianModelProperties;
import com.jingcaicompass.odds.service.ImpliedPoissonModel.Market;
import com.jingcaicompass.odds.service.ImpliedPoissonModel.Side;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段 D 门槛的纯计算验收：跨庄分歧（真拦）与 edge 影子门槛（只记录）。
 *
 * <p>最关键的一条是 {@link #yieldsZeroEdgeWhenModelEqualsMarket()}：让球方向的模型概率由 λ 投影回
 * 同一条主盘线得到，而 λ 正是由该线的去水概率反解，故 edge 恒为 0，任何正的安全边际都不可能通过。
 * 这条断言把「按文档做硬门槛会零发布」这个结论钉在代码里，防止后人误以为是参数没调好。</p>
 */
class AsianPublishGateTest {

    private static final BigDecimal ONE_POINT_NINETY = new BigDecimal("1.90");
    private static final BigDecimal TWO = new BigDecimal("2.00");

    private final AsianPublishGate gate = new AsianPublishGate();

    @Test
    void defaultsTolerancesToCalibratedValues() {
        assertThat(gate.dispersionTolerance()).isEqualByComparingTo("0.0200");
        assertThat(gate.safetyMargin()).isEqualByComparingTo("0.0100");
    }

    @Test
    void treatsNullPropertiesAsDefaults() {
        AsianPublishGate fromNull = new AsianPublishGate(null);

        assertThat(fromNull.dispersionTolerance()).isEqualByComparingTo("0.0200");
        assertThat(fromNull.safetyMargin()).isEqualByComparingTo("0.0100");
    }

    @Test
    void neverExceedsDispersionAtOrBelowTolerance() {
        // 恰等于上限必须放行：阈值写 2%、实现按 1.99% 执行是最难排查的一类错位。
        assertThat(gate.exceedsDispersion(new BigDecimal("0.020000"))).isFalse();
        assertThat(gate.exceedsDispersion(new BigDecimal("0.005000"))).isFalse();
        assertThat(gate.exceedsDispersion(BigDecimal.ZERO)).isFalse();
    }

    @Test
    void exceedsDispersionJustAboveTolerance() {
        assertThat(gate.exceedsDispersion(new BigDecimal("0.020001"))).isTrue();
        assertThat(gate.exceedsDispersion(new BigDecimal("0.050000"))).isTrue();
    }

    @Test
    void treatsMissingDispersionAsNoEvidence() {
        // 没有分歧证据不等于「一致」；这里不否决，但也不应抛异常。
        assertThat(gate.exceedsDispersion(null)).isFalse();
    }

    @Test
    void computesEdgeBreakevenAndMarginOnFirstSide() {
        AsianPublishGate.Verdict verdict =
                gate.evaluate(Market.HANDICAP, Side.FIRST, 0.55d, new BigDecimal("0.50"),
                        ONE_POINT_NINETY, ONE_POINT_NINETY);

        assertThat(verdict.modelProbability()).isEqualByComparingTo("0.550000");
        assertThat(verdict.marketProbability()).isEqualByComparingTo("0.500000");
        assertThat(verdict.edge()).isEqualByComparingTo("0.050000");
        assertThat(verdict.breakeven()).isEqualByComparingTo("0.526316");
        assertThat(verdict.required()).isEqualByComparingTo("0.536316");
        assertThat(verdict.margin()).isEqualByComparingTo("0.023684");
        assertThat(verdict.wouldPublish()).isTrue();
    }

    @Test
    void flipsProbabilityAndUsesSecondPriceOnSecondSide() {
        // 同一个方向判断，换成客队视角后 edge 必须取反、价格必须换成次边水位。
        AsianPublishGate.Verdict verdict =
                gate.evaluate(Market.HANDICAP, Side.SECOND, 0.55d, new BigDecimal("0.50"),
                        ONE_POINT_NINETY, ONE_POINT_NINETY);

        assertThat(verdict.modelProbability()).isEqualByComparingTo("0.450000");
        assertThat(verdict.marketProbability()).isEqualByComparingTo("0.500000");
        assertThat(verdict.edge()).isEqualByComparingTo("-0.050000");
        assertThat(verdict.margin()).isEqualByComparingTo("-0.076316");
        assertThat(verdict.wouldPublish()).isFalse();
    }

    @Test
    void passesExactlyAtRequiredProbability() {
        // 价格 2.00 → 盈亏平衡 0.500000；模型 0.510000 恰好等于 0.5 + 边际 0.01，应判通过（>= 而非 >）。
        AsianPublishGate.Verdict verdict =
                gate.evaluate(Market.TOTALS, Side.FIRST, 0.51d, new BigDecimal("0.50"), TWO, TWO);

        assertThat(verdict.required()).isEqualByComparingTo("0.510000");
        assertThat(verdict.margin()).isEqualByComparingTo("0.010000");
        assertThat(verdict.wouldPublish()).isTrue();
    }

    @Test
    void failsOneTenThousandthBelowRequiredProbability() {
        AsianPublishGate.Verdict verdict =
                gate.evaluate(Market.TOTALS, Side.FIRST, 0.5099d, new BigDecimal("0.50"), TWO, TWO);

        assertThat(verdict.margin()).isEqualByComparingTo("0.009900");
        assertThat(verdict.wouldPublish()).isFalse();
    }

    @Test
    void yieldsZeroEdgeWhenModelEqualsMarket() {
        // 让球主盘的真实情形：模型概率就是市场共识概率，投影残差 1e-16。
        AsianPublishGate.Verdict verdict =
                gate.evaluate(Market.HANDICAP, Side.FIRST, 0.52d, new BigDecimal("0.52"),
                        ONE_POINT_NINETY, ONE_POINT_NINETY);

        assertThat(verdict.edge()).isEqualByComparingTo("0.000000");
        // edge 为 0，但仍然要按该家真实报价判盈亏：0.52 < 0.526316，故必然不通过。
        assertThat(verdict.margin()).isEqualByComparingTo("-0.006316");
        assertThat(verdict.wouldPublish()).isFalse();
    }

    @Test
    void neverProducesNegativeZeroEdge() {
        // double 相减会留下 -1.7e-17 并在摘要里渲染成 -0.0000；BigDecimal 定标后必须是干净的 0。
        AsianPublishGate.Verdict verdict =
                gate.evaluate(Market.HANDICAP, Side.FIRST, 0.5199999d, new BigDecimal("0.52"),
                        ONE_POINT_NINETY, ONE_POINT_NINETY);

        assertThat(verdict.edge().toPlainString()).isEqualTo("0.000000");
        assertThat(verdict.edge().signum()).isZero();
    }

    @Test
    void usesFrozenPriceInsteadOfConsensusFairPrice() {
        // 盈亏平衡来自该家真实报价（1.90 → 0.526316），不是共识公平价（0.52 → 0.500000）。
        AsianPublishGate.Verdict atFrozenPrice =
                gate.evaluate(Market.HANDICAP, Side.FIRST, 0.52d, new BigDecimal("0.52"),
                        ONE_POINT_NINETY, ONE_POINT_NINETY);
        AsianPublishGate.Verdict atFairPrice =
                gate.evaluate(Market.HANDICAP, Side.FIRST, 0.52d, new BigDecimal("0.52"), TWO, TWO);

        assertThat(atFrozenPrice.required()).isEqualByComparingTo("0.536316");
        assertThat(atFairPrice.required()).isEqualByComparingTo("0.510000");
        assertThat(atFrozenPrice.required()).isGreaterThan(atFairPrice.required());
    }

    @Test
    void honoursConfiguredSafetyMargin() {
        AsianPublishGate tight = new AsianPublishGate(
                new AsianModelProperties(null, null, null, null, new BigDecimal("0.0025")));

        AsianPublishGate.Verdict verdict =
                tight.evaluate(Market.TOTALS, Side.FIRST, 0.505d, new BigDecimal("0.50"), TWO, TWO);

        assertThat(tight.safetyMargin()).isEqualByComparingTo("0.0025");
        assertThat(verdict.required()).isEqualByComparingTo("0.502500");
        assertThat(verdict.margin()).isEqualByComparingTo("0.005000");
        assertThat(verdict.wouldPublish()).isTrue();
    }
}
