package com.jingcaicompass.match.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/** 比赛标准实体尚未确认的字段原因。 */
@Getter
public enum NormalizationPendingReasonEnum {
    LEAGUE_PENDING("LEAGUE_PENDING"),
    HOME_TEAM_PENDING("HOME_TEAM_PENDING"),
    AWAY_TEAM_PENDING("AWAY_TEAM_PENDING");

    @JsonValue
    private final String code;

    NormalizationPendingReasonEnum(String code) {
        this.code = code;
    }
}
