package com.jingcaicompass.data.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.data.dto.RawDataPayloadSaveDto;
import com.jingcaicompass.data.dto.RawDataPayloadSaveResult;
import com.jingcaicompass.data.entity.RawDataPayload;
import com.jingcaicompass.data.enums.ParseStatusEnum;
import com.jingcaicompass.data.enums.ProviderDataTypeEnum;
import com.jingcaicompass.data.mapper.RawDataPayloadMapper;
import com.jingcaicompass.data.support.PayloadHashSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RawDataPayloadServiceTest {

    @Mock
    private RawDataPayloadMapper baseMapper;

    private RawDataPayloadServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RawDataPayloadServiceImpl(new ObjectMapper().findAndRegisterModules());
        ReflectionTestUtils.setField(service, "baseMapper", baseMapper);
    }

    @Test
    void savePayloadHashesAndPersistsPendingRecord() {
        when(baseMapper.selectOne(any())).thenReturn(null);
        when(baseMapper.insert(any(RawDataPayload.class))).thenAnswer(invocation -> {
            RawDataPayload payload = invocation.getArgument(0);
            payload.setId(101L);
            return 1;
        });

        String json = "{\"matches\":[{\"id\":\"1\"}]}";
        RawDataPayloadSaveResult result = service.savePayload(new RawDataPayloadSaveDto(
                "STUB",
                ProviderDataTypeEnum.SPORTTERY_POOL,
                "2026-07-22",
                json,
                200,
                Instant.parse("2026-07-22T12:00:00Z"),
                Instant.parse("2026-07-22T12:01:00Z")
        ));

        assertThat(result.duplicate()).isFalse();
        assertThat(result.payload().getId()).isEqualTo(101L);
        assertThat(result.payload().getPayloadHash()).isEqualTo(PayloadHashSupport.sha256Hex(json));
        assertThat(result.payload().getParseStatus()).isEqualTo(ParseStatusEnum.PENDING);
        assertThat(result.payload().getPayload()).containsEntry("matches", List.of(Map.of("id", "1")));
        verify(baseMapper).insert(any(RawDataPayload.class));
    }

    @Test
    void savePayloadReturnsExistingDuplicateWithoutInsert() {
        RawDataPayload existing = new RawDataPayload();
        existing.setId(202L);
        existing.setProviderCode("STUB");
        existing.setDataType(ProviderDataTypeEnum.SPORTTERY_POOL);
        existing.setRequestKey("2026-07-22");
        existing.setPayloadHash(PayloadHashSupport.sha256Hex("{\"ok\":true}"));
        existing.setParseStatus(ParseStatusEnum.SUCCESS);
        when(baseMapper.selectOne(any())).thenReturn(existing);

        RawDataPayloadSaveResult result = service.savePayload(new RawDataPayloadSaveDto(
                "STUB",
                ProviderDataTypeEnum.SPORTTERY_POOL,
                "2026-07-22",
                "{\"ok\":true}",
                200,
                null,
                Instant.now()
        ));

        assertThat(result.duplicate()).isTrue();
        assertThat(result.payload().getId()).isEqualTo(202L);
        verify(baseMapper, never()).insert(any(RawDataPayload.class));
    }

    @Test
    void markParseFailedAppendsErrorAndKeepsPayload() {
        RawDataPayload payload = new RawDataPayload();
        payload.setId(303L);
        payload.setParseStatus(ParseStatusEnum.PENDING);
        payload.setParseError("first");
        when(baseMapper.selectById(303L)).thenReturn(payload);
        when(baseMapper.updateById(any(RawDataPayload.class))).thenReturn(1);

        RawDataPayload updated = service.markParseFailed(303L, "second");

        assertThat(updated.getParseStatus()).isEqualTo(ParseStatusEnum.FAILED);
        assertThat(updated.getParseError()).isEqualTo("first; second");

        ArgumentCaptor<RawDataPayload> captor = ArgumentCaptor.forClass(RawDataPayload.class);
        verify(baseMapper).updateById(captor.capture());
        assertThat(captor.getValue().getParseError()).isEqualTo("first; second");
    }

    @Test
    void markParseSuccessClearsError() {
        RawDataPayload payload = new RawDataPayload();
        payload.setId(304L);
        payload.setParseStatus(ParseStatusEnum.FAILED);
        payload.setParseError("old");
        when(baseMapper.selectById(304L)).thenReturn(payload);
        when(baseMapper.updateById(any(RawDataPayload.class))).thenReturn(1);

        RawDataPayload updated = service.markParseSuccess(304L);

        assertThat(updated.getParseStatus()).isEqualTo(ParseStatusEnum.SUCCESS);
        assertThat(updated.getParseError()).isNull();
    }
}
