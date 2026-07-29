package com.jingcaicompass.odds.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.data.dto.ProviderFetchResult;
import com.jingcaicompass.data.dto.ProviderParseResult;
import com.jingcaicompass.data.dto.ProviderSyncOutcome;
import com.jingcaicompass.data.entity.DataSyncRun;
import com.jingcaicompass.data.entity.RawDataPayload;
import com.jingcaicompass.data.enums.ProviderDataTypeEnum;
import com.jingcaicompass.data.enums.SyncStatusEnum;
import com.jingcaicompass.data.mapper.DataSyncRunMapper;
import com.jingcaicompass.data.service.ProviderPayloadFetcher;
import com.jingcaicompass.data.service.ProviderPayloadParser;
import com.jingcaicompass.data.service.ProviderSyncTemplate;
import com.jingcaicompass.match.dto.MatchMapResultDto;
import com.jingcaicompass.match.entity.MatchEntity;
import com.jingcaicompass.match.enums.MatchMapOutcomeEnum;
import com.jingcaicompass.match.enums.MappingStatusEnum;
import com.jingcaicompass.match.mapper.MatchMapper;
import com.jingcaicompass.match.service.MatchMappingService;
import com.jingcaicompass.match.service.TeamNormalizationService;
import com.jingcaicompass.odds.client.AsianOddsProviderProperties;
import com.jingcaicompass.odds.dto.AsianOddsSyncRequestDto;
import com.jingcaicompass.odds.dto.AsianOddsSyncResultDto;
import com.jingcaicompass.odds.enums.AsianOddsProviderTypeEnum;
import com.jingcaicompass.odds.mapper.AsianOddsSnapshotMapper;
import com.jingcaicompass.system.provider.ProviderErrorCategory;
import com.jingcaicompass.system.provider.ProviderException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AsianOddsSyncServiceTest {

    @Mock
    private AsianOddsProvider asianOddsProvider;

    @Mock
    private ProviderSyncTemplate providerSyncTemplate;

    @Mock
    private AsianOddsSnapshotWriter snapshotWriter;

    @Mock
    private MatchMappingService matchMappingService;

    @Mock
    private TeamNormalizationService teamNormalizationService;

    @Mock
    private MatchMapper matchMapper;

    @Mock
    private AsianOddsSnapshotMapper asianOddsSnapshotMapper;

    @Mock
    private DataSyncRunMapper dataSyncRunMapper;

    private ObjectMapper objectMapper;
    private AsianOddsSyncServiceImpl service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        AsianOddsProviderProperties properties = new AsianOddsProviderProperties(
                AsianOddsProviderTypeEnum.STUB,
                URI.create("https://api.the-odds-api.com"),
                "",
                Duration.ofSeconds(5),
                Duration.ofSeconds(10),
                new AsianOddsProviderProperties.RetryProperties(2, Duration.ofMillis(500)),
                0
        );
        service = new AsianOddsSyncServiceImpl(
                asianOddsProvider,
                providerSyncTemplate,
                new AsianOddsPayloadMapper(objectMapper),
                snapshotWriter,
                matchMappingService,
                teamNormalizationService,
                matchMapper,
                asianOddsSnapshotMapper,
                dataSyncRunMapper,
                properties,
                objectMapper
        );
    }

    @Test
    void blocksTheOddsRequestBeforeItWouldExceedConfiguredBudget() {
        AsianOddsProviderProperties cappedProperties = new AsianOddsProviderProperties(
                AsianOddsProviderTypeEnum.THE_ODDS_API,
                URI.create("https://api.the-odds-api.com"),
                "",
                Duration.ofSeconds(5),
                Duration.ofSeconds(10),
                new AsianOddsProviderProperties.RetryProperties(2, Duration.ofMillis(500)),
                0,
                new AsianOddsProviderProperties.TheOddsProperties(Map.of("英超", "soccer_epl"), 400)
        );
        service = new AsianOddsSyncServiceImpl(
                asianOddsProvider,
                providerSyncTemplate,
                new AsianOddsPayloadMapper(objectMapper),
                snapshotWriter,
                matchMappingService,
                teamNormalizationService,
                matchMapper,
                asianOddsSnapshotMapper,
                dataSyncRunMapper,
                cappedProperties,
                objectMapper
        );
        MatchEntity match = new MatchEntity();
        match.setLeagueName("英超");
        match.setKickoffTime(Instant.parse("2026-07-22T12:00:00Z"));
        when(asianOddsProvider.providerCode()).thenReturn("THE_ODDS_API");
        when(asianOddsProvider.estimateQuotaCost(any())).thenReturn(2);
        when(matchMapper.selectList(any(Wrapper.class))).thenReturn(List.of(match));
        DataSyncRun prior = new DataSyncRun();
        prior.setQuotaCost(399);
        when(dataSyncRunMapper.selectList(any(Wrapper.class))).thenReturn(List.of(prior));
        DataSyncRun blockedRun = new DataSyncRun();
        blockedRun.setId(88L);
        blockedRun.setQuotaCost(0);
        when(providerSyncTemplate.execute(
                eq("THE_ODDS_API"),
                eq(ProviderDataTypeEnum.ASIAN_ODDS),
                any(ProviderPayloadFetcher.class),
                any(ProviderPayloadParser.class)
        )).thenAnswer(invocation -> {
            ProviderPayloadFetcher fetcher = invocation.getArgument(2);
            assertThatThrownBy(fetcher::fetch)
                    .isInstanceOf(ProviderException.class)
                    .satisfies(exception -> assertThat(((ProviderException) exception).category())
                            .isEqualTo(ProviderErrorCategory.QUOTA_EXCEEDED));
            return new ProviderSyncOutcome(blockedRun, null, SyncStatusEnum.FAILED, false);
        });

        AsianOddsSyncResultDto result = service.sync(new AsianOddsSyncRequestDto(LocalDate.of(2026, 7, 22)));

        assertThat(result.quotaBlocked()).isTrue();
        assertThat(result.quotaCostUsed()).isEqualTo(399);
        assertThat(result.outcome()).isNotNull();
        assertThat(result.unconfiguredLeagueMatchCount()).isZero();
        verify(asianOddsProvider).estimateQuotaCost(argThat(query ->
                query.sportKeys().equals(List.of("soccer_epl"))));
        verify(providerSyncTemplate).execute(
                eq("THE_ODDS_API"),
                eq(ProviderDataTypeEnum.ASIAN_ODDS),
                any(ProviderPayloadFetcher.class),
                any(ProviderPayloadParser.class)
        );
        verify(asianOddsProvider, never()).fetchPreMatchOddsRaw(any());
    }

    @Test
    void recordsUnconfiguredSportteryLeagueAsUncoveredInsteadOfCallingAHiddenDefault() {
        AsianOddsProviderProperties properties = new AsianOddsProviderProperties(
                AsianOddsProviderTypeEnum.THE_ODDS_API,
                URI.create("https://api.the-odds-api.com"),
                "",
                Duration.ofSeconds(5),
                Duration.ofSeconds(10),
                new AsianOddsProviderProperties.RetryProperties(2, Duration.ofMillis(500)),
                0,
                new AsianOddsProviderProperties.TheOddsProperties(Map.of(), 400)
        );
        service = new AsianOddsSyncServiceImpl(
                asianOddsProvider,
                providerSyncTemplate,
                new AsianOddsPayloadMapper(objectMapper),
                snapshotWriter,
                matchMappingService,
                teamNormalizationService,
                matchMapper,
                asianOddsSnapshotMapper,
                dataSyncRunMapper,
                properties,
                objectMapper
        );
        MatchEntity match = new MatchEntity();
        match.setLeagueName("未配置联赛");
        match.setKickoffTime(Instant.parse("2026-07-22T12:00:00Z"));
        when(asianOddsProvider.providerCode()).thenReturn("THE_ODDS_API");
        when(matchMapper.selectList(any(Wrapper.class))).thenReturn(List.of(match));
        when(dataSyncRunMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(providerSyncTemplate.execute(any(), any(), any(), any())).thenReturn(
                new ProviderSyncOutcome(null, null, SyncStatusEnum.SUCCESS, false));

        AsianOddsSyncResultDto result = service.sync(new AsianOddsSyncRequestDto(LocalDate.of(2026, 7, 22)));

        assertThat(result.unconfiguredLeagueMatchCount()).isEqualTo(1);
        assertThat(result.coverageRate()).isEqualByComparingTo("0.0000");
        verify(asianOddsProvider).estimateQuotaCost(argThat(query -> query.sportKeys().isEmpty()));
    }

    @Test
    void syncWritesOnlyConfirmedMappingsAndSkipsPending() throws Exception {
        when(asianOddsProvider.providerCode()).thenReturn("STUB");
        MatchEntity match = new MatchEntity();
        match.setId(100L);
        match.setLotteryDate(LocalDate.of(2026, 7, 22));
        match.setKickoffTime(Instant.parse("2026-07-22T11:30:00Z"));
        when(matchMapper.selectList(any(Wrapper.class))).thenReturn(List.of(match));
        when(dataSyncRunMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(asianOddsSnapshotMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        when(matchMappingService.findConfirmed("STUB", "asian-stub-001")).thenReturn(
                new MatchMapResultDto(
                        1L,
                        100L,
                        MatchMapOutcomeEnum.REUSED,
                        MappingStatusEnum.MANUAL_CONFIRMED,
                        new BigDecimal("1.0000"),
                        "MANUAL",
                        "EXTERNAL_ID_REUSE",
                        List.of()
                )
        );
        when(matchMappingService.resolve(any())).thenReturn(new MatchMapResultDto(
                    2L,
                    101L,
                    MatchMapOutcomeEnum.PENDING,
                    MappingStatusEnum.PENDING,
                    new BigDecimal("0.5000"),
                    "PENDING",
                    "SCORE_PENDING",
                    List.of()
        ));

        when(snapshotWriter.writeLines(eq(100L), eq("STUB"), any(), any())).thenReturn(
                new AsianOddsSnapshotWriter.WriteResult(new ProviderParseResult(1, 0, null), 1, 0)
        );

        String payloadJson = """
                {
                  "matches": [
                  {
                    "providerMatchId": "asian-stub-001",
                    "homeTeamName": "演示主队 A",
                    "awayTeamName": "演示客队 A",
                    "kickoffTime": "2026-07-22T19:30:00+08:00",
                    "live": false,
                    "lines": [
                      {
                        "bookmakerCode": "pinnacle",
                        "handicapLine": -0.5,
                        "homeOdds": 1.90,
                        "awayOdds": 1.95,
                        "totalLine": 2.5,
                        "overOdds": 1.92,
                        "underOdds": 1.94,
                        "providerUpdatedAt": "2026-07-22T12:00:00+08:00"
                      }
                    ]
                  },
                  {
                    "providerMatchId": "asian-stub-pending",
                    "homeTeamName": "待映射主队",
                    "awayTeamName": "待映射客队",
                    "kickoffTime": "2026-07-22T20:00:00+08:00",
                    "live": false,
                    "lines": [
                      {
                        "bookmakerCode": "pinnacle",
                        "handicapLine": 0,
                        "homeOdds": 1.80,
                        "awayOdds": 2.00,
                        "providerUpdatedAt": "2026-07-22T12:00:00+08:00"
                      }
                    ]
                  },
                  {
                    "providerMatchId": "asian-stub-live",
                    "homeTeamName": "滚球主队",
                    "awayTeamName": "滚球客队",
                    "kickoffTime": "2026-07-22T21:00:00+08:00",
                    "live": true,
                    "lines": [
                      {
                        "bookmakerCode": "pinnacle",
                        "handicapLine": 0,
                        "homeOdds": 1.80,
                        "awayOdds": 2.00,
                        "providerUpdatedAt": "2026-07-22T12:00:00+08:00"
                      }
                    ]
                  }
                  ]
                }
                """;

        DataSyncRun run = new DataSyncRun();
        run.setId(9L);
        run.setQuotaCost(1);
        when(providerSyncTemplate.execute(
                eq("STUB"),
                eq(ProviderDataTypeEnum.ASIAN_ODDS),
                any(ProviderPayloadFetcher.class),
                any(ProviderPayloadParser.class)
        )).thenAnswer(invocation -> {
            ProviderPayloadFetcher fetcher = invocation.getArgument(2);
            ProviderPayloadParser parser = invocation.getArgument(3);
            when(asianOddsProvider.fetchPreMatchOddsRaw(any())).thenReturn(new ProviderFetchResult(
                    "asian-odds:test",
                    payloadJson,
                    200,
                    Instant.now(),
                    0,
                    1
            ));
            fetcher.fetch();

            RawDataPayload payload = new RawDataPayload();
            payload.setPayloadHash("f".repeat(64));
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> payloadMap = objectMapper.readValue(payloadJson, java.util.Map.class);
            payload.setPayload(payloadMap);
            ProviderParseResult parseResult = parser.parse(
                    ProviderDataTypeEnum.ASIAN_ODDS,
                    "asian-odds:test",
                    payload
            );
            assertThat(parseResult.successCount()).isGreaterThanOrEqualTo(2);
            return new ProviderSyncOutcome(run, payload, SyncStatusEnum.SUCCESS, false);
        });

        AsianOddsSyncResultDto result = service.sync(new AsianOddsSyncRequestDto(LocalDate.of(2026, 7, 22)));

        assertThat(result.quotaBlocked()).isFalse();
        assertThat(result.snapshotInsertCount()).isEqualTo(1);
        assertThat(result.skippedUnmapped()).isEqualTo(1);
        assertThat(result.skippedLive()).isEqualTo(1);
        assertThat(result.sportteryMatchCount()).isEqualTo(1);
        assertThat(result.coveredMatchCount()).isEqualTo(1);
        assertThat(result.coverageRate()).isEqualByComparingTo("1.0000");
        verify(snapshotWriter).writeLines(eq(100L), eq("STUB"), any(), eq("f".repeat(64)));
        verify(matchMappingService, never()).resolve(argThat(
                request -> "asian-stub-001".equals(request.externalMatchId())
        ));
    }
}
