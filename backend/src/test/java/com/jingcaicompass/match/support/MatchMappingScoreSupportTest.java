package com.jingcaicompass.match.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.jingcaicompass.match.dto.MatchMapRequestDto;
import com.jingcaicompass.match.entity.MatchEntity;
import com.jingcaicompass.match.support.MatchMappingScoreSupport.ScoredCandidate;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MatchMappingScoreSupportTest {

    private final Instant kickoff = Instant.parse("2026-07-24T12:00:00Z");

    @Test
    void fullIdMatchScoresHighEnoughForAuto() {
        MatchEntity match = match(1L, 10L, 20L, 30L, "英超", "曼联", "切尔西", kickoff);
        ScoredCandidate scored = MatchMappingScoreSupport.score(
                request(10L, 20L, 30L, "英超", "曼联", "切尔西", kickoff),
                match
        );

        assertThat(scored.score()).isGreaterThanOrEqualTo(new BigDecimal("0.85"));
        assertThat(scored.hardReject()).isFalse();
        assertThat(scored.reasons()).contains("HOME_ID", "AWAY_ID", "LEAGUE_ID", "TIME_LE_15");
        assertThat(MatchMappingScoreSupport.canAutoConfirm(scored, null)).isTrue();
    }

    @Test
    void homeAwayReversedIsHardReject() {
        MatchEntity match = match(1L, 10L, 20L, 30L, "英超", "曼联", "切尔西", kickoff);
        ScoredCandidate scored = MatchMappingScoreSupport.score(
                request(10L, 30L, 20L, "英超", "切尔西", "曼联", kickoff),
                match
        );

        assertThat(scored.reasons()).contains("HOME_AWAY_REVERSED");
        assertThat(scored.hardReject()).isTrue();
        assertThat(MatchMappingScoreSupport.canAutoConfirm(scored, null)).isFalse();
    }

    @Test
    void leagueConflictIsHardReject() {
        MatchEntity match = match(1L, 10L, 20L, 30L, "英超", "曼联", "切尔西", kickoff);
        ScoredCandidate scored = MatchMappingScoreSupport.score(
                request(99L, 20L, 30L, "西甲", "曼联", "切尔西", kickoff),
                match
        );

        assertThat(scored.reasons()).contains("LEAGUE_CONFLICT");
        assertThat(scored.hardReject()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "10, true",
            "60, true",
            "61, false",
            "180, false",
            "181, false"
    })
    void candidateWindowAndAutoTimeLimit(long minutes, boolean withinAuto) {
        Instant other = kickoff.plus(minutes, ChronoUnit.MINUTES);
        assertThat(MatchMappingScoreSupport.withinCandidateWindow(kickoff, other))
                .isEqualTo(minutes <= 180);

        if (minutes <= 180) {
            MatchEntity match = match(1L, 10L, 20L, 30L, "英超", "曼联", "切尔西", other);
            ScoredCandidate scored = MatchMappingScoreSupport.score(
                    request(10L, 20L, 30L, "英超", "曼联", "切尔西", kickoff),
                    match
            );
            assertThat(scored.hardReject()).isEqualTo(!withinAuto);
        }
    }

    @Test
    void insufficientMarginBlocksAuto() {
        ScoredCandidate top = new ScoredCandidate(
                1L, new BigDecimal("0.90"), java.util.List.of(), false, 5
        );
        ScoredCandidate second = new ScoredCandidate(
                2L, new BigDecimal("0.85"), java.util.List.of(), false, 5
        );
        assertThat(MatchMappingScoreSupport.canAutoConfirm(top, second)).isFalse();
    }

    private MatchMapRequestDto request(
            Long leagueId,
            Long homeTeamId,
            Long awayTeamId,
            String leagueName,
            String homeName,
            String awayName,
            Instant kickoffTime
    ) {
        return new MatchMapRequestDto(
                "THE_ODDS_API",
                "ext-1",
                "L1",
                "H1",
                "A1",
                leagueId,
                homeTeamId,
                awayTeamId,
                leagueName,
                homeName,
                awayName,
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
