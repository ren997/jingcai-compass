package com.jingcaicompass.match.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
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
import com.jingcaicompass.match.dto.MatchResultSyncRequestDto;
import com.jingcaicompass.match.dto.MatchResultSyncResultDto;
import com.jingcaicompass.match.dto.SportteryMatchResultDto;
import com.jingcaicompass.match.dto.SportteryMatchResultPayloadDto;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** raw 存档后的赛果解析、去重和单场失败隔离测试。 */
@ExtendWith(MockitoExtension.class)
class MatchResultSyncServiceTest {

    private static final LocalDate LOTTERY_DATE = LocalDate.of(2026, 7, 22);

    @Mock
    private SportteryProvider sportteryProvider;

    @Mock
    private ProviderSyncTemplate providerSyncTemplate;

    @Mock
    private MatchResultFactWriter factWriter;

    private ObjectMapper objectMapper;
    private MatchResultSyncServiceImpl service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new MatchResultSyncServiceImpl(
                sportteryProvider,
                providerSyncTemplate,
                new SportteryMatchResultPayloadMapper(objectMapper),
                factWriter,
                objectMapper
        );
    }

    @Test
    void savesAndParsesRawPayloadThenUsesOnlyLatestSameMatchResult() throws Exception {
        ProviderFetchResult fetchResult = new ProviderFetchResult(
                LOTTERY_DATE + ":" + LOTTERY_DATE,
                payloadJson(List.of(
                        result("周三001", MatchStatusEnum.FINISHED, 2, 1, false, false,
                                "2026-07-22T23:30:00+08:00"),
                        result("周三001", MatchStatusEnum.FINISHED, 1, 1, true, false,
                                "2026-07-23T10:00:00+08:00"),
                        result("周三002", MatchStatusEnum.POSTPONED, null, null, false, false,
                                "2026-07-22T20:00:00+08:00")
                )),
                200,
                Instant.parse("2026-07-23T02:00:00Z"),
                0,
                0
        );
        when(sportteryProvider.providerCode()).thenReturn("STUB");
        when(sportteryProvider.fetchMatchResultsRaw(LOTTERY_DATE, LOTTERY_DATE)).thenReturn(fetchResult);
        when(factWriter.write(any(), eq(71L))).thenAnswer(invocation -> {
            SportteryMatchResultDto item = invocation.getArgument(0);
            return "周三001".equals(item.lotteryMatchNo())
                    ? new MatchResultFactWriter.WriteResult(MatchResultFactWriter.WriteOutcome.SUPERSEDED, 501L)
                    : new MatchResultFactWriter.WriteResult(MatchResultFactWriter.WriteOutcome.APPENDED, 502L);
        });

        ProviderSyncOutcome outcome = configureTemplateToUseRawPayload(fetchResult);
        MatchResultSyncResultDto result = service.sync(new MatchResultSyncRequestDto(LOTTERY_DATE, LOTTERY_DATE));

        assertThat(result.outcome()).isSameAs(outcome);
        assertThat(result.appendedFactCount()).isEqualTo(2);
        assertThat(result.supersededFactCount()).isEqualTo(1);
        assertThat(result.unchangedFactCount()).isZero();
        ArgumentCaptor<SportteryMatchResultDto> itemCaptor = ArgumentCaptor.forClass(SportteryMatchResultDto.class);
        verify(factWriter, org.mockito.Mockito.times(2)).write(itemCaptor.capture(), eq(71L));
        assertThat(itemCaptor.getAllValues())
                .extracting(SportteryMatchResultDto::lotteryMatchNo)
                .containsExactly("周三001", "周三002");
        assertThat(itemCaptor.getAllValues().getFirst().homeScore()).isEqualTo(1);
        assertThat(itemCaptor.getAllValues().getFirst().awayScore()).isEqualTo(1);
        verify(sportteryProvider).fetchMatchResultsRaw(LOTTERY_DATE, LOTTERY_DATE);
    }

    @Test
    void retainsOtherMatchesWhenOneWriterRejectsAnItem() throws Exception {
        ProviderFetchResult fetchResult = new ProviderFetchResult(
                "request",
                payloadJson(List.of(
                        result("周三001", MatchStatusEnum.FINISHED, 2, 1, false, false,
                                "2026-07-22T23:30:00+08:00"),
                        result("周三002", MatchStatusEnum.CANCELLED, null, null, false, false,
                                "2026-07-22T20:00:00+08:00")
                )),
                200,
                Instant.now(),
                0,
                0
        );
        when(sportteryProvider.providerCode()).thenReturn("STUB");
        when(sportteryProvider.fetchMatchResultsRaw(LOTTERY_DATE, LOTTERY_DATE)).thenReturn(fetchResult);
        when(factWriter.write(any(), eq(71L))).thenAnswer(invocation -> {
            SportteryMatchResultDto item = invocation.getArgument(0);
            if ("周三001".equals(item.lotteryMatchNo())) {
                throw new IllegalArgumentException("unknown match");
            }
            return new MatchResultFactWriter.WriteResult(MatchResultFactWriter.WriteOutcome.APPENDED, 502L);
        });
        configureTemplateToUseRawPayload(fetchResult);

        MatchResultSyncResultDto result = service.sync(new MatchResultSyncRequestDto(LOTTERY_DATE, LOTTERY_DATE));

        assertThat(result.appendedFactCount()).isEqualTo(1);
        assertThat(result.supersededFactCount()).isZero();
        verify(factWriter, org.mockito.Mockito.times(2)).write(any(), eq(71L));
    }

    @Test
    void rejectsMissingOrReversedDateRangeBeforeProviderCall() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.sync(null))
                .withMessageContaining("startDate");
        assertThatIllegalArgumentException().isThrownBy(() -> service.sync(
                new MatchResultSyncRequestDto(LOTTERY_DATE, LOTTERY_DATE.minusDays(1))
        )).withMessageContaining("endDate");
    }

    private ProviderSyncOutcome configureTemplateToUseRawPayload(ProviderFetchResult fetchResult) throws Exception {
        RawDataPayload payload = new RawDataPayload();
        payload.setId(71L);
        payload.setPayload(objectMapper.readValue(fetchResult.payloadJson(), LinkedHashMap.class));
        DataSyncRun run = new DataSyncRun();
        run.setId(31L);
        ProviderSyncOutcome outcome = new ProviderSyncOutcome(run, payload, SyncStatusEnum.SUCCESS, false);
        when(providerSyncTemplate.execute(
                eq("STUB"),
                eq(ProviderDataTypeEnum.SPORTTERY_RESULT),
                any(ProviderPayloadFetcher.class),
                any(ProviderPayloadParser.class)
        )).thenAnswer(invocation -> {
            ProviderPayloadFetcher fetcher = invocation.getArgument(2);
            ProviderPayloadParser parser = invocation.getArgument(3);
            assertThat(fetcher.fetch()).isEqualTo(fetchResult);
            ProviderParseResult parseResult = parser.parse(
                    ProviderDataTypeEnum.SPORTTERY_RESULT,
                    fetchResult.requestKey(),
                    payload
            );
            assertThat(parseResult.successCount() + parseResult.failureCount()).isPositive();
            return outcome;
        });
        return outcome;
    }

    private String payloadJson(List<SportteryMatchResultDto> results) throws Exception {
        return objectMapper.writeValueAsString(new SportteryMatchResultPayloadDto(results));
    }

    private static SportteryMatchResultDto result(
            String matchNo,
            MatchStatusEnum status,
            Integer homeScore,
            Integer awayScore,
            boolean amended,
            boolean officialVoid,
            String providerUpdatedAt
    ) {
        return new SportteryMatchResultDto(
                "stub-" + matchNo,
                LOTTERY_DATE,
                matchNo,
                homeScore,
                awayScore,
                status,
                amended,
                officialVoid,
                OffsetDateTime.parse(providerUpdatedAt)
        );
    }
}
