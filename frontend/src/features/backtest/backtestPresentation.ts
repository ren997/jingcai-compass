import type {
  BacktestCaseVo,
  BacktestDecisionStage,
  BacktestMarketScope,
  BacktestMetricsVo,
  BacktestRunStatus,
  BacktestRunVo,
  BacktestSkipReason,
  BacktestStage,
} from '../../services/backtest';
import { formatAsianLine } from '../matches/matchPresentation';

export const backtestRunStatusLabels: Record<BacktestRunStatus, string> = {
  QUEUED: '排队中',
  ARCHIVING: '补采历史赔率',
  EVALUATING: '逐步判定',
  SETTLING: '结算中',
  AGGREGATING: '汇总指标',
  SUCCEEDED: '成功',
  PARTIAL: '部分成功',
  FAILED: '失败',
  CANCELLED: '已取消',
  INTERRUPTED: '进程中断',
};

/**
 * 状态色标。
 *
 * <p>`PARTIAL` 刻意不算成功色：它表示「样本可用但补采有失败」，把它画成绿色会让
 * 「这次结论是基于残缺样本」这件事彻底消失。</p>
 */
export function backtestRunStatusTone(status: BacktestRunStatus) {
  if (status === 'SUCCEEDED') {
    return 'success';
  }
  if (status === 'PARTIAL') {
    return 'partial';
  }
  if (status === 'FAILED') {
    return 'failed';
  }
  if (status === 'INTERRUPTED') {
    return 'warning';
  }
  if (status === 'CANCELLED') {
    return 'muted';
  }
  return 'running';
}

export const backtestStageLabels: Record<BacktestStage, string> = {
  NONE: '未开始',
  ARCHIVING: '补采历史赔率',
  EVALUATING: '逐步判定',
  SETTLING: '结算',
  AGGREGATING: '汇总指标',
  DONE: '已结束',
};

/**
 * 判定阶段文案。
 *
 * <p>每个非 `DECIDED` 值都要能独立读通，因为它们都是**正常业务结局**而不是错误：
 * 庄家不足、市场打架、临场太平衡都会让一场比赛没有结论，而这不是失败。
 */
export const backtestDecisionStageLabels: Record<BacktestDecisionStage, string> = {
  NOT_SCHEDULED: '非可生成状态',
  KICKOFF_PASSED: '已开赛',
  MISSING_ROWS: '该时点无可交易让球行',
  INVALID_ROWS: '水位或采集时刻不可用',
  INSUFFICIENT_HANDICAP: '让球庄家不足',
  NO_TOTALS_CONSTRAINT: '缺大小球主盘',
  INFEASIBLE: '两约束无解',
  INCONSISTENT: '两市场定价不自洽',
  NO_FROZEN_ROW: '共识线无可冻结真实行',
  FROZEN_TOTALS_LINE_MISMATCH: '冻结行大小球线非主盘',
  BOTH_MARKETS_REJECTED: '两市场都没方向',
  DECIDED: '已出方向',
};

/** 判定阶段的进一步解释，收进折叠区。 */
export const backtestDecisionStageHints: Partial<Record<BacktestDecisionStage, string>> = {
  INSUFFICIENT_HANDICAP: '报价庄家不足两家，无法形成去水共识主盘。',
  NO_TOTALS_CONSTRAINT: '只有一条方程，λ 无法反解。',
  INFEASIBLE: '主盘反解出的 λ 在数值上不可行。',
  INCONSISTENT: '残差或邻线回投超限，两个市场在互相打架。',
  NO_FROZEN_ROW: '共识线上没有真实快照行，公平价无法成交。',
  FROZEN_TOTALS_LINE_MISMATCH: '选中的冻结行大小球线不是共识主盘线，会算出错位的 λ。',
  BOTH_MARKETS_REJECTED: '走到置信度阶段但两侧都被阈值挡掉——临场越平衡，方向越明确也判低置信。',
};

