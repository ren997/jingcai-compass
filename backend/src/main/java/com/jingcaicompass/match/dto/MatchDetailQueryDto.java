package com.jingcaicompass.match.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 公开比赛详情查询条件。 */
public record MatchDetailQueryDto(
        /** 持久化比赛 ID。 */
        @NotNull @Positive Long matchId
) {
}
