package com.jingcaicompass.prediction.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.jingcaicompass.odds.client.AsianModelProperties;
import com.jingcaicompass.prediction.enums.ConfidenceLevelEnum;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * 置信度判定的档位与边界。
 *
 * <p>阈值是「margin 上限 + signal 下限」两个条件同时成立，且两处比较都取等号
 * （{@code margin <= 上限}、{@code signal >= 下限}）。取等号方向写反不会报错，只会让边界样本
 * 静默换档，所以这两条必须被钉住。</p>
 */
class AsianConfidenceClassifierTest {

    private final AsianConfidenceClassifier classifier =
            new AsianConfidenceClassifier(AsianModelProperties.defaults());

    @Test
    void balancedBookWithNoSignalIsLow() {
        // 1.90/1.90：去水后恰好 50/50，signal = 0，抽水 5.3% 也不算高。
        assertThat(classifier.classify(new BigDecimal("1.90"), new BigDecimal("1.90")))
                .isEqualTo(ConfidenceLevelEnum.LOW);
    }

    @Test
    void mildImbalanceIsMedium() {
        // margin 2.83%（≤10%），signal 5.13%（≥2.5% 但 <7.5%）→ 中间档。
        assertThat(classifier.classify(new BigDecimal("1.85"), new BigDecimal("2.05")))
                .isEqualTo(ConfidenceLevelEnum.MEDIUM);
    }

    @Test
    void strongImbalanceWithLowMarginIsHigh() {
        // margin 5.13%（≤10%），signal 26.8%（≥7.5%）。
        assertThat(classifier.classify(new BigDecimal("1.50"), new BigDecimal("2.60")))
                .isEqualTo(ConfidenceLevelEnum.HIGH);
    }

    @Test
    void strongImbalanceWithHighMarginIsOnlyMedium() {
        // signal 15.5% 足够 HIGH，但 margin 15.4% 超过 10% 上限，被抽水吃掉一档。
        assertThat(classifier.classify(new BigDecimal("1.50"), new BigDecimal("2.05")))
                .isEqualTo(ConfidenceLevelEnum.MEDIUM);
    }

    @Test
    void marginAboveMediumCeilingIsLowEvenWithStrongSignal() {
        // margin 18.0% 超过 16% 上限：方向再明确也不发布。
        assertThat(classifier.classify(new BigDecimal("1.42"), new BigDecimal("2.10")))
                .isEqualTo(ConfidenceLevelEnum.LOW);
    }

    @Test
    void signalEqualToStringThresholdStillQualifiesAsHigh() {
        // 3.00/1.50 → margin 恰为 0、signal 恰为 0.333333；把 HIGH 的 signal 下限设成同一个值。
        AsianConfidenceClassifier tightBoundary = new AsianConfidenceClassifier(new AsianModelProperties(
                null, null, null, null, null,
                new BigDecimal("0.1000"), new BigDecimal("0.1600"),
                new BigDecimal("0.333333"), new BigDecimal("0.0250")));

        assertThat(tightBoundary.classify(new BigDecimal("3.00"), new BigDecimal("1.50")))
                .isEqualTo(ConfidenceLevelEnum.HIGH);
    }

    @Test
    void marginEqualToCeilingStillQualifies() {
        // 2.00/2.00 → margin 恰为 0、signal 恰为 0；把 MEDIUM 的 margin 上限也设成 0。
        // HIGH 的 signal 下限抬高 0.0001 使其不可达，于是结果只能来自「margin <= 上限」成立。
        AsianConfidenceClassifier tightBoundary = new AsianConfidenceClassifier(new AsianModelProperties(
                null, null, null, null, null,
                BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("0.0001"), BigDecimal.ZERO));

        assertThat(tightBoundary.classify(new BigDecimal("2.00"), new BigDecimal("2.00")))
                .isEqualTo(ConfidenceLevelEnum.MEDIUM);
    }

    @Test
    void marginJustAboveCeilingDropsToLow() {
        // 同一组报价（margin 0.0283454…），上限卡在 0.0283 → 越界即 LOW。
        AsianConfidenceClassifier strict = new AsianConfidenceClassifier(new AsianModelProperties(
                null, null, null, null, null,
                new BigDecimal("0.0283"), new BigDecimal("0.0283"),
                new BigDecimal("0.0750"), new BigDecimal("0.0250")));

        assertThat(strict.classify(new BigDecimal("1.85"), new BigDecimal("2.05")))
                .isEqualTo(ConfidenceLevelEnum.LOW);
    }

    @Test
    void inverseUsesTwelveDecimalScale() {
        assertThat(AsianConfidenceClassifier.inverse(new BigDecimal("3.00")))
                .isEqualTo(new BigDecimal("0.333333333333"));
    }
}
