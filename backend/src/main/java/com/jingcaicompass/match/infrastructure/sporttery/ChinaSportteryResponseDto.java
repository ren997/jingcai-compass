package com.jingcaicompass.match.infrastructure.sporttery;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ChinaSportteryResponseDto(
        boolean success,
        String errorCode,
        String errorMessage,
        ValueDto value
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ValueDto(
            List<MatchGroupDto> matchInfoList
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MatchGroupDto(
            String businessDate,
            List<MatchDto> subMatchList
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MatchDto(
            long matchId,
            String matchNumStr,
            String leagueAbbName,
            String homeTeamAbbName,
            String awayTeamAbbName,
            String matchDate,
            String matchTime,
            String matchStatus,
            HandicapOddsDto hhad
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HandicapOddsDto(
            String goalLine
    ) {
    }
}
