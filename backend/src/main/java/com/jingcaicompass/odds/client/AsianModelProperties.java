package com.jingcaicompass.odds.client;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 亚盘 λ 反解模型与置信度判定的运行参数。
 *
 * <p>搜索域、二分次数与比分矩阵结构属于**模型定义**，刻意不做成配置项：同一份快照在任何环境
 * 必须解出同一组 λ，否则批次指纹的可复现性失效；要改域或改解法就是改模型，应当升版本号。</p>
 *
 * <p>这里开放的九项都是「数据质量阈值」、「判定阈值」或「精度/性能旋钮」，其默认值均有实测依据：
 * 邻线容差由 3 场真实抓包的最大偏差 3.508% 留 14% 余量得出；比分上限由 λ 总和最大 8.0 时
 * 的截断尾部质量标定（上限 16 时尾部 3.7e-3，上限 24 时降到 1.2e-6）；离散度上限由 3 场
 * 让球主盘 σ 实测 0.002~0.012 留余量得出；安全边际取 1%，对应实测主盘赔率下约 2.7% 的
 * 盈亏差（见设计文档 4.7）。</p>
 *
 * <p>四个置信度阈值原本硬编码在 {@code BaselinePredictionGenerationServiceImpl} 里，抽到配置是
 * 为了让历史回测能在同一批场次上比较不同阈值，而不需要复制判定公式。**默认值必须与原先的
 * 常量逐字相等**，否则等于悄悄改了线上判定口径。</p>
 */
