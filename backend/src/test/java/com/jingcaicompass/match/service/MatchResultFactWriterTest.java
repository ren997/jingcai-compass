package com.jingcaicompass.match.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jingcaicompass.audit.enums.AuditActionTypeEnum;
import com.jingcaicompass.audit.enums.AuditTargetTypeEnum;
import com.jingcaicompass.audit.service.AuditLogService;
import com.jingcaicompass.match.dto.SportteryMatchResultDto;
import com.jingcaicompass.match.entity.MatchEntity;
import com.jingcaicompass.match.entity.MatchResultFact;
import com.jingcaicompass.match.enums.MatchResultFactStatusEnum;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import com.jingcaicompass.match.mapper.MatchMapper;
import com.jingcaicompass.match.mapper.MatchResultFactMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 赛果事实单条事务规则不依赖 Spring、Mapper 实现或数据库。 */
@ExtendWith(MockitoExtension.class)
class MatchResultFactWriterTest {

    private static final LocalDate LOTTERY_DATE = LocalDate.of(2026, 7, 22);
    private static final Long RAW_PAYLOAD_ID = 91L;

    @Mock
    private MatchMapper matchMapper;

    @Mock
    private MatchResultFactMapper factMapper;

    @Mock
    private AuditLogService auditLogService;

    private MatchResultFactWriter writer;
    private MatchEntity match;

    @BeforeEach
    void setUp() {
        writer = new MatchResultFactWriter(matchMapper, factMapper, auditLogService);
        match = new MatchEntity();
        match.setId(41L);
        match.setMatchStatus(MatchStatusEnum.SCHEDULED);
        lenient().when(matchMapper.selectByLotteryIdentityForUpdate(LOTTERY_DATE, "周三001")).thenReturn(match);
        lenient().when(matchMapper.updateById(any(MatchEntity.class))).thenReturn(1);
        lenient().when(factMapper.insert(any(MatchResultFact.class))).thenAnswer(invocation -> {
            MatchResultFact fact = invocation.getArgument(0);
            fact.setId(501L);
            return 1;
        });
    }

    @Test
    void appendsFinalFactUpdatesProjectionAndAuditsSync() {
        MatchResultFactWriter.WriteResult result = writer.write(
                result(MatchStatusEnum.FINISHED, 2, 1, false, false, "2026-07-22T23:30:00+08:00"),
                RAW_PAYLOAD_ID
        );

        ArgumentCaptor<MatchResultFact> factCaptor = ArgumentCaptor.forClass(MatchResultFact.class);
        verify(factMapper).insert(factCaptor.capture());
        MatchResultFact fact = factCaptor.getValue();
        assertThat(result.outcome()).isEqualTo(MatchResultFactWriter.WriteOutcome.APPENDED);
        assertThat(fact.getFactVersion()).isEqualTo(1);
        assertThat(fact.getFactStatus()).isEqualTo(MatchResultFactStatusEnum.FINAL);
        assertThat(fact.getHomeScore()).isEqualTo(2);
        assertThat(fact.getAwayScore()).isEqualTo(1);
        assertThat(fact.getRawDataPayloadId()).isEqualTo(RAW_PAYLOAD_ID);
        assertThat(fact.getIsCurrent()).isTrue();
        assertThat(match.getMatchStatus()).isEqualTo(MatchStatusEnum.FINISHED);
        assertThat(match.getHomeScore()).isEqualTo(2);
        assertThat(match.getAwayScore()).isEqualTo(1);
        verify(auditLogService).append(
                eq(MatchResultFactWriter.SYSTEM_OPERATOR),
                eq(AuditTargetTypeEnum.MATCH_RESULT_FACT),
                eq("501"),
                eq(AuditActionTypeEnum.SYNC),
                eq("matchResultFact"),
                isNull(),
                contains("factStatus=FINAL")
        );
    }

