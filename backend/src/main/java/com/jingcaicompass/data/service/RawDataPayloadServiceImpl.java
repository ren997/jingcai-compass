package com.jingcaicompass.data.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.data.dto.RawDataPayloadSaveDto;
import com.jingcaicompass.data.dto.RawDataPayloadSaveResult;
import com.jingcaicompass.data.entity.RawDataPayload;
import com.jingcaicompass.data.enums.ParseStatusEnum;
import com.jingcaicompass.data.enums.ProviderDataTypeEnum;
import com.jingcaicompass.data.mapper.RawDataPayloadMapper;
import com.jingcaicompass.data.support.PayloadHashSupport;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@ConditionalOnBean(DataSource.class)
public class RawDataPayloadServiceImpl extends ServiceImpl<RawDataPayloadMapper, RawDataPayload>
        implements RawDataPayloadService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public RawDataPayloadServiceImpl(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean existsDuplicate(
            String providerCode,
            ProviderDataTypeEnum dataType,
            String requestKey,
            String payloadHash
    ) {
        return baseMapper.selectCount(dedupeQuery(providerCode, dataType, requestKey, payloadHash)) > 0;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RawDataPayloadSaveResult savePayload(RawDataPayloadSaveDto request) {
        validateSaveRequest(request);
        String payloadHash = PayloadHashSupport.sha256Hex(request.payloadJson());
        RawDataPayload existing = findDuplicate(
                request.providerCode(),
                request.dataType(),
                request.requestKey(),
                payloadHash
        );
        if (existing != null) {
            return new RawDataPayloadSaveResult(existing, true);
        }

        RawDataPayload payload = new RawDataPayload();
        payload.setProviderCode(request.providerCode());
        payload.setDataType(request.dataType());
        payload.setRequestKey(request.requestKey());
        payload.setRequestedAt(request.requestedAt() != null ? request.requestedAt() : Instant.now());
        payload.setProviderUpdatedAt(request.providerUpdatedAt());
        payload.setHttpStatus(request.httpStatus());
        payload.setPayload(toPayloadMap(request.payloadJson()));
        payload.setPayloadHash(payloadHash);
        payload.setParseStatus(ParseStatusEnum.PENDING);
        save(payload);
        return new RawDataPayloadSaveResult(payload, false);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RawDataPayload markParseSuccess(Long payloadId) {
        RawDataPayload payload = requirePayload(payloadId);
        payload.setParseStatus(ParseStatusEnum.SUCCESS);
        payload.setParseError(null);
        updateById(payload);
        return payload;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RawDataPayload markParseFailed(Long payloadId, String parseError) {
        RawDataPayload payload = requirePayload(payloadId);
        payload.setParseStatus(ParseStatusEnum.FAILED);
        payload.setParseError(appendError(payload.getParseError(), parseError));
        updateById(payload);
        return payload;
    }

    private RawDataPayload findDuplicate(
            String providerCode,
            ProviderDataTypeEnum dataType,
            String requestKey,
            String payloadHash
    ) {
        return baseMapper.selectOne(dedupeQuery(providerCode, dataType, requestKey, payloadHash).last("LIMIT 1"));
    }

    private QueryWrapper<RawDataPayload> dedupeQuery(
            String providerCode,
            ProviderDataTypeEnum dataType,
            String requestKey,
            String payloadHash
    ) {
        return new QueryWrapper<RawDataPayload>()
                .eq("provider_code", providerCode)
                .eq("data_type", dataType)
                .eq("request_key", requestKey)
                .eq("payload_hash", payloadHash);
    }

    private RawDataPayload requirePayload(Long payloadId) {
        if (payloadId == null) {
            throw new IllegalArgumentException("payloadId must not be null");
        }
        RawDataPayload payload = getById(payloadId);
        if (payload == null) {
            throw new IllegalArgumentException("raw payload not found: " + payloadId);
        }
        return payload;
    }

    private void validateSaveRequest(RawDataPayloadSaveDto request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (!StringUtils.hasText(request.providerCode())) {
            throw new IllegalArgumentException("providerCode must not be blank");
        }
        if (request.dataType() == null) {
            throw new IllegalArgumentException("dataType must not be null");
        }
        if (!StringUtils.hasText(request.requestKey())) {
            throw new IllegalArgumentException("requestKey must not be blank");
        }
        if (!StringUtils.hasText(request.payloadJson())) {
            throw new IllegalArgumentException("payloadJson must not be blank");
        }
    }

    private Map<String, Object> toPayloadMap(String payloadJson) {
        try {
            Map<String, Object> map = objectMapper.readValue(payloadJson, MAP_TYPE);
            return map == null ? new LinkedHashMap<>() : new LinkedHashMap<>(map);
        } catch (JsonProcessingException exception) {
            // 非对象 JSON（数组/纯量）包一层，保证 JSONB Map 可存
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("raw", payloadJson);
            return wrapper;
        }
    }

    private String appendError(String existing, String incoming) {
        if (!StringUtils.hasText(incoming)) {
            return existing;
        }
        if (!StringUtils.hasText(existing)) {
            return incoming;
        }
        if (existing.contains(incoming)) {
            return existing;
        }
        return existing + "; " + incoming;
    }
}
