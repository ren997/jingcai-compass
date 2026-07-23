package com.jingcaicompass.data.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jingcaicompass.data.entity.RawDataPayload;
import com.jingcaicompass.data.enums.ProviderDataTypeEnum;
import com.jingcaicompass.data.mapper.RawDataPayloadMapper;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnBean(DataSource.class)
public class RawDataPayloadServiceImpl extends ServiceImpl<RawDataPayloadMapper, RawDataPayload>
        implements RawDataPayloadService {

    @Override
    public boolean existsDuplicate(
            String providerCode,
            ProviderDataTypeEnum dataType,
            String requestKey,
            String payloadHash
    ) {
        return lambdaQuery()
                .eq(RawDataPayload::getProviderCode, providerCode)
                .eq(RawDataPayload::getDataType, dataType)
                .eq(RawDataPayload::getRequestKey, requestKey)
                .eq(RawDataPayload::getPayloadHash, payloadHash)
                .exists();
    }
}