export const backtestSkipReasonLabels: Record<BacktestSkipReason, string> = {
  NOT_SCHEDULED: '不处于可生成状态',
  KICKOFF_PASSED: '已开赛',
  MISSING_SPORTTERY_MARKET: '缺体彩让球与 SP',
  INVALID_SPORTTERY_MARKET: '体彩 SP 非正数',
  MISSING_CONFIRMED_ASIAN_MARKET: '缺该场亚盘快照',
  INVALID_ASIAN_MARKET: '亚盘水位非正数',
  INSUFFICIENT_ASIAN_BOOKMAKERS: '庄家不足两家',
  INSUFFICIENT_ASIAN_CONSTRAINTS: '约束不足反解 λ',
  INCONSISTENT_ASIAN_MARKETS: '两市场不自洽',
  NO_ASIAN_SNAPSHOT_ON_CONSENSUS_LINE: '共识线无快照行',
  NO_ASIAN_SNAPSHOT_ON_TOTALS_CONSENSUS_LINE: '大小球共识线无快照行',
  MARKET_DISPERSION_TOO_HIGH: '跨庄分歧过大',
  LOW_CONFIDENCE_ASIAN_MARKETS: '两市场都没方向',
};

export const backtestMarketScopeLabels: Record<BacktestMarketScope, string> = {
  ALL: '合计',
  ASIAN_HANDICAP: '让球',
  ASIAN_TOTAL_GOALS: '大小球',
};

export function backtestRunStatusLabel(status: BacktestRunStatus | null | undefined) {
  return status ? backtestRunStatusLabels[status] : '未知状态';
}

export function decisionStageLabel(stage: BacktestDecisionStage | null | undefined) {
  return stage ? backtestDecisionStageLabels[stage] : '—';
}

export function skipReasonLabel(reason: BacktestSkipReason | null | undefined) {
  return reason ? backtestSkipReasonLabels[reason] : '—';
}

/** 百分比；样本为 0 时后端给 null，此处保持「—」而不是 0%，两者含义完全不同。 */
export function formatRate(value: number | null | undefined, digits = 2) {
  if (value === null || value === undefined) {
    return '—';
  }
  return `${(value * 100).toFixed(digits)}%`;
}

/**
 * Brier 分数：无量纲，**不乘 100**。
 *
 * <p>把它当比率画成百分比（0.2025 → 20.25%）是最容易犯的错：Brier 的取值范围本来就是
 * 0~1 的平方误差，没有「百分之」的含义，一旦乘了 100 就会和命中率混成同一个视觉量纲。</p>
 */
export function formatScore(value: number | null | undefined, digits = 4) {
  if (value === null || value === undefined) {
    return '—';
  }
  return value.toFixed(digits);
}

/** 带符号金额；0 与 null 要分开显示。 */
export function formatProfit(value: number | null | undefined) {
  if (value === null || value === undefined) {
    return '—';
  }
  return `${value >= 0 ? '+' : ''}${value.toFixed(3)}`;
}

/**
 * Brier 差值：`market − model`，**正数才表示模型更好**。
 *
 * <p>负值是合法结论，不取绝对值也不隐藏——把「不如市场」显示成「持平」是最容易骗过自己的
 * 一种美化。</p>
 */
export function formatBrierDelta(value: number | null | undefined) {
  if (value === null || value === undefined) {
    return '—';
  }
  return `${value > 0 ? '+' : ''}${value.toFixed(4)}`;
}

/**
 * Brier 差值的文字判断。
 *
 * <p>刻意不用颜色：这一页已经用「正红负绿」表示收益，再用红色表示「模型不如市场」
 * 会让同一个红有两种相反含义。文字判断没有这种歧义。</p>
 */
export function brierDeltaVerdict(value: number | null | undefined) {
  if (value === null || value === undefined) {
    return '暂无样本';
  }
  if (value > 0) {
    return '优于市场';
  }
  if (value < 0) {
    return '不如市场';
  }
  return '持平';
}

