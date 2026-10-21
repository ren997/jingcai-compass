package com.jingcaicompass.odds.service;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 亚盘聚合数学工具的口径与精度。 */
class AsianMarketMathTest {

    @Test
    void devigReturnsComplementaryProbabilities() {
        BigDecimal first = AsianMarketMath.devigFirst(new BigDecimal("1.88"), new BigDecimal("1.97"));

        assertThat(first).isEqualByComparingTo("0.511688");
        assertThat(BigDecimal.ONE.subtract(first)).isEqualByComparingTo("0.488312");
    }

    @Test
    void devigReturnsEvenSplitForSymmetricPrices() {
        assertThat(AsianMarketMath.devigFirst(new BigDecimal("1.95"), new BigDecimal("1.95")))
                .isEqualByComparingTo("0.500000");
    }

    @Test
    void medianHandlesOddSample() {
        assertThat(AsianMarketMath.median(List.of(
                new BigDecimal("0.3"), new BigDecimal("0.5"), new BigDecimal("0.9"))))
                .isEqualByComparingTo("0.500000");
    }

    @Test
    void medianAveragesMiddlePairForEvenSample() {
        assertThat(AsianMarketMath.median(List.of(
                new BigDecimal("0.4"), new BigDecimal("0.5"), new BigDecimal("0.6"), new BigDecimal("0.7"))))
                .isEqualByComparingTo("0.550000");
    }

    @Test
    void medianRejectsEmptySample() {
        assertThatThrownBy(() -> AsianMarketMath.median(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void populationStandardDeviationMatchesHandCalculation() {
        assertThat(AsianMarketMath.populationStandardDeviation(List.of(
                new BigDecimal("0.4"), new BigDecimal("0.5"), new BigDecimal("0.6"))))
                .isEqualByComparingTo("0.081650");
    }

    @Test
    void standardDeviationIsZeroForIdenticalQuotes() {
        assertThat(AsianMarketMath.populationStandardDeviation(List.of(
                new BigDecimal("0.5"), new BigDecimal("0.5"), new BigDecimal("0.5"))))
                .isEqualByComparingTo("0.000000");
    }

    @Test
    void lineNormalizationCollapsesEquivalentDecimals() {
        assertThat(AsianMarketMath.normalizeLine(new BigDecimal("2.5")))
                .isEqualByComparingTo("2.50");
        assertThat(AsianMarketMath.normalizeLine(new BigDecimal("+2.50")))
                .isEqualByComparingTo("2.50");
        assertThat(AsianMarketMath.normalizeLine(new BigDecimal("-0.250")))
                .isEqualByComparingTo("-0.25");
    }

    @Test
    void lineKindRankFollowsDesignPreference() {
        assertThat(AsianMarketMath.lineKindRank(new BigDecimal("0.5"))).isZero();
        assertThat(AsianMarketMath.lineKindRank(new BigDecimal("2.5"))).isZero();
        assertThat(AsianMarketMath.lineKindRank(new BigDecimal("0.25"))).isEqualTo(1);
        assertThat(AsianMarketMath.lineKindRank(new BigDecimal("0.75"))).isEqualTo(1);
        assertThat(AsianMarketMath.lineKindRank(new BigDecimal("-1.25"))).isEqualTo(1);
        assertThat(AsianMarketMath.lineKindRank(new BigDecimal("0"))).isEqualTo(2);
        assertThat(AsianMarketMath.lineKindRank(new BigDecimal("-1"))).isEqualTo(2);
        assertThat(AsianMarketMath.lineKindRank(new BigDecimal("0.125"))).isEqualTo(3);
    }

    @Test
    void rejectsPricesAtOrBelowOne() {
        assertThat(AsianMarketMath.isUsablePrice(new BigDecimal("1.00"))).isFalse();
        assertThat(AsianMarketMath.isUsablePrice(new BigDecimal("0.99"))).isFalse();
        assertThat(AsianMarketMath.isUsablePrice(null)).isFalse();
        assertThat(AsianMarketMath.isUsablePrice(new BigDecimal("1.01"))).isTrue();
    }

    @Test
    void fairPriceInvertsProbabilityAtPriceScale() {
        assertThat(AsianMarketMath.fairPrice(new BigDecimal("0.511688")))
                .isEqualByComparingTo("1.9543");
    }
}
