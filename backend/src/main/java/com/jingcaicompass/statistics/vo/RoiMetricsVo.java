package com.jingcaicompass.statistics.vo;

import com.jingcaicompass.statistics.enums.RoiUnavailableReasonEnum;
import java.math.BigDecimal;
import java.util.List;

/** 仅在完整赔率和固定下注口径下才可返回 ROI/Yield。 */
public record RoiMetricsVo(
        boolean available,
        BigDecimal roi,
        BigDecimal yield,
        long sampleSize,
        List<RoiUnavailableReasonEnum> unavailableReasons
) {
    public RoiMetricsVo {
        unavailableReasons = List.copyOf(unavailableReasons);
    }
}