/** 进度文案：归档阶段看场次，判定及之后看样本数，终态看结果。 */
export function backtestProgressText(run: Pick<
  BacktestRunVo,
  'runStatus' | 'runStage' | 'processedMatchCount' | 'totalMatchCount' | 'caseCount' | 'progressRatio'
>) {
  if (run.runStatus === 'QUEUED') {
    return '排队中，等待 worker 抢单';
  }
  // 归档结束后 processed 已等于总数，此时再显示「10/10（100%）」会让人以为整件事做完了。
  // 判定与之后的阶段真正的进展是新产出的样本条数。
  if (
    run.runStatus === 'EVALUATING'
    || run.runStatus === 'SETTLING'
    || run.runStatus === 'AGGREGATING'
  ) {
    return `${backtestStageLabels[run.runStage]} · 已产出 ${run.caseCount ?? 0} 条样本`;
  }
  if (run.totalMatchCount === null || run.totalMatchCount === 0) {
    const statusLabel = backtestRunStatusLabel(run.runStatus);
    const stageLabel = backtestStageLabels[run.runStage];
    // 状态名与阶段名经常是同一个词（ARCHIVING 两者都是「补采历史赔率」），
    // 不加判断就会显示成「补采历史赔率 · 补采历史赔率」。
    return statusLabel === stageLabel ? stageLabel : `${statusLabel} · ${stageLabel}`;
  }
  const processed = run.processedMatchCount ?? 0;
  return `${backtestStageLabels[run.runStage]} · ${processed}/${run.totalMatchCount} 场`
    + `（${Math.round(run.progressRatio * 100)}%）`;
}

/** 取某参数组在某口径下的指标；不存在时返回 undefined 而不是造一条空指标。 */
export function scopeMetrics(
  metrics: readonly BacktestMetricsVo[],
  groupKey: string,
  scope: BacktestMarketScope,
) {
  return metrics.find((item) => item.groupKey === groupKey && item.marketScope === scope);
}

/** 明确打印口径版本：两份结论只有在四个版本号都相同的时候才可比。 */
export function backtestVersionSummary(run: BacktestRunVo) {
  return [
    `模型 ${run.modelVersion}`,
    `选行 ${run.selectorVersion}`,
    `聚合 ${run.aggregationVersion}`,
    `构建 ${run.builderVersion}`,
  ].join(' · ');
}

/** 参数组的四个阈值摘要，用于表格列。 */
export function formatParameterSet(parameterSet: Record<string, unknown>) {
  const pick = (key: string) => {
    const value = parameterSet[key];
    return typeof value === 'number' ? value.toFixed(4) : String(value ?? '—');
  };
  return `margin ${pick('highConfidenceMargin')}/${pick('mediumConfidenceMargin')}`
    + ` · signal ${pick('highDirectionSignal')}/${pick('mediumDirectionSignal')}`;
}

/**
 * 逐场结论的一句话：优先给方向，没方向的给卡在哪一步。
 *
 * <p>方向文案统一在 `formatAsianLine` 与「平手」的写法上，与首页复盘同源——
 * 回测页自己发明一套线写法，读的人就得在两套口径之间翻译。</p>
 */
export function backtestCaseVerdict(item: BacktestCaseVo) {
  const parts: string[] = [];
  if (item.handicapPick) {
    const side = item.handicapPick === 'HOME_COVER' ? '主' : '客';
    const line = item.handicapPick === 'HOME_COVER'
      ? item.frozenHandicapLine
      : item.frozenHandicapLine === null ? null : -item.frozenHandicapLine;
    parts.push(`${side} ${line === 0 ? '平手' : formatAsianLine(line)}`);
  }
  if (item.totalsPick) {
    parts.push(`${item.totalsPick === 'OVER' ? '大' : '小'} ${formatAsianLine(item.frozenTotalLine)}`);
  }
  return parts.length > 0 ? parts.join(' / ') : decisionStageLabel(item.decisionStage);
}
