import { requestApi } from './http';
import type { PageResult } from './public';

/**
 * run 生命周期状态。
 *
 * <p>顺序即推进顺序：`QUEUED → ARCHIVING → EVALUATING → SETTLING → AGGREGATING → 终态`。
 * `INTERRUPTED` 是唯一可回退的中间态（进程被中断，`resume` 把它送回 `QUEUED`），
 * 其余终态不可再改——所以筛选器里要把它和终态分开摆，不能让用户以为能「重跑」。
 */
export const BACKTEST_RUN_STATUSES = [
  'QUEUED',
  'ARCHIVING',
  'EVALUATING',
  'SETTLING',
  'AGGREGATING',
  'SUCCEEDED',
  'PARTIAL',
  'FAILED',
  'CANCELLED',
  'INTERRUPTED',
] as const;

export type BacktestRunStatus = (typeof BACKTEST_RUN_STATUSES)[number];

/**
 * 公开端可见的状态。
 *
 * <p>只含 `SUCCEEDED` / `PARTIAL`，与后端 `PublicBacktestController.PUBLISHABLE_STATUSES` 一致。
 * 这里保留一份是为了在公开页面上不把「还在跑」的 run 渲染成空列表——
 * 后端对未定型的 run 一律返回「不存在」，前端得能解释这个 404。
 */
export const PUBLISHABLE_BACKTEST_STATUSES = ['SUCCEEDED', 'PARTIAL'] as const;

export function isPublishableBacktestStatus(status: BacktestRunStatus) {
  return (PUBLISHABLE_BACKTEST_STATUSES as readonly string[]).includes(status);
}

/** 当前正在推进的中间态；只有这些状态值得轮询进度。 */
const ACTIVE_BACKTEST_STATUSES: readonly BacktestRunStatus[] = [
  'QUEUED',
  'ARCHIVING',
  'EVALUATING',
  'SETTLING',
  'AGGREGATING',
];

export function isActiveBacktestStatus(status: BacktestRunStatus) {
  return ACTIVE_BACKTEST_STATUSES.includes(status);
}

/** 可续跑的状态：只有进程中断的 run 能重排队。 */
export function canResumeBacktestRun(status: BacktestRunStatus) {
  return status === 'INTERRUPTED';
}

/** 可取消的状态：中间态都能请求取消，终态不需要。 */
export function canCancelBacktestRun(status: BacktestRunStatus) {
  return isActiveBacktestStatus(status);
}

export const BACKTEST_STAGES = [
  'NONE',
  'ARCHIVING',
  'EVALUATING',
  'SETTLING',
  'AGGREGATING',
  'DONE',
] as const;

export type BacktestStage = (typeof BACKTEST_STAGES)[number];

/**
 * 单场判定卡在哪一步。
 *
 * <p>这是「为什么这场没结论」的唯一解释来源，不是错误码：`DECIDED` 之外的每个值都是
 * 正常业务结局（庄家不够、市场打架、临场太平衡），把它们当失败去重试只会重复同一件事。
 */
export const BACKTEST_DECISION_STAGES = [
  'NOT_SCHEDULED',
  'KICKOFF_PASSED',
  'MISSING_ROWS',
  'INVALID_ROWS',
  'INSUFFICIENT_HANDICAP',
  'NO_TOTALS_CONSTRAINT',
  'INFEASIBLE',
  'INCONSISTENT',
  'NO_FROZEN_ROW',
  'FROZEN_TOTALS_LINE_MISMATCH',
  'BOTH_MARKETS_REJECTED',
  'DECIDED',
] as const;

export type BacktestDecisionStage = (typeof BACKTEST_DECISION_STAGES)[number];

/** 生成侧跳过原因，与生产 `BaselinePredictionSkipReasonEnum` 同名。 */
export const BACKTEST_SKIP_REASONS = [
  'NOT_SCHEDULED',
  'KICKOFF_PASSED',
  'MISSING_SPORTTERY_MARKET',
  'INVALID_SPORTTERY_MARKET',
  'MISSING_CONFIRMED_ASIAN_MARKET',
  'INVALID_ASIAN_MARKET',
  'INSUFFICIENT_ASIAN_BOOKMAKERS',
  'INSUFFICIENT_ASIAN_CONSTRAINTS',
  'INCONSISTENT_ASIAN_MARKETS',
  'NO_ASIAN_SNAPSHOT_ON_CONSENSUS_LINE',
  'NO_ASIAN_SNAPSHOT_ON_TOTALS_CONSENSUS_LINE',
  'MARKET_DISPERSION_TOO_HIGH',
  'LOW_CONFIDENCE_ASIAN_MARKETS',
] as const;

