package com.jingcaicompass.match.support;

import com.jingcaicompass.match.dto.MatchMapRequestDto;
import com.jingcaicompass.match.entity.MatchEntity;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 比赛映射打分与硬拒绝判定（纯函数）。
 */
public final class MatchMappingScoreSupport {

    public static final long CANDIDATE_WINDOW_MINUTES = 180;
    public static final long AUTO_MAX_TIME_DIFF_MINUTES = 60;
    public static final BigDecimal AUTO_MIN_SCORE = new BigDecimal("0.85");
    public static final BigDecimal AUTO_MIN_MARGIN = new BigDecimal("0.10");

    private static final BigDecimal HOME_ID = new BigDecimal("0.35");
    private static final BigDecimal HOME_NAME = new BigDecimal("0.25");
    private static final BigDecimal AWAY_ID = new BigDecimal("0.35");
    private static final BigDecimal AWAY_NAME = new BigDecimal("0.25");
    private static final BigDecimal LEAGUE_ID = new BigDecimal("0.15");
    private static final BigDecimal LEAGUE_NAME = new BigDecimal("0.10");
    private static final BigDecimal TIME_15 = new BigDecimal("0.15");
    private static final BigDecimal TIME_60 = new BigDecimal("0.10");
    private static final BigDecimal TIME_180 = new BigDecimal("0.05");

    private MatchMappingScoreSupport() {
    }

    /** 是否落在 ±180 分钟候选时间窗内。 */
    public static boolean withinCandidateWindow(Instant requestKickoff, Instant matchKickoff) {
        if (requestKickoff == null || matchKickoff == null) {
            return false;
        }
        long absMinutes = Math.abs(Duration.between(requestKickoff, matchKickoff).toMinutes());
        return absMinutes <= CANDIDATE_WINDOW_MINUTES;
    }

    public static ScoredCandidate score(MatchMapRequestDto request, MatchEntity match) {
        List<String> reasons = new ArrayList<>();
        BigDecimal score = BigDecimal.ZERO;

        boolean homeIdMatch = idsEqual(request.homeTeamId(), match.getHomeTeamId());
        boolean awayIdMatch = idsEqual(request.awayTeamId(), match.getAwayTeamId());
        boolean homeNameMatch = namesEqual(request.homeTeamName(), match.getHomeTeamName());
        boolean awayNameMatch = namesEqual(request.awayTeamName(), match.getAwayTeamName());

        boolean reversedById = idsEqual(request.homeTeamId(), match.getAwayTeamId())
                && idsEqual(request.awayTeamId(), match.getHomeTeamId())
                && request.homeTeamId() != null
                && request.awayTeamId() != null;
        boolean reversedByName = namesEqual(request.homeTeamName(), match.getAwayTeamName())
                && namesEqual(request.awayTeamName(), match.getHomeTeamName())
                && hasText(request.homeTeamName())
                && hasText(request.awayTeamName())
                && !homeNameMatch
                && !awayNameMatch;

        if (homeIdMatch) {
            score = score.add(HOME_ID);
            reasons.add("HOME_ID");
        } else if (homeNameMatch) {
            score = score.add(HOME_NAME);
            reasons.add("HOME_NAME");
        }

        if (awayIdMatch) {
            score = score.add(AWAY_ID);
            reasons.add("AWAY_ID");
        } else if (awayNameMatch) {
            score = score.add(AWAY_NAME);
            reasons.add("AWAY_NAME");
        }

        if (idsEqual(request.leagueId(), match.getLeagueId())) {
            score = score.add(LEAGUE_ID);
            reasons.add("LEAGUE_ID");
        } else if (namesEqual(request.leagueName(), match.getLeagueName())) {
            score = score.add(LEAGUE_NAME);
            reasons.add("LEAGUE_NAME");
        }

        long absMinutes = Math.abs(Duration.between(request.kickoffTime(), match.getKickoffTime()).toMinutes());
        if (absMinutes <= 15) {
            score = score.add(TIME_15);
            reasons.add("TIME_LE_15");
        } else if (absMinutes <= 60) {
            score = score.add(TIME_60);
            reasons.add("TIME_LE_60");
        } else if (absMinutes <= CANDIDATE_WINDOW_MINUTES) {
            score = score.add(TIME_180);
            reasons.add("TIME_LE_180");
        }

        boolean leagueConflict = request.leagueId() != null
                && match.getLeagueId() != null
                && !request.leagueId().equals(match.getLeagueId());
        if (leagueConflict) {
            reasons.add("LEAGUE_CONFLICT");
        }
        if (reversedById || reversedByName) {
            reasons.add("HOME_AWAY_REVERSED");
        }
        if (absMinutes > AUTO_MAX_TIME_DIFF_MINUTES) {
            reasons.add("TIME_OVER_AUTO_LIMIT");
        }

        boolean hardReject = leagueConflict
                || reversedById
                || reversedByName
                || absMinutes > AUTO_MAX_TIME_DIFF_MINUTES;

        return new ScoredCandidate(
                match.getId(),
                score.setScale(4, RoundingMode.HALF_UP),
                List.copyOf(reasons),
                hardReject,
                absMinutes
        );
    }

    public static boolean canAutoConfirm(ScoredCandidate top, ScoredCandidate second) {
        if (top == null || top.hardReject()) {
            return false;
        }
        if (top.score().compareTo(AUTO_MIN_SCORE) < 0) {
            return false;
        }
        if (second == null) {
            return true;
        }
        BigDecimal margin = top.score().subtract(second.score());
        return margin.compareTo(AUTO_MIN_MARGIN) >= 0;
    }

    private static boolean idsEqual(Long left, Long right) {
        return left != null && Objects.equals(left, right);
    }

    private static boolean namesEqual(String left, String right) {
        String leftKey = NameNormalizationSupport.normalizedKey(left);
        String rightKey = NameNormalizationSupport.normalizedKey(right);
        return !leftKey.isEmpty() && leftKey.equals(rightKey);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record ScoredCandidate(
            Long matchId,
            BigDecimal score,
            List<String> reasons,
            boolean hardReject,
            long timeDiffMinutes
    ) {
    }
}
