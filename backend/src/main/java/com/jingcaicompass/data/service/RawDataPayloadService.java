package com.jingcaicompass.data.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.jingcaicompass.data.entity.RawDataPayload;
import com.jingcaicompass.data.enums.ProviderDataTypeEnum;

public interface RawDataPayloadService extends IService<RawDataPayload> {

    /**
     * 按幂等键查询是否已存在相同哈希的原始响应。
     */
    boolean existsDuplicate(
            String providerCode,
            ProviderDataTypeEnum dataType,
            String requestKey,
            String payloadHash
    );
}
