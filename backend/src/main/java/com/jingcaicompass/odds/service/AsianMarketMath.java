package com.jingcaicompass.odds.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 亚盘聚合所用的纯数学工具。
 *
 * <p>概率统一为 6 位小数、价格统一为 4 位小数，与 {@code asian_odds_snapshots} 的
 * {@code NUMERIC(10,4)} 对齐。此处集中唯一实现，避免聚合器与选行器各自写一份去水公式后漂移。</p>
 */
public final class AsianMarketMath {

    /** 概率刻度：与 predictions 概率列一致。 */
    public static final int PROBABILITY_SCALE = 6;

    /** 价格刻度：与 asian_odds_snapshots 水位列一致。 */
    public static final int PRICE_SCALE = 4;

    /** 盘口线刻度：0.25 为一档，两位小数已足够。 */
    public static final int LINE_SCALE = 2;

    private static final BigDecimal HALF = new BigDecimal("0.5");
    private static final BigDecimal QUARTER = new BigDecimal("0.25");
    private static final BigDecimal THREE_QUARTERS = new BigDecimal("0.75");
    private static final BigDecimal MIN_PRICE = BigDecimal.ONE;

    private AsianMarketMath() {
    }

    /** 单家双边去水，返回首边（主队 / 大球）的归一概率。 */
    public static BigDecimal devigFirst(BigDecimal firstPrice, BigDecimal secondPrice) {
        BigDecimal first = inverse(firstPrice);
        BigDecimal second = inverse(secondPrice);
        BigDecimal total = first.add(second);
        return first.divide(total, PROBABILITY_SCALE, RoundingMode.HALF_UP);
    }

    /** 判断价格是否可用于聚合：必须严格大于 1.00，否则去水会得到无意义的概率。 */
    public static boolean isUsablePrice(BigDecimal price) {
        return price != null && price.compareTo(MIN_PRICE) > 0;
    }

    /** 中位数；偶数个样本取中间两值的算术平均。调用方保证非空。 */
    public static BigDecimal median(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("median requires at least one value");
        }
        List<BigDecimal> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int size = sorted.size();
        int middle = size / 2;
        if (size % 2 == 1) {
            return sorted.get(middle).setScale(PROBABILITY_SCALE, RoundingMode.HALF_UP);
        }
        return sorted.get(middle - 1)
                .add(sorted.get(middle))
                .divide(BigDecimal.TWO, PROBABILITY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 总体标准差（除以 n）。
     *
     * <p>参与聚合的是当前全部报价而非抽样，因此用总体口径；离散度只作为置信信号，
     * 不做任何阈值判断（阈值属阶段 D）。</p>
     */
    public static BigDecimal populationStandardDeviation(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("standard deviation requires at least one value");
        }
        BigDecimal count = BigDecimal.valueOf(values.size());
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            sum = sum.add(value);
        }
        BigDecimal mean = sum.divide(count, PROBABILITY_SCALE, RoundingMode.HALF_UP);
        BigDecimal squaredSum = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            BigDecimal deviation = value.subtract(mean);
            squaredSum = squaredSum.add(deviation.multiply(deviation));
        }
        // 方差先保留高精度再开方，避免先舍入到 6 位造成末位漂移（0.081650 与 0.081652 之差）。
        BigDecimal variance = squaredSum.divide(count, 20, RoundingMode.HALF_UP);
        return variance.sqrt(new MathContext(20, RoundingMode.HALF_UP))
                .setScale(PROBABILITY_SCALE, RoundingMode.HALF_UP);
    }

    /** 盘口线归一：统一到两位小数，使 2.5 / 2.50 / +2.50 落成同一个键。 */
    public static BigDecimal normalizeLine(BigDecimal line) {
        return line.setScale(LINE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 线型优先级，只在「两边去水概率同样接近 50/50」时作为 tie-break。
     *
     * <p>0 = x.5（无走盘）、1 = x.25 / x.75（拆盘）、2 = 整数盘（有走盘）、3 = 不在 0.25 网格上。
     * 未加任何容差：实测两条线的平衡度差距可以小到 0.0028，容差会直接选错主盘。</p>
     */
    public static int lineKindRank(BigDecimal line) {
        BigDecimal fraction = line.remainder(BigDecimal.ONE).abs();
        if (fraction.compareTo(HALF) == 0) {
            return 0;
        }
        if (fraction.compareTo(QUARTER) == 0 || fraction.compareTo(THREE_QUARTERS) == 0) {
            return 1;
        }
        if (fraction.compareTo(BigDecimal.ZERO) == 0) {
            return 2;
        }
        return 3;
    }

    /** 公平价 = 1 / 概率；仅用于选行打分与阶段 C/D 的 edge 计算，不作为计账价格。 */
    public static BigDecimal fairPrice(BigDecimal probability) {
        return BigDecimal.ONE.divide(probability, PRICE_SCALE, RoundingMode.HALF_UP);
    }

    /** 倒数，保留 12 位中间精度，避免连续运算累积舍入误差。 */
    public static BigDecimal inverse(BigDecimal value) {
        return BigDecimal.ONE.divide(value, 12, RoundingMode.HALF_UP);
    }
}
