package com.jingcaicompass.backtest.vo;

import com.jingcaicompass.backtest.enums.BacktestRunStatusEnum;
import com.jingcaicompass.backtest.enums.BacktestStageEnum;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 一次回测的定义 + 进度 + 版本锚点。
 *
 * <p>列表与详情共用一个 VO：两者字段集合只差 {@code parameterSets}，分成两个类型必然出现
 * 「列表补了字段、详情忘了补」这类不一致，而前端两个页面读的是同一份查询缓存。</p>
 *
 * @param progressRatio 已处理 / 总场次；总数为 0 时为 0，**不要**用完成度推断状态
 *                      （{@code PARTIAL} 也是终态，但 ratio 可能小于 1）
 */
public record BacktestRunVo(
        Long id,
        String runKey,
        String runName,
        BacktestRunStatusEnum runStatus,
        BacktestStageEnum runStage,
        String operatorUsername,
        LocalDate dateFrom,
        LocalDate dateTo,
        List<String> leagueNames,
        Integer decisionLeadMinutes,
        List<Map<String, Object>> parameterSets,
        String providerCode,
        String modelVersion,
        String selectorVersion,
        String aggregationVersion,
        String builderVersion,
        String gitSha,
        Integer totalMatchCount,
        Integer processedMatchCount,
        Integer archiveReusedCount,
        Integer archiveFetchedCount,
        Integer archiveFailedCount,
        Integer caseCount,
        Integer estimateQuotaCost,
        Integer quotaCost,
        Boolean cancelRequested,
        Integer resumeCount,
        String errorCode,
        String errorMessage,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        Instant lastHeartbeatAt,
        BigDecimal progressRatio
) {

    public BacktestRunVo {
        leagueNames = leagueNames == null ? List.of() : List.copyOf(leagueNames);
        parameterSets = parameterSets == null ? List.of() : List.copyOf(parameterSets);
    }

    /** 进度比例，保留 4 位小数；没有总数时返回 0。 */
    public static BigDecimal ratio(Integer processed, Integer total) {
        if (total == null || total <= 0 || processed == null) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(Math.min(processed, total))
                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
    }
}