export type BacktestSkipReason = (typeof BACKTEST_SKIP_REASONS)[number];

/** 指标口径。三个 scope 各自独立计算，`ALL` 不是两个亚盘市场相加。 */
export const BACKTEST_MARKET_SCOPES = ['ALL', 'ASIAN_HANDICAP', 'ASIAN_TOTAL_GOALS'] as const;

export type BacktestMarketScope = (typeof BACKTEST_MARKET_SCOPES)[number];

export type BacktestOrientation = 'HOME_COVER' | 'AWAY_COVER' | 'OVER' | 'UNDER';

/**
 * 一场样本的赛果状态。
 *
 * <p>只有三个值，与 `settlements.settlement_status` 刻意不同：这里回答「这行怎么算收益」，
 * 市场级输赢另有两列。`VOID` 是「比赛作废」而不是「走盘」——走盘在亚盘里是常态
 * （四分之一盘打平），它属于 `FINAL` 下的 `PUSH`。</p>
 *
 * <p>这里曾经写成 `PENDING | SETTLED | VOID | NO_RESULT | FAILED`：五个值里有四个后端根本
 * 不产生，而真实值 `FINAL` 反而不在集合里。类型和实现各说各话时不会有任何编译错误——
 * 只有读代码的人会被误导，于是 `resultStatus === 'PENDING'` 之外的分支全凭猜。</p>
 */
export type BacktestResultStatus = 'PENDING' | 'FINAL' | 'VOID';

export type BacktestSettlementStatus = 'HIT' | 'MISS' | 'PUSH' | 'HALF_WIN' | 'HALF_LOSS' | 'VOID';

/**
 * 一个参数组：四个置信度阈值。
 *
 * <p>方向由模型概率决定、置信度由冻结行报价决定，两者**不同源**——所以这四个值调的是
 * 「什么样的报价才算有信心」，而不是「模型多有把握」。改这四个数不会改变方向，只会改变发不发。
 */
export type BacktestParameterSet = {
  groupKey: string;
  highConfidenceMargin: number;
  mediumConfidenceMargin: number;
  highDirectionSignal: number;
  mediumDirectionSignal: number;
};

export type BacktestRunListQuery = {
  runStatus?: string;
  dateFrom?: string;
  dateTo?: string;
  page?: number;
  pageSize?: number;
};

export type BacktestRunCreateRequest = {
  runName: string;
  dateFrom: string;
  dateTo: string;
  leagueNames?: string[];
  decisionLeadMinutes?: number;
  parameterSets?: BacktestParameterSet[];
  confirmBudget?: boolean;
};

export type BacktestCaseQuery = {
  groupKey?: string;
  marketScope?: string;
  skipReason?: string;
  onlyPicked?: boolean;
  page?: number;
  pageSize?: number;
};

export type BacktestEstimateVo = {
  dateFrom: string;
  dateTo: string;
  matchCount: number;
  reusableArchiveCount: number;
  estimatedQuotaCost: number;
  estimatedSeconds: number;
  /** 为 true 时创建必须带 `confirmBudget`，否则后端拒绝。 */
  budgetConfirmRequired: boolean;
  parameterGroupCount: number;
  warnings: string[];
};

export type BacktestRunVo = {
  id: number;
  runKey: string;
  runName: string;
  runStatus: BacktestRunStatus;
  runStage: BacktestStage;
  operatorUsername: string | null;
  dateFrom: string;
  dateTo: string;
  leagueNames: string[];
  decisionLeadMinutes: number;
  /** 本次运行的参数组输入快照。指标表用它把 groupKey 翻译成四个阈值。 */
  parameterSets: BacktestParameterSet[];
  providerCode: string;
  modelVersion: string;
  selectorVersion: string;
  aggregationVersion: string;
  builderVersion: string;
  /** 公开端不返回，管理端用于把结论钉到某次部署。 */
  gitSha: string | null;
  totalMatchCount: number | null;
  processedMatchCount: number | null;
  archiveReusedCount: number | null;
  archiveFetchedCount: number | null;
  archiveFailedCount: number | null;
  caseCount: number | null;
  estimateQuotaCost: number | null;
  quotaCost: number | null;
  cancelRequested: boolean | null;
  resumeCount: number | null;
  errorCode: string | null;
  errorMessage: string | null;
  createdAt: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  lastHeartbeatAt: string | null;
  /** 0~1，后端已按 `min(processed,total)/total` 截断；无总数时为 0。 */
  progressRatio: number;
};

