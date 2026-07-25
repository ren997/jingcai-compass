package com.jingcaicompass.match.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jingcaicompass.match.dto.EntityNormalizeResultDto;
import com.jingcaicompass.match.entity.MatchEntity;
import com.jingcaicompass.match.enums.EntityNormalizeOutcomeEnum;
import com.jingcaicompass.match.enums.MappingStatusEnum;
import com.jingcaicompass.match.enums.NormalizationPendingReasonEnum;
import com.jingcaicompass.match.mapper.MatchMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MatchNormalizationWorkerTest {

    @Mock
    private MatchMapper matchMapper;
    @Mock
    private LeagueNormalizationService leagueNormalizationService;
    @Mock
    private TeamNormalizationService teamNormalizationService;

    private MatchNormalizationWorker worker;

    @BeforeEach
    void setUp() {
        worker = new MatchNormalizationWorker(
                matchMapper,
                leagueNormalizationService,
                teamNormalizationService
        );
    }

    @Test
    void neverOverwritesExistingManualIds() {
        MatchEntity match = match();
        match.setLeagueId(10L);
        match.setHomeTeamId(20L);
        match.setAwayTeamId(30L);
        when(matchMapper.selectById(1L)).thenReturn(match);

        var result = worker.normalize(1L, "STUB");

        assertThat(result.normalized()).isTrue();
        assertThat(result.updated()).isFalse();
        verify(leagueNormalizationService, never()).resolve(any());
        verify(teamNormalizationService, never()).resolve(any());
        verify(matchMapper, never()).updateById(any(MatchEntity.class));
    }

    @Test
    void fillsOnlyConfirmedEntitiesAndKeepsPendingNull() {
        MatchEntity match = match();
        when(matchMapper.selectById(1L)).thenReturn(match);
        when(leagueNormalizationService.resolve(any())).thenReturn(new EntityNormalizeResultDto(
                10L,
                EntityNormalizeOutcomeEnum.RESOLVED,
                MappingStatusEnum.AUTO_CONFIRMED,
                "EXACT_NAME"
        ));
        when(teamNormalizationService.resolve(any()))
                .thenReturn(new EntityNormalizeResultDto(
                        20L,
                        EntityNormalizeOutcomeEnum.RESOLVED,
                        MappingStatusEnum.MANUAL_CONFIRMED,
                        "ALIAS"
                ))
                .thenReturn(new EntityNormalizeResultDto(
                        30L,
                        EntityNormalizeOutcomeEnum.CANDIDATE_CREATED,
                        MappingStatusEnum.PENDING,
                        "NAME_CANDIDATE"
                ));

        var result = worker.normalize(1L, "STUB");

        assertThat(result.normalized()).isFalse();
        assertThat(result.updated()).isTrue();
        assertThat(result.pendingReasons())
                .containsExactly(NormalizationPendingReasonEnum.AWAY_TEAM_PENDING);
        assertThat(match.getLeagueId()).isEqualTo(10L);
        assertThat(match.getHomeTeamId()).isEqualTo(20L);
        assertThat(match.getAwayTeamId()).isNull();
        verify(matchMapper).updateById(match);
    }

    private static MatchEntity match() {
        MatchEntity match = new MatchEntity();
        match.setId(1L);
        match.setLeagueName("演示联赛");
        match.setHomeTeamName("演示主队");
        match.setAwayTeamName("演示客队");
        return match;
    }
}
