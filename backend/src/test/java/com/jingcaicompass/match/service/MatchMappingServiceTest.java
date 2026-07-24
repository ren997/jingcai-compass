package com.jingcaicompass.match.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.jingcaicompass.match.dto.MatchMapRequestDto;
import com.jingcaicompass.match.dto.MatchMapResultDto;
import com.jingcaicompass.match.entity.MatchEntity;
import com.jingcaicompass.match.entity.MatchSourceMapping;
import com.jingcaicompass.match.enums.MatchMapOutcomeEnum;
import com.jingcaicompass.match.enums.MappingStatusEnum;
import com.jingcaicompass.match.mapper.MatchMapper;
import com.jingcaicompass.match.mapper.MatchSourceMappingMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MatchMappingServiceTest {

    @Mock
    private MatchMapper matchMapper;

    @Mock
    private MatchSourceMappingMapper matchSourceMappingMapper;

    private MatchMappingServiceImpl service;
    private final AtomicLong idSeq = new AtomicLong(100);
    private final Instant kickoff = Instant.parse("2026-07-24T12:00:00Z");

    @BeforeEach
    void setUp() {
        service = new MatchMappingServiceImpl(matchMapper, matchSourceMappingMapper);
    }

    @Test
    void reusesConfirmedExternalMapping() {
        MatchSourceMapping existing = new MatchSourceMapping();
        existing.setId(9L);
        existing.setMatchId(77L);
        existing.setMappingStatus(MappingStatusEnum.MANUAL_CONFIRMED);
        existing.setMappingConfidence(new BigDecimal("1.0000"));
        existing.setMappingExplanation("manual");
        when(matchSourceMappingMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

        MatchMapResultDto result = service.resolve(baseRequest("ext-1", 10L, 20L, 30L, kickoff));

        assertThat(result.outcome()).isEqualTo(MatchMapOutcomeEnum.REUSED);
        assertThat(result.matchId()).isEqualTo(77L);
        assertThat(result.method()).isEqualTo(MatchMappingServiceImpl.METHOD_EXTERNAL_ID_REUSE);
        verify(matchMapper, never()).selectList(any());
        verify(matchSourceMappingMapper, never()).insert(any(MatchSourceMapping.class));
    }

    @Test
    void autoConfirmsWhenIdsAndTimeAlign() {
        when(matchSourceMappingMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(matchMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                match(1L, 10L, 20L, 30L, "英超", "曼联", "切尔西", kickoff)
        ));
        when(matchSourceMappingMapper.insert(any(MatchSourceMapping.class))).thenAnswer(invocation -> {
            MatchSourceMapping mapping = invocation.getArgument(0);
            mapping.setId(idSeq.getAndIncrement());
            return 1;
        });

        MatchMapResultDto result = service.resolve(baseRequest("ext-2", 10L, 20L, 30L, kickoff));

        assertThat(result.outcome()).isEqualTo(MatchMapOutcomeEnum.AUTO_CONFIRMED);
        assertThat(result.mappingStatus()).isEqualTo(MappingStatusEnum.AUTO_CONFIRMED);
        assertThat(result.matchId()).isEqualTo(1L);
        assertThat(result.method()).isEqualTo(MatchMappingServiceImpl.METHOD_SCORE_AUTO);
        assertThat(result.confidence()).isGreaterThanOrEqualTo(new BigDecimal("0.85"));

        ArgumentCaptor<MatchSourceMapping> captor = ArgumentCaptor.forClass(MatchSourceMapping.class);
        verify(matchSourceMappingMapper).insert(captor.capture());
        assertThat(captor.getValue().getMappingExplanation()).contains("HOME_ID");
        assertThat(captor.getValue().getMappingCandidates()).isNotEmpty();
    }

    @Test
    void homeAwayReversedGoesPending() {
        when(matchSourceMappingMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(matchMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                match(1L, 10L, 20L, 30L, "英超", "曼联", "切尔西", kickoff)
        ));
        when(matchSourceMappingMapper.insert(any(MatchSourceMapping.class))).thenAnswer(invocation -> {
            MatchSourceMapping mapping = invocation.getArgument(0);
            mapping.setId(idSeq.getAndIncrement());
            return 1;
        });

        MatchMapResultDto result = service.resolve(baseRequest("ext-3", 10L, 30L, 20L, kickoff));

        assertThat(result.outcome()).isEqualTo(MatchMapOutcomeEnum.PENDING);
        assertThat(result.method()).isEqualTo(MatchMappingServiceImpl.METHOD_HARD_REJECT_PENDING);
        assertThat(result.explanation()).contains("HOME_AWAY_REVERSED");
    }

    @Test
    void leagueConflictGoesPending() {
        when(matchSourceMappingMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(matchMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                match(1L, 10L, 20L, 30L, "英超", "曼联", "切尔西", kickoff)
        ));
        when(matchSourceMappingMapper.insert(any(MatchSourceMapping.class))).thenAnswer(invocation -> {
            MatchSourceMapping mapping = invocation.getArgument(0);
            mapping.setId(idSeq.getAndIncrement());
            return 1;
        });

        MatchMapResultDto result = service.resolve(baseRequest("ext-4", 99L, 20L, 30L, kickoff));

        assertThat(result.outcome()).isEqualTo(MatchMapOutcomeEnum.PENDING);
        assertThat(result.explanation()).contains("LEAGUE_CONFLICT");
    }

    @Test
    void noCandidateWhenOutsideTimeWindow() {
        when(matchSourceMappingMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(matchMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        MatchMapResultDto result = service.resolve(
                baseRequest("ext-5", 10L, 20L, 30L, kickoff.plus(5, ChronoUnit.HOURS))
        );

        assertThat(result.outcome()).isEqualTo(MatchMapOutcomeEnum.NO_CANDIDATE);
        assertThat(result.mappingId()).isNull();
        verify(matchSourceMappingMapper, never()).insert(any(MatchSourceMapping.class));
    }

    @Test
    void insufficientMarginGoesPending() {
        when(matchSourceMappingMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(matchMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                match(1L, 10L, 20L, 30L, "英超", "曼联", "切尔西", kickoff),
                match(2L, 10L, 20L, 30L, "英超", "曼联", "切尔西", kickoff.plus(5, ChronoUnit.MINUTES))
        ));
        when(matchSourceMappingMapper.insert(any(MatchSourceMapping.class))).thenAnswer(invocation -> {
            MatchSourceMapping mapping = invocation.getArgument(0);
            mapping.setId(idSeq.getAndIncrement());
            return 1;
        });

        MatchMapResultDto result = service.resolve(baseRequest("ext-6", 10L, 20L, 30L, kickoff));

        assertThat(result.outcome()).isEqualTo(MatchMapOutcomeEnum.PENDING);
        assertThat(result.method()).isEqualTo(MatchMappingServiceImpl.METHOD_SCORE_PENDING);
        assertThat(result.candidates()).hasSize(2);
    }

    @Test
    void repeatedPendingCallUpdatesSameRow() {
        MatchSourceMapping pending = new MatchSourceMapping();
        pending.setId(55L);
        pending.setMatchId(1L);
        pending.setProviderCode("THE_ODDS_API");
        pending.setExternalMatchId("ext-7");
        pending.setMappingStatus(MappingStatusEnum.PENDING);

        when(matchSourceMappingMapper.selectOne(any(Wrapper.class)))
                .thenReturn(null)
                .thenReturn(pending);
        when(matchMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                match(1L, 10L, 20L, 30L, "英超", "曼联", "切尔西", kickoff)
        ));

        MatchMapResultDto result = service.resolve(baseRequest("ext-7", 10L, 20L, 30L, kickoff));

        assertThat(result.mappingId()).isEqualTo(55L);
        verify(matchSourceMappingMapper, never()).insert(any(MatchSourceMapping.class));
        verify(matchSourceMappingMapper, times(1)).updateById(any(MatchSourceMapping.class));
    }

    @Test
    void listPendingReturnsOnlyPendingOrdered() {
        MatchSourceMapping pending = new MatchSourceMapping();
        pending.setId(1L);
        pending.setMappingStatus(MappingStatusEnum.PENDING);
        when(matchSourceMappingMapper.selectList(any(Wrapper.class))).thenReturn(List.of(pending));

        List<MatchSourceMapping> result = service.listPending("THE_ODDS_API");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getMappingStatus()).isEqualTo(MappingStatusEnum.PENDING);
        verify(matchSourceMappingMapper).selectList(any(Wrapper.class));
    }

    private MatchMapRequestDto baseRequest(
            String externalMatchId,
            Long leagueId,
            Long homeTeamId,
            Long awayTeamId,
            Instant kickoffTime
    ) {
        return new MatchMapRequestDto(
                "THE_ODDS_API",
                externalMatchId,
                "L1",
                "H1",
                "A1",
                leagueId,
                homeTeamId,
                awayTeamId,
                "英超",
                homeTeamId != null && homeTeamId == 30L ? "切尔西" : "曼联",
                awayTeamId != null && awayTeamId == 20L ? "曼联" : "切尔西",
                kickoffTime
        );
    }

    private MatchEntity match(
            Long id,
            Long leagueId,
            Long homeTeamId,
            Long awayTeamId,
            String leagueName,
            String homeName,
            String awayName,
            Instant kickoffTime
    ) {
        MatchEntity entity = new MatchEntity();
        entity.setId(id);
        entity.setLeagueId(leagueId);
        entity.setHomeTeamId(homeTeamId);
        entity.setAwayTeamId(awayTeamId);
        entity.setLeagueName(leagueName);
        entity.setHomeTeamName(homeName);
        entity.setAwayTeamName(awayName);
        entity.setKickoffTime(kickoffTime);
        return entity;
    }
}