export type BacktestCaseVo = {
  id: number;
  runId: number;
  groupKey: string;
  externalMatchId: string;
  matchId: number | null;
  lotteryMatchNo: string | null;
  leagueName: string | null;
  decisionAt: string;
  kickoffTime: string;
  decisionStage: BacktestDecisionStage;
  skipReason: BacktestSkipReason | null;
  frozenBookmakerCode: string | null;
  frozenHandicapLine: number | null;
  frozenHomeOdds: number | null;
  frozenAwayOdds: number | null;
  frozenTotalLine: number | null;
  frozenOverOdds: number | null;
  frozenUnderOdds: number | null;
  frozenCapturedAt: string | null;
  handicapPick: 'HOME_COVER' | 'AWAY_COVER' | null;
  handicapConfidence: 'LOW' | 'MEDIUM' | 'HIGH' | null;
  totalsPick: 'OVER' | 'UNDER' | null;
  totalsConfidence: 'LOW' | 'MEDIUM' | 'HIGH' | null;
  handicapDispersion: number | null;
  totalsDispersion: number | null;
  lambdaHome: number | null;
  lambdaAway: number | null;
  /** 一律首边：让球为「主队赢盘」，大小球为「大球」。 */
  modelCoverProb: number | null;
  marketCoverProb: number | null;
  edgeCover: number | null;
  modelOverProb: number | null;
  marketOverProb: number | null;
  edgeOver: number | null;
  neighborMaxDeviation: number | null;
  maxResidual: number | null;
  /** λ 反解不可行原因；null 表示反解可行（此时仍可能因残差/邻线超限被拒） */
  infeasibilityReason: string | null;
  handicapGatePass: boolean | null;
  totalsGatePass: boolean | null;
  resultStatus: BacktestResultStatus;
  homeScore: number | null;
  awayScore: number | null;
  handicapStatus: BacktestSettlementStatus | null;
  handicapProfit: number | null;
  totalsStatus: BacktestSettlementStatus | null;
  totalsProfit: number | null;
  settledAt: string | null;
};

export type BacktestMetricsVo = {
  runId: number;
  groupKey: string;
  marketScope: BacktestMarketScope;
  caseCount: number;
  decidedCount: number;
  skippedCount: number;
  pickCount: number;
  /** 含走盘（走盘计 0.5，见 weightedHitRate）。 */
  settledCount: number;
  pushCount: number;
  halfWinCount: number;
  halfLossCount: number;
  hitCount: number;
  missCount: number;
  /** 分母不含走盘；样本为 0 时是 null 而不是 0。 */
  hitRate: number | null;
  weightedHitRate: number | null;
  profitSum: number | null;
  roi: number | null;
  yieldRate: number | null;
  brierModel: number | null;
  brierMarket: number | null;
  /** market − model：正数才表示模型优于市场。负值是合法结论。 */
  brierDelta: number | null;
  brierSampleSize: number;
  /**
   * 各跳过原因的场次数。
   *
   * <p>允许 null：这一列是 jsonb，后端一旦用原生 `@Select` 读取就会绕过 jsonb typeHandler
   * 读回 null（`autoResultMap` 只对 MyBatis-Plus 自己拼的 SQL 生效）。类型上声明成非空，
   * 只会让下游忘记判空，然后在渲染时炸掉整页——所以这里如实写 null。</p>
   */
  skipReasonCounts: Record<string, number> | null;
  metricsJson: Record<string, unknown> | null;
  computedAt: string | null;
};

export type BacktestComparisonVo = {
  runId: number;
  groupKeys: string[];
  bestGroupKey: string | null;
  /** 判定理由原文：要显示出来，否则「最优」只是一个无法反驳的断言。 */
  bestGroupReason: string | null;
  metrics: BacktestMetricsVo[];
};

export type BacktestLeagueVo = {
  leagueName: string;
  matchCount: number;
  archivedCount: number;
};

/** 创建前预估成本。创建动作必须让用户先看到这个结果。 */
export function estimateBacktestRun(request: BacktestRunCreateRequest, signal?: AbortSignal) {
  return requestApi<BacktestEstimateVo>('/api/admin/backtest/runs/estimate', {
    method: 'POST', body: request, signal, authenticated: true,
  });
}

/** 入队一次回测。 */
export function createBacktestRun(request: BacktestRunCreateRequest, signal?: AbortSignal) {
  return requestApi<BacktestRunVo>('/api/admin/backtest/runs', {
    method: 'POST', body: request, signal, authenticated: true,
  });
}

