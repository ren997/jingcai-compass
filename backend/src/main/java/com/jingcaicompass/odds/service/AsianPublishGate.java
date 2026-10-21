package com.jingcaicompass.odds.service;

import com.jingcaicompass.odds.client.AsianModelProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 选择性发布的两道判定：跨庄分歧（真拦）与 edge（影子门槛）。
 *
 * <p><b>当前为影子模式：edge 只返回裁决，不改变生成结果。</b>原因是它在让球主盘上结构性
 * 恒为 0——λ 正是由该主盘的去水概率反解出来的，再投影回同一条线必然相等（残差 1e-16）。
 * 任何正的安全边际都会拒发 100% 的让球方向，与「调参」无关（见设计文档 4.7.1）。</p>
 *
 * <p>离散度不同：它衡量的是各家庄之间是否互相矛盾，与 λ 的求解方式无关，因此是真实可用的
 * 市场质量信号，本阶段直接参与否决。</p>
 *
 * <p>本类无 Spring 依赖，由使用方直接 {@code new}，与 {@link ImpliedPoissonModel} 同风格。</p>
 */
public final class AsianPublishGate {

    /** 概率与价格的中间刻度；与 {@code predictions} 的概率列一致。 */
    private static final int PROBABILITY_SCALE = AsianMarketMath.PROBABILITY_SCALE;

    private final BigDecimal dispersionTolerance;
    private final BigDecimal safetyMargin;

    public AsianPublishGate(AsianModelProperties properties) {
        AsianModelProperties effective =
                properties == null ? AsianModelProperties.defaults() : properties;
        this.dispersionTolerance = effective.dispersionTolerance();
        this.safetyMargin = effective.safetyMargin();
    }

    /** 使用与 {@link AsianModelProperties#defaults()} 一致的默认阈值。 */
    public AsianPublishGate() {
        this(null);
    }

    public BigDecimal dispersionTolerance() {
        return dispersionTolerance;
    }

    public BigDecimal safetyMargin() {
        return safetyMargin;
    }

    /**
     * 该市场的跨庄分歧是否超过上限。
     *
     * <p>严格大于才否决：恰等于上限仍放行，否则阈值取整后会出现「文档写 2%、实际按 1.99% 执行」
     * 的错位。{@code dispersion} 为 null 视为没有分歧证据，不否决（正常路径不会为 null，
     * 聚合线至少两家报价）。</p>
     */
    public boolean exceedsDispersion(BigDecimal dispersion) {
        return dispersion != null && dispersion.compareTo(dispersionTolerance) > 0;
    }

    /**
     * 计算某个市场在指定方向上的影子门槛裁决。
     *
     * <p>调用方保证两个价格严格大于 1.00（{@link AsianFreezeSnapshotSelector} 已过滤），
     * 因此 {@code 1 / price} 不会越界。</p>
     *
     * <p><b>方向必须换算到所选一侧</b>：看客队 / 小球时概率取 {@code 1 - p}、价格取次边。
     * 若仍用首边概率，{@code edge} 会得到与 {@code breakeven} 反号的量，{@code wouldPublish}
     * 就成了无意义的比较。</p>
     *
     * @param modelFirstProbability  模型给出的首边概率（0~1）
     * @param marketFirstProbability 同一条线上去水共识的首边概率
     */
    public Verdict evaluate(
            ImpliedPoissonModel.Market market,
            ImpliedPoissonModel.Side side,
            double modelFirstProbability,
            BigDecimal marketFirstProbability,
            BigDecimal firstPrice,
            BigDecimal secondPrice
    ) {
        BigDecimal modelPick = BigDecimal.valueOf(modelFirstProbability)
                .setScale(PROBABILITY_SCALE, RoundingMode.HALF_UP);
        BigDecimal marketPick = marketFirstProbability
                .setScale(PROBABILITY_SCALE, RoundingMode.HALF_UP);
        BigDecimal price = firstPrice;
        if (side == ImpliedPoissonModel.Side.SECOND) {
            modelPick = BigDecimal.ONE.subtract(modelPick);
            marketPick = BigDecimal.ONE.subtract(marketPick);
            price = secondPrice;
        }
        // 全 BigDecimal：double 相减会在「模型等于市场」时留下 -1.7e-17，摘要会渲染成 -0.0000。
        BigDecimal edge = modelPick.subtract(marketPick);
        BigDecimal breakeven = BigDecimal.ONE.divide(price, PROBABILITY_SCALE, RoundingMode.HALF_UP);
        BigDecimal required = breakeven.add(safetyMargin);
        BigDecimal margin = modelPick.subtract(breakeven);
        return new Verdict(
                market, side, modelPick, marketPick, edge, breakeven, required, margin,
                margin.compareTo(safetyMargin) >= 0);
    }

    /**
     * 单个方向的影子门槛裁决。
     *
     * @param edge    模型概率减市场去水概率，即设计文档 4.11.1 的 edge 定义；让球主盘上应恒为 0
     * @param required {@code 1 / 冻结报价 + 安全边际}，即「要过门槛模型概率得到多少」
     * @param margin  模型概率减盈亏平衡概率，即实际余量；{@code margin >= safetyMargin} 才是正期望
     */
    public record Verdict(
            ImpliedPoissonModel.Market market,
            ImpliedPoissonModel.Side side,
            BigDecimal modelProbability,
            BigDecimal marketProbability,
            BigDecimal edge,
            BigDecimal breakeven,
            BigDecimal required,
            BigDecimal margin,
            boolean wouldPublish
    ) {
    }
}
