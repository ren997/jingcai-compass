package com.jingcaicompass.match.service;

import com.jingcaicompass.match.dto.NormalizationBackfillResultDto;
import java.time.LocalDate;

/** 对已存在的体彩比赛按业务日补齐标准联赛、主队和客队。 */
public interface MatchNormalizationBackfillService {

    /**
     * 仅处理数据库已有比赛，逐场独立事务补齐空的标准实体 ID。
     *
     * @param businessDate 竞彩业务日
     * @return 标准化完成、待确认和失败统计
     */
    NormalizationBackfillResultDto backfill(LocalDate businessDate);
}