/** 管理端分页列表（含排队与失败的 run）。 */
export function fetchAdminBacktestRuns(query: BacktestRunListQuery, signal?: AbortSignal) {
  return requestApi<PageResult<BacktestRunVo>>('/api/admin/backtest/runs/list', {
    method: 'POST', body: query, signal, authenticated: true,
  });
}

export function fetchAdminBacktestRun(runId: number, signal?: AbortSignal) {
  return requestApi<BacktestRunVo>(`/api/admin/backtest/runs/${runId}`, {
    method: 'GET', signal, authenticated: true,
  });
}

/** 进度轮询。与详情返回同一对象，只关心其中的计数字段。 */
export function fetchAdminBacktestProgress(runId: number, signal?: AbortSignal) {
  return requestApi<BacktestRunVo>(`/api/admin/backtest/runs/${runId}/progress`, {
    method: 'GET', signal, authenticated: true,
  });
}

/** 请求取消：只置标志，已写样本保留。 */
export function cancelBacktestRun(runId: number, signal?: AbortSignal) {
  return requestApi<BacktestRunVo>(`/api/admin/backtest/runs/${runId}/cancel`, {
    method: 'POST', signal, authenticated: true,
  });
}

/** 续跑中断的 run。只有 `INTERRUPTED` 能续，其余状态后端会拒绝。 */
export function resumeBacktestRun(runId: number, signal?: AbortSignal) {
  return requestApi<BacktestRunVo>(`/api/admin/backtest/runs/${runId}/resume`, {
    method: 'POST', signal, authenticated: true,
  });
}

export function fetchAdminBacktestCases(
  runId: number,
  query: BacktestCaseQuery,
  signal?: AbortSignal,
) {
  return requestApi<PageResult<BacktestCaseVo>>(`/api/admin/backtest/runs/${runId}/cases`, {
    method: 'POST', body: query, signal, authenticated: true,
  });
}

export function fetchAdminBacktestMetrics(runId: number, signal?: AbortSignal) {
  return requestApi<BacktestMetricsVo[]>(`/api/admin/backtest/runs/${runId}/metrics`, {
    method: 'GET', signal, authenticated: true,
  });
}

export function fetchAdminBacktestComparison(runId: number, signal?: AbortSignal) {
  return requestApi<BacktestComparisonVo>(`/api/admin/backtest/runs/${runId}/compare`, {
    method: 'GET', signal, authenticated: true,
  });
}

/** 联赛候选，带该区间内已归档场次数，用来判断复用率。 */
export function fetchAdminBacktestLeagues(
  dateFrom?: string,
  dateTo?: string,
  signal?: AbortSignal,
) {
  const params = new URLSearchParams();
  if (dateFrom) {
    params.set('dateFrom', dateFrom);
  }
  if (dateTo) {
    params.set('dateTo', dateTo);
  }
  const suffix = params.toString() ? `?${params.toString()}` : '';
  return requestApi<BacktestLeagueVo[]>(`/api/admin/backtest/leagues${suffix}`, {
    method: 'POST', signal, authenticated: true,
  });
}

/** 公开列表：只含已产出结论的 run。 */
export function fetchPublicBacktestRuns(query: BacktestRunListQuery, signal?: AbortSignal) {
  return requestApi<PageResult<BacktestRunVo>>('/api/public/backtest/runs/list', {
    method: 'POST', body: query, signal,
  });
}

/** 公开详情；未产出结论的 run 返回「不存在」，不透露它存在。 */
export function fetchPublicBacktestRun(runId: number, signal?: AbortSignal) {
  return requestApi<BacktestRunVo>('/api/public/backtest/runs/detail', {
    method: 'POST', body: { runId }, signal,
  });
}

export function fetchPublicBacktestMetrics(runId: number, signal?: AbortSignal) {
  return requestApi<BacktestMetricsVo[]>('/api/public/backtest/metrics', {
    method: 'POST', body: { runId }, signal,
  });
}

export function fetchPublicBacktestComparison(runId: number, signal?: AbortSignal) {
  return requestApi<BacktestComparisonVo>('/api/public/backtest/compare', {
    method: 'POST', body: { runId }, signal,
  });
}

export function fetchPublicBacktestCases(
  runId: number,
  query: BacktestCaseQuery,
  signal?: AbortSignal,
) {
  return requestApi<PageResult<BacktestCaseVo>>('/api/public/backtest/cases', {
    method: 'POST', body: { runId, ...query }, signal,
  });
}
