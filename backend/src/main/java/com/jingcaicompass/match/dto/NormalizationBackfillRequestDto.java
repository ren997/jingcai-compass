package com.jingcaicompass.match.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/** 按竞彩业务日回填已有比赛的标准实体。 */
public record NormalizationBackfillRequestDto(
        @NotNull LocalDate businessDate
) {
}
