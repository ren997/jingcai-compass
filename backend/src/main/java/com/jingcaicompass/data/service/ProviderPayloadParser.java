package com.jingcaicompass.data.service;

import com.jingcaicompass.data.dto.ProviderFetchResult;
import com.jingcaicompass.data.dto.ProviderParseResult;
import com.jingcaicompass.data.entity.RawDataPayload;
import com.jingcaicompass.data.enums.ProviderDataTypeEnum;

/**
 * Provider 原始载荷领域解析回调。
 */
@FunctionalInterface
public interface ProviderPayloadParser {

    ProviderParseResult parse(
            ProviderDataTypeEnum dataType,
            String requestKey,
            RawDataPayload payload
    );
}
