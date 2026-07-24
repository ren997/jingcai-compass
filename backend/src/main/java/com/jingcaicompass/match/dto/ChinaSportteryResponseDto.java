package com.jingcaicompass.match.dto;

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
            OddsDto had,
            OddsDto hhad
    ) {
    }

    /**
     * 胜平负或让球胜平负盘口；HAD 可无 goalLine，HHAD 通常带 goalLine。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OddsDto(
            String goalLine,
            String h,
            String d,
            String a
    ) {
    }
}
