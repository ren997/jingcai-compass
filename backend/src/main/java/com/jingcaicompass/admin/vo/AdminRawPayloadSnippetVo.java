package com.jingcaicompass.admin.vo;

import com.jingcaicompass.data.enums.ParseStatusEnum;
import java.time.Instant;

/** 经脱敏并限长的后台原始响应片段。 */
public record AdminRawPayloadSnippetVo(
        Long payloadId,
        String requestKey,
        Instant requestedAt,
        Instant providerUpdatedAt,
        Integer httpStatus,
        String payloadHash,
        ParseStatusEnum parseStatus,
        String parseErrorSummary,
        String maskedJsonFragment,
        boolean truncated
) {
}
