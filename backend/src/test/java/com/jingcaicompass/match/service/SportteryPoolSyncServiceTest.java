package com.jingcaicompass.match.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.data.dto.ProviderFetchResult;
import com.jingcaicompass.data.dto.ProviderParseResult;
import com.jingcaicompass.data.dto.ProviderSyncOutcome;
import com.jingcaicompass.data.entity.DataSyncRun;
import com.jingcaicompass.data.entity.RawDataPayload;
import com.jingcaicompass.data.enums.ProviderDataTypeEnum;
import com.jingcaicompass.data.enums.SyncStatusEnum;
import com.jingcaicompass.data.service.ProviderPayloadFetcher;
import com.jingcaicompass.data.service.ProviderPayloadParser;
import com.jingcaicompass.data.service.ProviderSyncTemplate;
import com.jingcaicompass.match.dto.SportteryPoolSyncRequestDto;
import com.jingcaicompass.match.dto.SportteryPoolSyncResultDto;
import com.jingcaicompass.system.stub.StubFixtureLoader;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SportteryPoolSyncServiceTest {

    @Mock
    private SportteryProvider sportteryProvider;

    @Mock
    private ProviderSyncTemplate providerSyncTemplate;

    @Mock
    private SportteryPoolMatchWriter matchWriter;

    private SportteryPoolSyncServiceImpl service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new SportteryPoolSyncServiceImpl(
                sportteryProvider,
                providerSyncTemplate,
                new SportteryPoolPayloadMapper(objectMapper),
                matchWriter,
                objectMapper
        );
    }

    @Test
    void syncDelegatesToTemplateAndReturnsCounts() throws Exception {
        LocalDate businessDate = LocalDate.of(2026, 7, 22);
        when(sportteryProvider.providerCode()).thenReturn("STUB");
        when(matchWriter.writeAll(any(), any())).thenReturn(
                new SportteryPoolMatchWriter.WriteResult(new ProviderParseResult(2, 0, null), 2, 2)
        );

        DataSyncRun run = new DataSyncRun();
        run.setId(9L);
        when(providerSyncTemplate.execute(
                eq("STUB"),
                eq(ProviderDataTypeEnum.SPORTTERY_POOL),
                any(ProviderPayloadFetcher.class),
                any(ProviderPayloadParser.class)
        )).thenAnswer(invocation -> {
            ProviderPayloadFetcher fetcher = invocation.getArgument(2);
            ProviderPayloadParser parser = invocation.getArgument(3);
            ProviderFetchResult fetchResult = new ProviderFetchResult(
                    businessDate.toString(),
                    StubFixtureLoader.readText("stub/sporttery/pool-raw-normal.json"),
                    200,
                    Instant.now(),
                    0,
                    0
            );
            when(sportteryProvider.fetchMatchPoolRaw(businessDate)).thenReturn(fetchResult);
            fetcher.fetch();

            RawDataPayload payload = new RawDataPayload();
            payload.setPayloadHash("f".repeat(64));
            payload.setPayload(objectMapper.readValue(
                    StubFixtureLoader.readText("stub/sporttery/pool-raw-normal.json"),
                    LinkedHashMap.class
            ));
            ProviderParseResult parseResult = parser.parse(
                    ProviderDataTypeEnum.SPORTTERY_POOL,
                    businessDate.toString(),
                    payload
            );
            assertThat(parseResult.successCount()).isEqualTo(2);
            return new ProviderSyncOutcome(run, payload, SyncStatusEnum.SUCCESS, false);
        });

        SportteryPoolSyncResultDto result = service.sync(new SportteryPoolSyncRequestDto(businessDate));

        assertThat(result.outcome().status()).isEqualTo(SyncStatusEnum.SUCCESS);
        assertThat(result.matchUpsertCount()).isEqualTo(2);
        assertThat(result.snapshotInsertCount()).isEqualTo(2);
        verify(matchWriter).writeAll(any(), eq("f".repeat(64)));
    }

    @Test
    void emptyPoolReturnsZeroCounts() throws Exception {
        LocalDate businessDate = LocalDate.of(2026, 7, 22);
        when(sportteryProvider.providerCode()).thenReturn("STUB");
        when(matchWriter.writeAll(any(), any())).thenReturn(SportteryPoolMatchWriter.WriteResult.empty());

        when(providerSyncTemplate.execute(
                eq("STUB"),
                eq(ProviderDataTypeEnum.SPORTTERY_POOL),
                any(ProviderPayloadFetcher.class),
                any(ProviderPayloadParser.class)
        )).thenAnswer(invocation -> {
            ProviderPayloadParser parser = invocation.getArgument(3);
            RawDataPayload payload = new RawDataPayload();
            payload.setPayloadHash("0".repeat(64));
            payload.setPayload(Map.of(
                    "success", true,
                    "errorCode", "0",
                    "errorMessage", "ok",
                    "value", Map.of("matchInfoList", java.util.List.of())
            ));
            ProviderParseResult parseResult = parser.parse(
                    ProviderDataTypeEnum.SPORTTERY_POOL,
                    businessDate.toString(),
                    payload
            );
            assertThat(parseResult.successCount()).isEqualTo(0);
            return new ProviderSyncOutcome(new DataSyncRun(), payload, SyncStatusEnum.SUCCESS, false);
        });

        SportteryPoolSyncResultDto result = service.sync(new SportteryPoolSyncRequestDto(businessDate));
        assertThat(result.matchUpsertCount()).isZero();
        assertThat(result.snapshotInsertCount()).isZero();
    }
}
