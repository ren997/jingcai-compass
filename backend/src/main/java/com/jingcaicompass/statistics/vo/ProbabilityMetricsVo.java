package com.jingcaicompass.statistics.vo;

import com.jingcaicompass.statistics.enums.ProbabilityMetricUnavailableReasonEnum;
import java.math.BigDecimal;
import java.util.List;

/** 当前 FINAL 事实上的 HAD 三分类概率指标。 */
public record ProbabilityMetricsVo(
        long sampleSize,
        BigDecimal brierScore,
        BigDecimal logLoss,
        List<ProbabilityMetricUnavailableReasonEnum> unavailableReasons
) {
    public ProbabilityMetricsVo {
        unavailableReasons = List.copyOf(unavailableReasons);
    }
}
