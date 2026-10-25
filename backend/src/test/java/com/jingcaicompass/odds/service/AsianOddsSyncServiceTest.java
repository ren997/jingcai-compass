package com.jingcaicompass.odds.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.data.dto.ProviderParseResult;
import com.jingcaicompass.data.dto.ProviderSyncOutcome;
import com.jingcaicompass.data.entity.DataSyncRun;
import com.jingcaicompass.data.entity.RawDataPayload;
import com.jingcaicompass.data.enums.ProviderDataTypeEnum;
import com.jingcaicompass.data.enums.SyncStatusEnum;
import com.jingcaicompass.data.service.ProviderPayloadParser;
import com.jingcaicompass.data.service.ProviderSyncTemplate;
import com.jingcaicompass.match.entity.MatchEntity;
import com.jingcaicompass.match.mapper.MatchMapper;
import com.jingcaicompass.odds.dto.AsianOddsSyncRequestDto;
import com.jingcaicompass.odds.dto.AsianOddsSyncResultDto;
import com.jingcaicompass.odds.mapper.AsianOddsSnapshotMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 亚盘同步的比赛解析门禁：单源下只按 {@code external_match_id} 直连。
 *
 * <p>映射表与归一化链已在阶段 5 删除，这里不再有「未确认映射」以外的判定分支；
 * 额度门禁曾只服务 The Odds API，也随旧源一起移除。</p>
 */
@ExtendWith(MockitoExtension.class)
class AsianOddsSyncServiceTest {

    private static final String PROVIDER = "NOVA_API";

    @Mock
    private AsianOddsProvider asianOddsProvider;

    @Mock
    private ProviderSyncTemplate providerSyncTemplate;

    @Mock
    private AsianOddsSnapshotWriter snapshotWriter;

    @Mock
    private MatchMapper matchMapper;

    @Mock
    private AsianOddsSnapshotMapper asianOddsSnapshotMapper;

