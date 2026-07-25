package com.jingcaicompass.match.service;

import com.jingcaicompass.match.dto.NormalizationBackfillResultDto;
import java.time.LocalDate;

/** 对已存在的体彩比赛按业务日补齐标准联赛、主队和客队。 */
public interface MatchNormalizationBackfillService {

    NormalizationBackfillResultDto backfill(LocalDate businessDate);
}
