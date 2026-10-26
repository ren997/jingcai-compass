package com.jingcaicompass.backtest.enums;

/**
 * 回测指标的统计口径范围。
 *
 * <p>三个范围必须分别计算、不能由 {@code ALL} 相加得出：亚盘两个市场的概率是<b>二分类</b>
 * （该方向赢/不赢），把两个市场混进同一个 Brier 会把两种不同的判定尺度平均到一起；
 * 而命中率又必须在同一市场内统计半赢半输。{@code StatisticsCalculator} 本身就是按
 * 「一条预测一个方向市场」实现二分类的，混算会让模型胜率凭空虚高。</p>
 *
 * <p>{@code ALL} 的含义是「两个方向都参与，但每个方向各自成样本」，
 * 不是「把两个市场的概率相加」。</p>
 */
public enum BacktestMarketScopeEnum {

    /** 两个市场都参与，逐方向累加。 */
    ALL,

    /** 只看让球。 */
    ASIAN_HANDICAP,

    /** 只看大小球。 */
    ASIAN_TOTAL_GOALS;

    public boolean includesHandicap() {
        return this != ASIAN_TOTAL_GOALS;
    }

    public boolean includesTotals() {
        return this != ASIAN_HANDICAP;
    }
}
