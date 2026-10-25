package com.jingcaicompass.odds.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** 阈值参数的默认值必须与原先硬编码的常量逐字相等，否则等于悄悄改了线上判定口径。 */
class AsianModelPropertiesTest {

    @Test
    void defaultsMatchFormerlyHardcodedConstants() {
        AsianModelProperties defaults = AsianModelProperties.defaults();

        assertThat(defaults.highConfidenceMargin()).isEqualByComparingTo("0.1000");
        assertThat(defaults.mediumConfidenceMargin()).isEqualByComparingTo("0.1600");
        assertThat(defaults.highDirectionSignal()).isEqualByComparingTo("0.0750");
        assertThat(defaults.mediumDirectionSignal()).isEqualByComparingTo("0.0250");
        // 标度也要一致：0.1000 与 0.1 数值相等，但写进摘要与指标时位数不同。
        assertThat(defaults.highConfidenceMargin().scale()).isEqualTo(4);
        assertThat(defaults.mediumDirectionSignal().scale()).isEqualTo(4);
    }

    @Test
    void nullThresholdsFallBackToDefaults() {
        AsianModelProperties properties =
                new AsianModelProperties(null, null, null, null, null, null, null, null, null);

        assertThat(properties.highConfidenceMargin()).isEqualByComparingTo("0.1000");
        assertThat(properties.mediumConfidenceMargin()).isEqualByComparingTo("0.1600");
        assertThat(properties.highDirectionSignal()).isEqualByComparingTo("0.0750");
        assertThat(properties.mediumDirectionSignal()).isEqualByComparingTo("0.0250");
        // 其余五项的老默认值不能被新字段带偏。
        assertThat(properties.neighborTolerance()).isEqualByComparingTo("0.0400");
        assertThat(properties.residualTolerance()).isEqualByComparingTo("0.0010");
        assertThat(properties.maxGoals()).isEqualTo(24);
        assertThat(properties.dispersionTolerance()).isEqualByComparingTo("0.0200");
        assertThat(properties.safetyMargin()).isEqualByComparingTo("0.0100");
    }

    @Test
    void rejectsSignalsWhereHighIsWeakerThanMedium() {
        assertThatThrownBy(() -> props("0.1000", "0.1600", "0.0200", "0.0250"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("highDirectionSignal");
    }

    @Test
    void rejectsMarginWhereHighIsLooserThanMedium() {
        assertThatThrownBy(() -> props("0.2000", "0.1600", "0.0750", "0.0250"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("highConfidenceMargin");
    }

    @Test
    void withConfidenceThresholdsOnlyReplacesThoseFourFields() {
        AsianModelProperties base = new AsianModelProperties(
                new BigDecimal("0.0500"), new BigDecimal("0.0020"), 28,
                new BigDecimal("0.0300"), new BigDecimal("0.0200"),
                null, null, null, null);

        AsianModelProperties tuned = base.withConfidenceThresholds(
                new BigDecimal("0.0800"), new BigDecimal("0.1400"),
                new BigDecimal("0.0900"), new BigDecimal("0.0300"));

        assertThat(tuned.highConfidenceMargin()).isEqualByComparingTo("0.0800");
        assertThat(tuned.mediumConfidenceMargin()).isEqualByComparingTo("0.1400");
        assertThat(tuned.highDirectionSignal()).isEqualByComparingTo("0.0900");
        assertThat(tuned.mediumDirectionSignal()).isEqualByComparingTo("0.0300");
        assertThat(tuned.neighborTolerance()).isEqualByComparingTo("0.0500");
        assertThat(tuned.residualTolerance()).isEqualByComparingTo("0.0020");
        assertThat(tuned.maxGoals()).isEqualTo(28);
        assertThat(tuned.dispersionTolerance()).isEqualByComparingTo("0.0300");
        assertThat(tuned.safetyMargin()).isEqualByComparingTo("0.0200");
    }

    private static AsianModelProperties props(String high, String medium, String highSignal, String mediumSignal) {
        return new AsianModelProperties(
                null, null, null, null, null,
                new BigDecimal(high), new BigDecimal(medium),
                new BigDecimal(highSignal), new BigDecimal(mediumSignal));
    }
}