    private ObjectMapper objectMapper;
    private AsianOddsSyncServiceImpl service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new AsianOddsSyncServiceImpl(
                asianOddsProvider,
                providerSyncTemplate,
                new AsianOddsPayloadMapper(objectMapper, new NovaApiOddsPayloadMapper()),
                snapshotWriter,
                matchMapper,
                asianOddsSnapshotMapper,
                objectMapper
        );
    }

    @Test
    @DisplayName("赔率里的场次不在当日赛程时计为未匹配，且不写快照")
    void skipsOddsWhoseExternalMatchIdIsMissingFromTodaysSchedule() throws Exception {
        when(asianOddsProvider.providerCode()).thenReturn(PROVIDER);
        when(matchMapper.selectList(any(Wrapper.class))).thenReturn(List.of(match(100L, "2042155")));
        stubSync(payload("9999999"));

        AsianOddsSyncResultDto result = service.sync(new AsianOddsSyncRequestDto(LocalDate.of(2026, 7, 22)));

        assertThat(result.skippedUnmapped()).isEqualTo(1);
        assertThat(result.snapshotInsertCount()).isZero();
        verify(snapshotWriter, never()).writeLines(any(), any(), any(), any());
    }

    @Test
    @DisplayName("已开赛的场次不写赛前快照，计为 live 跳过")
    void skipsMatchesThatAlreadyKickedOff() throws Exception {
        when(asianOddsProvider.providerCode()).thenReturn(PROVIDER);
        MatchEntity started = match(100L, "2042155");
        started.setKickoffTime(Instant.now().minusSeconds(600));
        when(matchMapper.selectList(any(Wrapper.class))).thenReturn(List.of(started));
        stubSync(payload("2042155"));

        AsianOddsSyncResultDto result = service.sync(new AsianOddsSyncRequestDto(LocalDate.of(2026, 7, 22)));

        assertThat(result.skippedLive()).isEqualTo(1);
        assertThat(result.snapshotInsertCount()).isZero();
        verify(snapshotWriter, never()).writeLines(any(), any(), any(), any());
    }

    @Test
    @DisplayName("赛前场次按 external_match_id 命中后直接写入快照")
    void writesSnapshotForPreMatchMatchLocatedByExternalMatchId() throws Exception {
        when(asianOddsProvider.providerCode()).thenReturn(PROVIDER);
        MatchEntity upcoming = match(100L, "2042155");
        upcoming.setKickoffTime(Instant.now().plusSeconds(3600));
        when(matchMapper.selectList(any(Wrapper.class))).thenReturn(List.of(upcoming));
        when(asianOddsSnapshotMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        when(snapshotWriter.writeLines(eq(100L), eq(PROVIDER), any(), any())).thenReturn(
                new AsianOddsSnapshotWriter.WriteResult(new ProviderParseResult(1, 0, null), 1, 0)
        );
        stubSync(payload("2042155"));

        AsianOddsSyncResultDto result = service.sync(new AsianOddsSyncRequestDto(LocalDate.of(2026, 7, 22)));

        assertThat(result.snapshotInsertCount()).isEqualTo(1);
        assertThat(result.skippedUnmapped()).isZero();
        assertThat(result.skippedLive()).isZero();
        assertThat(result.coveredMatchCount()).isEqualTo(1);
        assertThat(result.coverageRate()).isEqualByComparingTo("1.0000");
        verify(snapshotWriter).writeLines(eq(100L), eq(PROVIDER), any(), any());
    }

    @Test
    @DisplayName("当日没有赛程时仍落一条零场次成功记录，而不是同步失败")
    void recordsAnEmptySuccessfulRunWhenThereAreNoMatches() {
        when(asianOddsProvider.providerCode()).thenReturn(PROVIDER);
        when(matchMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(providerSyncTemplate.execute(any(), any(), any(), any())).thenReturn(
                new ProviderSyncOutcome(new DataSyncRun(), null, SyncStatusEnum.SUCCESS, false));

        AsianOddsSyncResultDto result = service.sync(new AsianOddsSyncRequestDto(LocalDate.of(2026, 7, 22)));

        assertThat(result.outcome().status()).isEqualTo(SyncStatusEnum.SUCCESS);
        assertThat(result.snapshotInsertCount()).isZero();
        assertThat(result.sportteryMatchCount()).isZero();
        assertThat(result.coverageRate()).isEqualByComparingTo("0.0000");
        verify(asianOddsProvider, never()).fetchPreMatchOddsRaw(any());
    }

    private static MatchEntity match(long id, String externalMatchId) {
        MatchEntity match = new MatchEntity();
        match.setId(id);
        match.setExternalMatchId(externalMatchId);
        match.setLotteryDate(LocalDate.of(2026, 7, 22));
        match.setKickoffTime(Instant.parse("2026-07-22T12:00:00Z"));
        return match;
    }

    private static String payload(String providerMatchId) {
        return """
                {
                  "matches": [
                    {
                      "providerMatchId": "%s",
                      "homeTeamName": "主队",
                      "awayTeamName": "客队",
                      "kickoffTime": "2026-07-22T20:00:00+08:00",
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
                          "providerUpdatedAt": "2026-07-22T19:00:00+08:00"
                        }
                      ]
                    }
                  ]
                }
                """.formatted(providerMatchId);
    }

    private void stubSync(String payloadJson) throws Exception {
        when(providerSyncTemplate.execute(any(), any(), any(), any())).thenAnswer(invocation -> {
            ProviderPayloadParser parser = invocation.getArgument(3);
            RawDataPayload payload = new RawDataPayload();
            payload.setPayloadHash("a".repeat(64));
            payload.setPayload(objectMapper.readValue(payloadJson, Map.class));
            parser.parse(ProviderDataTypeEnum.ASIAN_ODDS, "nova-api:test", payload);
            return new ProviderSyncOutcome(new DataSyncRun(), payload, SyncStatusEnum.SUCCESS, false);
        });
    }
}
