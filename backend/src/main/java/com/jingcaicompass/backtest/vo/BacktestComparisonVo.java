package com.jingcaicompass.backtest.vo;

import java.util.List;

/**
 * 参数组对比。
 *
 * <p>{@code bestGroupKey} 的判据固定为「{@code ALL} 口径下 ROI 高者优先；ROI 相同看
 * Brier 低者；再相同看 settled 样本多者」，并且把理由一并返回。不返回理由时，
 * 前端会把「样本量差 5 倍的两组」并排展示而不做任何提示。</p>
 *
 * <p>没有可比较的组时 {@code bestGroupKey} 为 null，而不是返回第一个组——
 * 「只有一个组」与「多组里选了最优」是两件事。</p>
 */
public record BacktestComparisonVo(
        Long runId,
        List<String> groupKeys,
        String bestGroupKey,
        String bestGroupReason,
        List<BacktestMetricsVo> metrics
) {

    public BacktestComparisonVo {
        groupKeys = groupKeys == null ? List.of() : List.copyOf(groupKeys);
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
    }
}