    @ParameterizedTest
    @MethodSource("pendingStatuses")
    void appendsPendingFactsForUnconfirmedStatuses(MatchStatusEnum status) {
        writer.write(result(status, null, null, false, false, "2026-07-22T20:00:00+08:00"), RAW_PAYLOAD_ID);

        ArgumentCaptor<MatchResultFact> factCaptor = ArgumentCaptor.forClass(MatchResultFact.class);
        verify(factMapper).insert(factCaptor.capture());
        assertThat(factCaptor.getValue().getFactStatus()).isEqualTo(MatchResultFactStatusEnum.PENDING);
        assertThat(match.getMatchStatus()).isEqualTo(status);
        assertThat(match.getHomeScore()).isNull();
        assertThat(match.getAwayScore()).isNull();
    }

    @Test
    void mapsOnlyExplicitOfficialVoidToVoid() {
        writer.write(result(MatchStatusEnum.CANCELLED, null, null, false, true, "2026-07-22T20:00:00+08:00"), RAW_PAYLOAD_ID);

        ArgumentCaptor<MatchResultFact> factCaptor = ArgumentCaptor.forClass(MatchResultFact.class);
        verify(factMapper).insert(factCaptor.capture());
        assertThat(factCaptor.getValue().getFactStatus()).isEqualTo(MatchResultFactStatusEnum.VOID);
    }

