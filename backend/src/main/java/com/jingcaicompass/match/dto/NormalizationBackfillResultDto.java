package com.jingcaicompass.match.dto;

import com.jingcaicompass.match.enums.NormalizationPendingReasonEnum;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 按业务日执行的比赛标准化回填报告。
 *
 * @param businessDate 竞彩业务日
 * @param totalMatchCount 当日比赛总数
 * @param normalizedMatchCount 已补齐全部标准实体 ID 的比赛数
 * @param pendingMatchCount 仍有标准实体待确认的比赛数
 * @param failureCount 单场事务失败数
 * @param updatedMatchCount 本次实际更新比赛数
 * @param pendingReasonCounts 各待确认原因的比赛计数
 * @param failures 最多二十条单场失败摘要
 */
public record NormalizationBackfillResultDto(
        LocalDate businessDate,
        int totalMatchCount,
        int normalizedMatchCount,
        int pendingMatchCount,
        int failureCount,
        int updatedMatchCount,
        Map<NormalizationPendingReasonEnum, Integer> pendingReasonCounts,
        List<NormalizationFailureDto> failures
) {

    /** 创建无比赛时的空报告。 */
    public static NormalizationBackfillResultDto empty(LocalDate businessDate) {
        return new NormalizationBackfillResultDto(
                businessDate,
                0,
                0,
                0,
                0,
                0,
                Map.of(),
                List.of()
        );
    }
}
