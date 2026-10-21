package com.jingcaicompass.odds.client;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 亚盘 λ 反解模型的运行参数。
 *
 * <p>搜索域、二分次数与比分矩阵结构属于**模型定义**，刻意不做成配置项：同一份快照在任何环境
 * 必须解出同一组 λ，否则批次指纹的可复现性失效；要改域或改解法就是改模型，应当升版本号。</p>
 *
 * <p>这里开放的五项都是「数据质量阈值」或「精度/性能旋钮」，其默认值均有实测依据：
 * 邻线容差由 3 场真实抓包的最大偏差 3.508% 留 14% 余量得出；比分上限由 λ 总和最大 8.0 时
 * 的截断尾部质量标定（上限 16 时尾部 3.7e-3，上限 24 时降到 1.2e-6）；离散度上限由 3 场
 * 让球主盘 σ 实测 0.002~0.012 留余量得出；安全边际取 1%，对应实测主盘赔率下约 2.7% 的
 * 盈亏差（见设计文档 4.7）。</p>
 */
@Validated
@ConfigurationProperties("app.asian-model")
public record AsianModelProperties(
        BigDecimal neighborTolerance,
        BigDecimal residualTolerance,
        @Min(20) @Max(32) Integer maxGoals,
        @DecimalMin(value = "0.0000", inclusive = false) @DecimalMax("1.0000")
        BigDecimal dispersionTolerance,
        @DecimalMin("0.0000") @DecimalMax("1.0000") BigDecimal safetyMargin
) {

    /** 邻线回投校验的最大允许偏差，单位是概率（0.04 = 4 个百分点）。 */
    public static final BigDecimal DEFAULT_NEIGHBOR_TOLERANCE = new BigDecimal("0.0400");

    /**
     * 主盘两方程回投残差的兜底上限。
     *
     * <p>恰定方程组的残差在收敛后应接近 0（实测 1e-7），该阈值只用于拦截二分未收敛的异常，
     * 不承担「市场自洽性」判断——那由邻线校验负责。</p>
     */
    public static final BigDecimal DEFAULT_RESIDUAL_TOLERANCE = new BigDecimal("0.0010");

    /** 单边进球数矩阵上限；λ 总和最大可达 8.0，此值决定尾部截断误差。 */
    public static final int DEFAULT_MAX_GOALS = 24;

    /**
     * 跨庄分歧上限：同一条盘口线上各家去水概率的总体标准差。
     *
     * <p>超过即视为「市场自己打架」，该市场的定价不可信。实测 3 场让球主盘 σ 为
     * 0.0020 / 0.0118 / 0.0105，全部档位最大 0.0325，取 0.0200 不会误杀正常场次。</p>
     */
    public static final BigDecimal DEFAULT_DISPERSION_TOLERANCE = new BigDecimal("0.0200");

    /**
     * 影子门槛的安全边际，加到盈亏平衡概率上。
     *
     * <p>本阶段只记录不拦截：让球方向的 edge 在数学上恒为 0（λ 由该主盘概率反解），
     * 任何正边际都会拒发全部让球方向，详见设计文档 4.7.1。</p>
     */
    public static final BigDecimal DEFAULT_SAFETY_MARGIN = new BigDecimal("0.0100");

    public AsianModelProperties {
        neighborTolerance = neighborTolerance == null ? DEFAULT_NEIGHBOR_TOLERANCE : neighborTolerance;
        residualTolerance = residualTolerance == null ? DEFAULT_RESIDUAL_TOLERANCE : residualTolerance;
        maxGoals = maxGoals == null ? DEFAULT_MAX_GOALS : maxGoals;
        dispersionTolerance =
                dispersionTolerance == null ? DEFAULT_DISPERSION_TOLERANCE : dispersionTolerance;
        safetyMargin = safetyMargin == null ? DEFAULT_SAFETY_MARGIN : safetyMargin;
    }

    /** 未配置时的默认值，供单元测试与非 Spring 装配路径使用。 */
    public static AsianModelProperties defaults() {
        return new AsianModelProperties(
                DEFAULT_NEIGHBOR_TOLERANCE, DEFAULT_RESIDUAL_TOLERANCE, DEFAULT_MAX_GOALS,
                DEFAULT_DISPERSION_TOLERANCE, DEFAULT_SAFETY_MARGIN);
    }

    @Override
    public String toString() {
        return "AsianModelProperties["
                + "neighborTolerance=" + neighborTolerance
                + ", residualTolerance=" + residualTolerance
                + ", maxGoals=" + maxGoals
                + ", dispersionTolerance=" + dispersionTolerance
                + ", safetyMargin=" + safetyMargin
                + "]";
    }
}
