package com.jingcaicompass.data.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.jingcaicompass.data.dto.RawDataPayloadSaveDto;
import com.jingcaicompass.data.dto.RawDataPayloadSaveResult;
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

    /**
     * 计算哈希并保存原始响应；重复时返回已有记录且不插入。
     */
    RawDataPayloadSaveResult savePayload(RawDataPayloadSaveDto request);

    /**
     * 标记解析成功。
     */
    RawDataPayload markParseSuccess(Long payloadId);

    /**
     * 标记解析失败并记录错误信息。
     */
    RawDataPayload markParseFailed(Long payloadId, String parseError);
}
