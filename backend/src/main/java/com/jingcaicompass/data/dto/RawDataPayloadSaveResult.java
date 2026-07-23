package com.jingcaicompass.data.dto;

import com.jingcaicompass.data.entity.RawDataPayload;

/**
 * 原始响应入库结果。
 */
public record RawDataPayloadSaveResult(
        /** 已保存或已存在的原始响应。 */
        RawDataPayload payload,
        /** 是否命中幂等键而未插入新行。 */
        boolean duplicate
) {
}
