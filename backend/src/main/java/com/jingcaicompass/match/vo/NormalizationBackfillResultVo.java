package com.jingcaicompass.match.vo;

import com.jingcaicompass.match.dto.NormalizationBackfillResultDto;
import com.jingcaicompass.match.enums.NormalizationPendingReasonEnum;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 比赛标准化回填结果视图。
 *
 * @param businessDate 竞彩业务日
 * @param totalMatchCount 当日比赛总数
 * @param normalizedMatchCount 已补齐全部标准实体 ID 的比赛数
 * @param pendingMatchCount 仍有标准实体待确认的比赛数
 * @param failureCount 单场事务失败数
 * @param updatedMatchCount 本次实际更新比赛数
 * @param pendingReasonCounts 各待确认原因的比赛计数
 * @param failures 单场失败摘要
 */
public record NormalizationBackfillResultVo(
        LocalDate businessDate,
        int totalMatchCount,
        int normalizedMatchCount,
        int pendingMatchCount,
        int failureCount,
        int updatedMatchCount,
        Map<NormalizationPendingReasonEnum, Integer> pendingReasonCounts,
        List<NormalizationFailureVo> failures
) {

    /** 将内部回填报告转换为接口视图。 */
    public static NormalizationBackfillResultVo from(NormalizationBackfillResultDto source) {
        return new NormalizationBackfillResultVo(
                source.businessDate(),
                source.totalMatchCount(),
                source.normalizedMatchCount(),
                source.pendingMatchCount(),
                source.failureCount(),
                source.updatedMatchCount(),
                source.pendingReasonCounts(),
                source.failures().stream()
                        .map(NormalizationFailureVo::from)
                        .toList()
        );
    }
}
