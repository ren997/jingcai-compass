package com.jingcaicompass.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.jingcaicompass.admin.dto.AdminSyncRunDetailQueryDto;
import com.jingcaicompass.admin.dto.AdminSyncRunQuotaSummaryQueryDto;
import com.jingcaicompass.admin.vo.AdminSyncRunDetailVo;
import com.jingcaicompass.admin.vo.AdminSyncRunQuotaSummaryVo;
import com.jingcaicompass.data.entity.DataSyncRun;
import com.jingcaicompass.data.entity.RawDataPayload;
import com.jingcaicompass.data.enums.ParseStatusEnum;
import com.jingcaicompass.data.enums.ProviderDataTypeEnum;
import com.jingcaicompass.data.enums.SyncStatusEnum;
import com.jingcaicompass.data.mapper.DataSyncRunMapper;
import com.jingcaicompass.data.mapper.RawDataPayloadMapper;
import com.jingcaicompass.match.client.SportteryProviderProperties;
import com.jingcaicompass.match.client.SportteryProviderType;
import com.jingcaicompass.odds.client.AsianOddsProviderProperties;
import com.jingcaicompass.odds.enums.AsianOddsProviderTypeEnum;
import com.jingcaicompass.system.config.properties.PaginationProperties;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminSyncRunQueryServiceTest {

    @Mock
    private DataSyncRunMapper dataSyncRunMapper;

    @Mock
    private RawDataPayloadMapper rawDataPayloadMapper;

    private AdminSyncRunQueryServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = new AdminSyncRunQueryServiceImpl(
                dataSyncRunMapper,
                rawDataPayloadMapper,
                new PaginationProperties(100),
                new SportteryProviderProperties(SportteryProviderType.STUB, URI.create("https://sporttery.test"),
                        Duration.ofSeconds(1), Duration.ofSeconds(1),
                        new SportteryProviderProperties.RetryProperties(1, Duration.ofMillis(100)), 3),
                new AsianOddsProviderProperties(AsianOddsProviderTypeEnum.STUB, URI.create("https://odds.test"), "secret",
                        Duration.ofSeconds(1), Duration.ofSeconds(1),
                        new AsianOddsProviderProperties.RetryProperties(1, Duration.ofMillis(100)), 5),
                new AdminSensitiveDataSanitizer(new com.fasterxml.jackson.databind.ObjectMapper()),
                Clock.fixed(Instant.parse("2026-07-28T01:00:00Z"), ZoneOffset.UTC)
        );
    }

    @Test
    void detailShowsOnlyMaskedExactlyLinkedPayloads() {
        DataSyncRun run = run(7L, "THE_ODDS_API", ProviderDataTypeEnum.ASIAN_ODDS, 4);
        RawDataPayload payload = new RawDataPayload();
        payload.setId(9L);
        payload.setRequestKey("https://provider.test/feed?apiKey=hidden");
        payload.setPayload(Map.of("token", "must-not-leak", "nested", Map.of("cookie", "secret-cookie")));
        payload.setPayloadHash("a".repeat(64));
        payload.setParseStatus(ParseStatusEnum.SUCCESS);
        payload.setRequestedAt(Instant.parse("2026-07-28T00:00:00Z"));
        when(dataSyncRunMapper.selectById(7L)).thenReturn(run);
        when(rawDataPayloadMapper.selectBySyncRunId(7L)).thenReturn(List.of(payload));

        AdminSyncRunDetailVo detail = service.detail(new AdminSyncRunDetailQueryDto(7L));

        assertThat(detail.rawPayloadNotice()).isNull();
        assertThat(detail.rawPayloads()).singleElement().satisfies(snippet -> {
            assertThat(snippet.maskedJsonFragment()).contains("\"token\":\"***\"");
            assertThat(snippet.maskedJsonFragment()).doesNotContain("must-not-leak", "secret-cookie");
            assertThat(snippet.requestKey()).doesNotContain("apiKey=hidden");
        });
    }

    @Test
    void detailDoesNotGuessPayloadForHistoricalRun() {
        when(dataSyncRunMapper.selectById(8L)).thenReturn(run(8L, "STUB", ProviderDataTypeEnum.SPORTTERY_POOL, 0));
        when(rawDataPayloadMapper.selectBySyncRunId(8L)).thenReturn(List.of());

        assertThat(service.detail(new AdminSyncRunDetailQueryDto(8L)).rawPayloadNotice())
                .contains("不会按时间窗口推断");
        assertThatThrownBy(() -> service.detail(new AdminSyncRunDetailQueryDto(999L)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SYNC_RUN_NOT_FOUND);
    }

    @Test
    void quotaUsesShanghaiDateAndConfiguredWarningThresholdWithoutRemainingClaim() {
        when(dataSyncRunMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                run(1L, "STUB", ProviderDataTypeEnum.SPORTTERY_POOL, 3),
                run(2L, "THE_ODDS_API", ProviderDataTypeEnum.ASIAN_ODDS, 5)
        ));

        AdminSyncRunQuotaSummaryVo result = service.quotaSummary(
                new AdminSyncRunQuotaSummaryQueryDto(LocalDate.of(2026, 7, 28))
        );

        assertThat(result.items()).extracting(item -> item.warningThreshold()).containsExactlyInAnyOrder(3, 5);
        assertThat(result.items()).allSatisfy(item -> assertThat(item.warningTriggered()).isTrue());
    }

    private DataSyncRun run(Long id, String provider, ProviderDataTypeEnum dataType, int quota) {
        DataSyncRun run = new DataSyncRun();
        run.setId(id);
        run.setProviderCode(provider);
        run.setDataType(dataType);
        run.setSyncStatus(SyncStatusEnum.SUCCESS);
        run.setStartedAt(Instant.parse("2026-07-28T00:00:00Z"));
        run.setQuotaCost(quota);
        run.setFetchedCount(1);
        run.setSuccessCount(1);
        run.setFailureCount(0);
        run.setRetryCount(0);
        return run;
    }
}