    @Test
    void skipsIdenticalEffectiveFact() {
        MatchResultFact current = currentFact(1, MatchResultFactStatusEnum.FINAL, MatchStatusEnum.FINISHED, 2, 1,
                "2026-07-22T23:30:00Z");
        when(factMapper.selectCurrentByMatchId(41L)).thenReturn(current);

        MatchResultFactWriter.WriteResult result = writer.write(
                result(MatchStatusEnum.FINISHED, 2, 1, false, false, "2026-07-23T07:30:00+08:00"),
                RAW_PAYLOAD_ID
        );

        assertThat(result.outcome()).isEqualTo(MatchResultFactWriter.WriteOutcome.UNCHANGED);
        verify(factMapper, never()).markNotCurrent(any());
        verify(factMapper, never()).insert(any(MatchResultFact.class));
        verify(matchMapper, never()).updateById(any(MatchEntity.class));
        verify(auditLogService, never()).append(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsUnknownMatchesWithoutCreatingFacts() {
        when(matchMapper.selectByLotteryIdentityForUpdate(LOTTERY_DATE, "周三001")).thenReturn(null);

        assertThatIllegalArgumentException().isThrownBy(() -> writer.write(
                result(MatchStatusEnum.FINISHED, 2, 1, false, false, "2026-07-22T23:30:00+08:00"),
                RAW_PAYLOAD_ID
        )).withMessageContaining("match not found");
        verify(factMapper, never()).insert(any(MatchResultFact.class));
    }

    @ParameterizedTest
    @MethodSource("invalidResults")
    void rejectsInvalidScoresAndOfficialVoidPayloads(SportteryMatchResultDto invalidResult) {
        assertThatIllegalArgumentException().isThrownBy(() -> writer.write(invalidResult, RAW_PAYLOAD_ID));
        verify(factMapper, never()).insert(any(MatchResultFact.class));
    }

    @Test
    void rejectsUnamendedTerminalChangeAndTerminalRegression() {
        MatchResultFact current = currentFact(1, MatchResultFactStatusEnum.FINAL, MatchStatusEnum.FINISHED, 2, 1,
                "2026-07-22T23:30:00Z");
        when(factMapper.selectCurrentByMatchId(41L)).thenReturn(current);

        assertThatIllegalArgumentException().isThrownBy(() -> writer.write(
                result(MatchStatusEnum.FINISHED, 1, 1, false, false, "2026-07-23T08:00:00+08:00"),
                RAW_PAYLOAD_ID
        )).withMessageContaining("amended=true");
        assertThatIllegalArgumentException().isThrownBy(() -> writer.write(
                result(MatchStatusEnum.POSTPONED, null, null, true, false, "2026-07-23T08:00:00+08:00"),
                RAW_PAYLOAD_ID
        )).withMessageContaining("cannot regress");
        verify(factMapper, never()).markNotCurrent(any());
    }

    @Test
    void replacesTerminalFactOnlyForLaterOfficialAmendment() {
        MatchResultFact current = currentFact(1, MatchResultFactStatusEnum.FINAL, MatchStatusEnum.FINISHED, 2, 1,
                "2026-07-22T23:30:00Z");
        when(factMapper.selectCurrentByMatchId(41L)).thenReturn(current);
        when(factMapper.markNotCurrent(77L)).thenReturn(1);

        MatchResultFactWriter.WriteResult result = writer.write(
                result(MatchStatusEnum.FINISHED, 1, 1, true, false, "2026-07-23T08:00:00+08:00"),
                RAW_PAYLOAD_ID
        );

        ArgumentCaptor<MatchResultFact> factCaptor = ArgumentCaptor.forClass(MatchResultFact.class);
        verify(factMapper).insert(factCaptor.capture());
        assertThat(result.outcome()).isEqualTo(MatchResultFactWriter.WriteOutcome.SUPERSEDED);
        assertThat(factCaptor.getValue().getFactVersion()).isEqualTo(2);
        assertThat(factCaptor.getValue().getSupersedesFactVersion()).isEqualTo(1);
        verify(auditLogService).append(
                eq(MatchResultFactWriter.SYSTEM_OPERATOR),
                eq(AuditTargetTypeEnum.MATCH_RESULT_FACT),
                eq("501"),
                eq(AuditActionTypeEnum.SUPERSEDE),
                eq("matchResultFact"),
                contains("version=1"),
                contains("version=2")
        );
    }

    @Test
    void rejectsChangedPendingFactWithoutLaterProviderTimestamp() {
        MatchResultFact current = currentFact(1, MatchResultFactStatusEnum.PENDING, MatchStatusEnum.POSTPONED, null, null,
                "2026-07-22T20:00:00Z");
        when(factMapper.selectCurrentByMatchId(41L)).thenReturn(current);

        assertThatIllegalArgumentException().isThrownBy(() -> writer.write(
                result(MatchStatusEnum.CANCELLED, null, null, false, false, "2026-07-22T20:00:00Z"),
                RAW_PAYLOAD_ID
        )).withMessageContaining("later providerUpdatedAt");
    }

    private static Stream<Arguments> pendingStatuses() {
        return Stream.of(MatchStatusEnum.POSTPONED, MatchStatusEnum.CANCELLED, MatchStatusEnum.ABANDONED)
                .map(Arguments::of);
    }

    private static Stream<Arguments> invalidResults() {
        return Stream.of(
                Arguments.of(result(MatchStatusEnum.FINISHED, null, 1, false, false, "2026-07-22T23:30:00+08:00")),
                Arguments.of(result(MatchStatusEnum.FINISHED, -1, 1, false, false, "2026-07-22T23:30:00+08:00")),
                Arguments.of(result(MatchStatusEnum.POSTPONED, 1, null, false, false, "2026-07-22T23:30:00+08:00")),
                Arguments.of(result(MatchStatusEnum.CANCELLED, 1, 0, false, true, "2026-07-22T23:30:00+08:00"))
        );
    }

    private static MatchResultFact currentFact(
            int version,
            MatchResultFactStatusEnum factStatus,
            MatchStatusEnum matchStatus,
            Integer homeScore,
            Integer awayScore,
            String providerUpdatedAt
    ) {
        MatchResultFact fact = new MatchResultFact();
        fact.setId(77L);
        fact.setMatchId(41L);
        fact.setFactVersion(version);
        fact.setFactStatus(factStatus);
        fact.setMatchStatus(matchStatus);
        fact.setHomeScore(homeScore);
        fact.setAwayScore(awayScore);
        fact.setProviderUpdatedAt(Instant.parse(providerUpdatedAt));
        fact.setIsCurrent(true);
        return fact;
    }

    private static SportteryMatchResultDto result(
            MatchStatusEnum status,
            Integer homeScore,
            Integer awayScore,
            boolean amended,
            boolean officialVoid,
            String providerUpdatedAt
    ) {
        return new SportteryMatchResultDto(
                "stub-2026-07-22-001",
                LOTTERY_DATE,
                "周三001",
                homeScore,
                awayScore,
                status,
                amended,
                officialVoid,
                OffsetDateTime.parse(providerUpdatedAt)
        );
    }
}