@Validated
@ConfigurationProperties("app.asian-model")
public record AsianModelProperties(
        BigDecimal neighborTolerance,
        BigDecimal residualTolerance,
        @Min(20) @Max(32) Integer maxGoals,
        @DecimalMin(value = "0.0000", inclusive = false) @DecimalMax("1.0000")
        BigDecimal dispersionTolerance,
        @DecimalMin("0.0000") @DecimalMax("1.0000") BigDecimal safetyMargin,
        @DecimalMin("0.0000") @DecimalMax("1.0000") BigDecimal highConfidenceMargin,
        @DecimalMin("0.0000") @DecimalMax("1.0000") BigDecimal mediumConfidenceMargin,
        @DecimalMin("0.0000") @DecimalMax("1.0000") BigDecimal highDirectionSignal,
        @DecimalMin("0.0000") @DecimalMax("1.0000") BigDecimal mediumDirectionSignal
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

    /**
     * HIGH 档允许的抽水上限：{@code margin <= 该值}。
     *
     * <p>语义容易读反：margin 是「越平衡越大」的抽水率，所以这是**上限**，越严越小。</p>
     */
    public static final BigDecimal DEFAULT_HIGH_CONFIDENCE_MARGIN = new BigDecimal("0.1000");

    /** MEDIUM 档允许的抽水上限。 */
    public static final BigDecimal DEFAULT_MEDIUM_CONFIDENCE_MARGIN = new BigDecimal("0.1600");

    /** HIGH 档要求的最小方向信号强度。 */
    public static final BigDecimal DEFAULT_HIGH_DIRECTION_SIGNAL = new BigDecimal("0.0750");

    /** MEDIUM 档要求的最小方向信号强度。 */
    public static final BigDecimal DEFAULT_MEDIUM_DIRECTION_SIGNAL = new BigDecimal("0.0250");

    public AsianModelProperties {
        neighborTolerance = neighborTolerance == null ? DEFAULT_NEIGHBOR_TOLERANCE : neighborTolerance;
        residualTolerance = residualTolerance == null ? DEFAULT_RESIDUAL_TOLERANCE : residualTolerance;
        maxGoals = maxGoals == null ? DEFAULT_MAX_GOALS : maxGoals;
        dispersionTolerance =
                dispersionTolerance == null ? DEFAULT_DISPERSION_TOLERANCE : dispersionTolerance;
        safetyMargin = safetyMargin == null ? DEFAULT_SAFETY_MARGIN : safetyMargin;
        highConfidenceMargin =
                highConfidenceMargin == null ? DEFAULT_HIGH_CONFIDENCE_MARGIN : highConfidenceMargin;
        mediumConfidenceMargin =
                mediumConfidenceMargin == null ? DEFAULT_MEDIUM_CONFIDENCE_MARGIN : mediumConfidenceMargin;
        highDirectionSignal =
                highDirectionSignal == null ? DEFAULT_HIGH_DIRECTION_SIGNAL : highDirectionSignal;
        mediumDirectionSignal =
                mediumDirectionSignal == null ? DEFAULT_MEDIUM_DIRECTION_SIGNAL : mediumDirectionSignal;

        // 两档阈值的包含关系必须成立，否则 HIGH 档永远不可达（或 MEDIUM 档形同虚设）而不会报错。
        // 放在紧凑构造器而不是 @AssertTrue：回测直接 new 这个 record，不经过 Spring 的校验器。
        if (highDirectionSignal.compareTo(mediumDirectionSignal) < 0) {
            throw new IllegalArgumentException(
                    "highDirectionSignal (" + highDirectionSignal.toPlainString()
                            + ") must be >= mediumDirectionSignal (" + mediumDirectionSignal.toPlainString() + ")");
        }
        if (highConfidenceMargin.compareTo(mediumConfidenceMargin) > 0) {
            throw new IllegalArgumentException(
                    "highConfidenceMargin (" + highConfidenceMargin.toPlainString()
                            + ") must be <= mediumConfidenceMargin (" + mediumConfidenceMargin.toPlainString() + ")");
        }
    }

    /**
     * 未配置时的默认值，供单元测试与非 Spring 装配路径使用。
     *
     * <p>**不要**再给这个 record 加第二个构造器：{@code @ConfigurationProperties} 绑定 record 时
     * 要求只有一个构造器，多一个就会退化成「找不到默认构造器」，启动直接失败（已踩过一次）。</p>
     */
    public static AsianModelProperties defaults() {
        return new AsianModelProperties(
                DEFAULT_NEIGHBOR_TOLERANCE, DEFAULT_RESIDUAL_TOLERANCE, DEFAULT_MAX_GOALS,
                DEFAULT_DISPERSION_TOLERANCE, DEFAULT_SAFETY_MARGIN,
                DEFAULT_HIGH_CONFIDENCE_MARGIN, DEFAULT_MEDIUM_CONFIDENCE_MARGIN,
                DEFAULT_HIGH_DIRECTION_SIGNAL, DEFAULT_MEDIUM_DIRECTION_SIGNAL);
    }

    /**
     * 用一组置信度阈值替换现有值，其余参数保持不变。
     *
     * <p>回测按参数组生成模型配置时用，避免逐个字段抄写导致漏项。</p>
     */
    public AsianModelProperties withConfidenceThresholds(
            BigDecimal highConfidenceMargin,
            BigDecimal mediumConfidenceMargin,
            BigDecimal highDirectionSignal,
            BigDecimal mediumDirectionSignal
    ) {
        return new AsianModelProperties(
                neighborTolerance, residualTolerance, maxGoals, dispersionTolerance, safetyMargin,
                highConfidenceMargin, mediumConfidenceMargin, highDirectionSignal, mediumDirectionSignal);
    }

    @Override
    public String toString() {
        return "AsianModelProperties["
                + "neighborTolerance=" + neighborTolerance
                + ", residualTolerance=" + residualTolerance
                + ", maxGoals=" + maxGoals
                + ", dispersionTolerance=" + dispersionTolerance
                + ", safetyMargin=" + safetyMargin
                + ", highConfidenceMargin=" + highConfidenceMargin
                + ", mediumConfidenceMargin=" + mediumConfidenceMargin
                + ", highDirectionSignal=" + highDirectionSignal
                + ", mediumDirectionSignal=" + mediumDirectionSignal
                + "]";
    }
}
