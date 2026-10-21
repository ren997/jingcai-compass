package com.jingcaicompass.statistics.vo;

import com.jingcaicompass.statistics.enums.ProbabilityMetricUnavailableReasonEnum;
import com.jingcaicompass.statistics.enums.ProbabilityScoringModeEnum;
import java.math.BigDecimal;
import java.util.List;

/**
 * 概率指标。随 {@code scoringMode} 切换语义，一次响应只有一套口径。
 *
 * <p>亚盘方向按结算状态连续化打分：{@code HIT=1 / HALF_WIN=0.75 / PUSH=0.5 / HALF_LOSS=0.25 / MISS=0}。
 * 走盘计 0.5 而不是剔除，是因为「押注既没赢也没输」本身就是模型给出的概率应当覆盖的情形；
 * 剔除会让 Brier 只反映分出胜负的子集，系统性偏向极端概率。
 *
 * <p>{@code brierAdvantage = marketBrierScore − brierScore}，正数表示模型优于市场。
 * 该符号约定与 {@code modelOutperformsMarket} 一律在后端判定：首页与统计页都要展示同一结论，
 * 若各自比较 6 位小数会在边界上给出相反的箭头。
 */
public record ProbabilityMetricsVo(
        long sampleSize,
        ProbabilityScoringModeEnum scoringMode,
        BigDecimal brierScore,
        BigDecimal logLoss,
        BigDecimal marketBrierScore,
        BigDecimal brierAdvantage,
        Boolean modelOutperformsMarket,
        List<ProbabilityMetricUnavailableReasonEnum> unavailableReasons
) {
    public ProbabilityMetricsVo {
        unavailableReasons = List.copyOf(unavailableReasons);
    }
}
