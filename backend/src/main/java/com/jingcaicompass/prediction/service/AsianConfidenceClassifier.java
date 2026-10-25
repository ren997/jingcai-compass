package com.jingcaicompass.prediction.service;

import com.jingcaicompass.odds.client.AsianModelProperties;
import com.jingcaicompass.prediction.enums.ConfidenceLevelEnum;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 按冻结行报价判定置信度档位。
 *
 * <p>两个量的定义（{@code o1}/{@code o2} 是冻结行两侧水位）：</p>
 * <ul>
 *   <li>{@code margin = 1/o1 + 1/o2 - 1}：这一家的抽水率，两侧越平衡越大；</li>
 *   <li>{@code signal = |1/o1 - 1/o2| / (1/o1 + 1/o2)}：两侧去水概率的归一化差距，越不平衡越大。</li>
 * </ul>
 *
 * <p>两个条件同时满足才升级：只有抽水低（margin 小）但两侧接近 50/50 时，说明这家自己也没观点，
 * 此时按方向去押等于赌硬币；反过来 signal 很高但 margin 也高时，方向虽明确却被抽水吃掉。</p>
 *
 * <p>**临场盘越平衡 signal 越小**，所以「方向明确却判 LOW」不是缺陷，而是这条口径的必然结果。</p>
 *
 * <p>抽出来之前是 {@code BaselinePredictionGenerationServiceImpl} 里的 private 方法 + 四个
 * 硬编码常量；回测要比较不同阈值，必须让它可参数化，且不能复制一份公式（手写副本已两次出现
 * 末位漂移）。</p>
 */
public final class AsianConfidenceClassifier {

    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final int INVERSE_SCALE = 12;
    private static final int SIGNAL_SCALE = 6;

    private final BigDecimal highConfidenceMargin;
    private final BigDecimal mediumConfidenceMargin;
    private final BigDecimal highDirectionSignal;
    private final BigDecimal mediumDirectionSignal;

    public AsianConfidenceClassifier(AsianModelProperties properties) {
        AsianModelProperties effective = properties == null ? AsianModelProperties.defaults() : properties;
        this.highConfidenceMargin = effective.highConfidenceMargin();
        this.mediumConfidenceMargin = effective.mediumConfidenceMargin();
        this.highDirectionSignal = effective.highDirectionSignal();
        this.mediumDirectionSignal = effective.mediumDirectionSignal();
    }

    /**
     * @param primaryOdds   所需方向这一侧的水位（让球为主队、大小球为大球）
     * @param secondaryOdds 另一侧水位
     */
    public ConfidenceLevelEnum classify(BigDecimal primaryOdds, BigDecimal secondaryOdds) {
        Objects.requireNonNull(primaryOdds, "primaryOdds must not be null");
        Objects.requireNonNull(secondaryOdds, "secondaryOdds must not be null");
        BigDecimal primary = inverse(primaryOdds);
        BigDecimal secondary = inverse(secondaryOdds);
        BigDecimal total = primary.add(secondary);
        BigDecimal margin = total.subtract(ONE);
        BigDecimal signal = primary.subtract(secondary).abs().divide(total, SIGNAL_SCALE, RoundingMode.HALF_UP);
        if (margin.compareTo(highConfidenceMargin) <= 0 && signal.compareTo(highDirectionSignal) >= 0) {
            return ConfidenceLevelEnum.HIGH;
        }
        if (margin.compareTo(mediumConfidenceMargin) <= 0 && signal.compareTo(mediumDirectionSignal) >= 0) {
            return ConfidenceLevelEnum.MEDIUM;
        }
        return ConfidenceLevelEnum.LOW;
    }

    /** 供摘要与诊断复用的去水倒数，标度与判定内部完全一致。 */
    public static BigDecimal inverse(BigDecimal odds) {
        return ONE.divide(odds, INVERSE_SCALE, RoundingMode.HALF_UP);
    }
}
