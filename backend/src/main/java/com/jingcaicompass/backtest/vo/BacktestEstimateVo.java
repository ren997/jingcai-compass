package com.jingcaicompass.backtest.vo;

import java.time.LocalDate;
import java.util.List;

/**
 * 创建前的成本预估。
 *
 * <p>「预估」必须与真实执行共用同一套候选场次查询，否则它会变成一句安慰：
 * 候选口径一旦分叉，预估说 300 场、实际跑 2400 场，而用户是在看到预估之后才点的确认。</p>
 *
 * @param matchCount          候选场次数（决定归档调用次数的上限）
 * @param reusableArchiveCount 已有可复用归档的场次数（这些不会消耗调用）
 * @param estimatedQuotaCost  预计实际调用次数
 * @param estimatedSeconds    预计墙钟秒数（NovaAPI 固定 2s/次限速，下限 = 调用次数 × 2）
 * @param budgetConfirmRequired 是否超过阈值、需要 {@code confirmBudget=true}
 * @param parameterGroupCount 参数组数量（场次数 × 组数 = 判定次数，不影响调用次数）
 * @param warnings            越界或不可比的提示
 */
public record BacktestEstimateVo(
        LocalDate dateFrom,
        LocalDate dateTo,
        int matchCount,
        int reusableArchiveCount,
        int estimatedQuotaCost,
        int estimatedSeconds,
        boolean budgetConfirmRequired,
        int parameterGroupCount,
        List<String> warnings
) {

    public BacktestEstimateVo {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
