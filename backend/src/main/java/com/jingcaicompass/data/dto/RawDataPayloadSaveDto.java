package com.jingcaicompass.data.dto;

import com.jingcaicompass.data.enums.ProviderDataTypeEnum;

import java.time.Instant;

/**
 * 原始响应入库请求。
 */
public record RawDataPayloadSaveDto(
        /** Provider 业务编码。 */
        String providerCode,
        /** 数据类型。 */
        ProviderDataTypeEnum dataType,
        /** 请求幂等键。 */
        String requestKey,
        /** 原始 JSON 文本。 */
        String payloadJson,
        /** HTTP 状态码，可空。 */
        Integer httpStatus,
        /** 供应商侧更新时间，可空。 */
        Instant providerUpdatedAt,
        /** 发起请求时间。 */
        Instant requestedAt
) {
}
