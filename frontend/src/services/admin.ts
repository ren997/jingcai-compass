import { requestApi } from './http';
import type { AsianHandicapPick, PredictionAsianMarketVo, TotalGoalsPick } from './public';
import type { AdminSession } from '../types/api';
import type { PageResult } from './public';

export type AdminLoginDto = {
  username: string;
  password: string;
};

export const PROVIDER_DATA_TYPES = ['SPORTTERY_POOL', 'SPORTTERY_RESULT', 'ASIAN_ODDS', 'MANUAL_RESULT', 'OTHER'] as const;
export type ProviderDataType = (typeof PROVIDER_DATA_TYPES)[number];

export const SYNC_STATUSES = ['RUNNING', 'SUCCESS', 'FAILED', 'PARTIAL'] as const;
export type SyncStatus = (typeof SYNC_STATUSES)[number];

export const PREDICTION_STATUSES = ['PUBLISHED', 'LOCKED'] as const;
export type PredictionStatus = (typeof PREDICTION_STATUSES)[number];

export type AdminDraftPredictionListQuery = {
  lotteryDate?: string;
  modelVersion?: string;
  pageNo: number;
  pageSize: number;
};

export const LOCK_DIAGNOSTICS = ['OVERDUE', 'SCHEDULED', 'LOCKED'] as const;
export type LockDiagnostic = (typeof LOCK_DIAGNOSTICS)[number];

/**
 * 结算诊断码与市场解耦：市场信息放在 AdminStatusDiagnostic.marketType 上。
 *
 * <p>按市场硬编码诊断码（SETTLEMENT_MISSING_HAD 之类）会让"每加一个市场就加一个码"，
 * 而诊断语义本身与市场无关，只是恰好发生在某个市场上。
 */
export const SETTLEMENT_DIAGNOSTICS = [
  'AWAITING_RESULT',
  'SETTLEMENT_MISSING',
  'SETTLEMENT_STALE',
] as const;
export type SettlementDiagnostic = (typeof SETTLEMENT_DIAGNOSTICS)[number];

/** 与后端 MarketTypeEnum 一致；亚盘市场由亚盘预测使用。 */
export const ADMIN_SETTLEMENT_MARKETS = [
  'HAD',
  'HHAD',
  'ASIAN_HANDICAP',
  'ASIAN_TOTAL_GOALS',
] as const;
export type AdminSettlementMarketType = (typeof ADMIN_SETTLEMENT_MARKETS)[number];

/** 亚盘存在走盘与半赢半输，竞彩只有胜/负/走。 */
export const ADMIN_SETTLEMENT_STATUSES = [
  'PENDING',
  'HIT',
  'MISS',
  'PUSH',
  'HALF_WIN',
  'HALF_LOSS',
  'VOID',
] as const;
export type AdminSettlementStatus = (typeof ADMIN_SETTLEMENT_STATUSES)[number];

export type AdminSyncRunListQuery = {
  providerCode?: string;
  dataType?: ProviderDataType;
  syncStatuses?: SyncStatus[];
  pageNo: number;
  pageSize: number;
};

export type AdminSyncRunListItem = {
  syncRunId: number;
  providerCode: string;
  dataType: ProviderDataType;
  syncStatus: SyncStatus;
  startedAt: string;
  finishedAt: string | null;
  fetchedCount: number;
  successCount: number;
  failureCount: number;
  retryCount: number;
  quotaCost: number;
  errorSummary: string | null;
};

export type AdminRawPayloadSnippet = {
  payloadId: number;
  requestKey: string | null;
  requestedAt: string;
  providerUpdatedAt: string | null;
  httpStatus: number | null;
  payloadHash: string;
  parseStatus: 'PENDING' | 'SUCCESS' | 'FAILED';
  parseErrorSummary: string | null;
  maskedJsonFragment: string;
  truncated: boolean;
};

export type AdminSyncRunDetail = {
  run: AdminSyncRunListItem;
  rawPayloads: AdminRawPayloadSnippet[];
  rawPayloadNotice: string | null;
};

export type AdminSyncRunError = {
  syncRunId: number;
  providerCode: string;
  dataType: ProviderDataType;
  syncStatus: Extract<SyncStatus, 'FAILED' | 'PARTIAL'>;
  startedAt: string;
  finishedAt: string | null;
  failureCount: number;
  retryCount: number;
  errorSummary: string | null;
};

export type AdminSyncRunQuotaItem = {
  providerCode: string;
  dataType: ProviderDataType;
  runCount: number;
  consumedQuota: number;
  warningThreshold: number | null;
  warningTriggered: boolean;
};

export type AdminSyncRunQuotaSummary = {
  businessDate: string;
  generatedAt: string;
  items: AdminSyncRunQuotaItem[];
};

export type AdminPredictionLockListQuery = {
  lotteryDate?: string;
  modelVersion?: string;
  predictionStatuses?: PredictionStatus[];
  lockDiagnostics?: LockDiagnostic[];
  pageNo: number;
  pageSize: number;
};

export type AdminSettlementStatusListQuery = {
  lotteryDate?: string;
  modelVersion?: string;
  diagnostics?: SettlementDiagnostic[];
  pageNo: number;
  pageSize: number;
};

export type AdminStatusDiagnostic = {
  code: LockDiagnostic | SettlementDiagnostic;
  description: string;
  /** 诊断发生在哪个市场上；锁定类诊断与市场无关，为空。 */
  marketType?: AdminSettlementMarketType | null;
};

export type AdminPredictionMatch = {
  matchId: number;
  lotteryDate: string;
  lotteryMatchNo: string;
  leagueName: string;
  homeTeamName: string;
  awayTeamName: string;
  kickoffTime: string;
  /** 体彩让球胜平负使用的官方让球线；状态页历史兼容时可缺省。 */
  officialHandicap?: number | null;
};

/** 发布前由管理员复核的未公开预测；草稿不会进入公共接口。 */
export type AdminDraftPredictionListItem = {
  predictionId: number;
  modelVersion: string;
  featureVersion: string;
  generationBatchId: string;
  generationBatchHash: string;
  predictionVersion: number;
  predictionType?: 'SPORTTERY' | 'ASIAN';
  homeWinProb: number | null;
  drawProb: number | null;
  awayWinProb: number | null;
  handicapPick: 'HOME_WIN' | 'DRAW' | 'AWAY_WIN' | null;
  expectedTotalGoals: number | null;
  asianHandicapPick?: AsianHandicapPick | null;
  totalGoalsPick?: TotalGoalsPick | null;
  asianMarket?: PredictionAsianMarketVo | null;
  asianHandicapConfidenceLevel?: 'LOW' | 'MEDIUM' | 'HIGH' | null;
  totalGoalsConfidenceLevel?: 'LOW' | 'MEDIUM' | 'HIGH' | null;
  confidenceLevel: 'LOW' | 'MEDIUM' | 'HIGH' | null;
  analysisSummary: string;
  generatedAt: string;
  match: AdminPredictionMatch;
};

export type AdminDraftPredictionPage = {
  records: AdminDraftPredictionListItem[];
  pageNo: number;
  pageSize: number;
  total: number;
};

/** 管理员按竞彩业务日生成 V2 草稿后的可复核摘要。 */
export type AdminBaselinePredictionGeneration = {
  lotteryDate: string;
  modelVersion: string;
  featureVersion: string;
  candidateCount: number;
  generatedCount: number;
  insertedCount: number;
  reusedCount: number;
  generationBatchId: string | null;
  generationBatchHash: string | null;
  generatedAt: string | null;
  skippedByReason: Record<string, number>;
};

export type AdminPredictionPublishResult = {
  predictionId: number;
  matchId: number;
  modelVersion: string;
  predictionVersion: number;
  predictionStatus: 'PUBLISHED';
  publishTime: string;
  lockTime: string;
  predictionHash: string;
  reused: boolean;
};

/** 批量发布里单条预测的结果；失败时只有 predictionId 与 errorMessage。 */
export type AdminBatchPublishItem = {
  predictionId: number;
  success: boolean;
  matchId: number | null;
  predictionVersion: number | null;
  predictionStatus: PredictionStatus | null;
  predictionHash: string | null;
  reused: boolean;
  errorMessage: string | null;
};

export type AdminBatchPublishResult = {
  requestedCount: number;
  successCount: number;
  failureCount: number;
  results: AdminBatchPublishItem[];
};

/** 亚盘某一档盘的跨庄共识；概率已由服务端去水，前端不再重算。 */
export type AdminOddsLadderLine = {
  line: number;
  sampleCount: number;
  whitelistCount: number;
  whitelistApplied: boolean;
  firstProbability: number;
  secondProbability: number;
  dispersion: number;
  fairFirstPrice: number;
  fairSecondPrice: number;
  best: boolean;
};

export type AdminOddsLadderMarket = {
  bestLine: number | null;
  bestFirstProbability: number | null;
  bestSecondProbability: number | null;
  bestDispersion: number | null;
  bestSampleCount: number | null;
  bestSampleSource: string | null;
  lines: AdminOddsLadderLine[];
};

/** 一条原始报价；frozen 表示结算与摘要实际引用的那一行。 */
export type AdminOddsLadderRow = {
  snapshotId: number;
  providerCode: string;
  bookmakerCode: string;
  handicapLine: number | null;
  homeOdds: number | null;
  awayOdds: number | null;
  totalLine: number | null;
  overOdds: number | null;
  underOdds: number | null;
  capturedAt: string;
  frozen: boolean;
};

export type AdminOddsLadder = {
  predictionId: number;
  matchId: number;
  frozenSnapshotId: number | null;
  handicap: AdminOddsLadderMarket;
  totals: AdminOddsLadderMarket;
  rows: AdminOddsLadderRow[];
};

export type AdminResultFact = {
  factId: number;
  factVersion: number;
  supersedesFactVersion: number | null;
  factStatus: 'PENDING' | 'FINAL' | 'VOID';
  matchStatus: string;
  homeScore: number | null;
  awayScore: number | null;
  providerUpdatedAt: string;
  resultSource?: 'OFFICIAL' | 'MANUAL';
  sourceNote?: string | null;
  entryReason?: string | null;
  enteredBy?: string | null;
  current: boolean;
  createdAt: string;
};

export type AdminSettlementMarket = {
  marketType: AdminSettlementMarketType;
  currentStatus: AdminSettlementStatus;
  currentSettlementPersisted: boolean;
  settlementId: number | null;
  settlementVersion: number | null;
  matchFactId: number | null;
  ruleVersion: string | null;
  stale: boolean;
  /** 按 1 单位本金计算的收益；未落库时为 null。 */
  profit: number | null;
};

export type AdminSettlementVersion = {
  settlementId: number;
  settlementVersion: number;
  supersedesSettlementVersion: number | null;
  settlementStatus: AdminSettlementStatus;
  matchFactId: number;
  ruleVersion: string;
  current: boolean;
  createdAt: string;
  profit: number | null;
};

export type AdminSettlementMarketHistory = {
  marketType: AdminSettlementMarketType;
  currentStatus: AdminSettlementMarket['currentStatus'];
  currentSettlementPersisted: boolean;
  currentSettlementStale: boolean;
  currentProfit: number | null;
  versions: AdminSettlementVersion[];
};

export type AdminPredictionStatusItem = {
  predictionId: number;
  modelVersion: string;
  featureVersion: string;
  predictionVersion: number;
  predictionType: 'SPORTTERY' | 'ASIAN';
  predictionStatus: PredictionStatus;
  publishTime: string;
  lockTime: string;
  predictionHash: string;
  match: AdminPredictionMatch;
  lockDiagnostics: AdminStatusDiagnostic[];
  currentResultFact: AdminResultFact | null;
  /** 该产品线实际结算的市场，顺序固定；不再有 had/hhad 具名字段。 */
  settlementMarkets: AdminSettlementMarket[];
  settlementDiagnostics: AdminStatusDiagnostic[];
};

export type AdminPredictionStatusPage = {
  records: AdminPredictionStatusItem[];
  pageNo: number;
  pageSize: number;
  total: number;
  manualAttentionCount: number;
};

export type AdminPredictionStatusDetail = {
  prediction: AdminPredictionStatusItem;
  resultFactHistory: AdminResultFact[];
  settlementMarkets: AdminSettlementMarketHistory[];
};

export type AdminManualMatchResultRequest = {
  matchId: number;
  factStatus: 'FINAL' | 'VOID';
  matchStatus: 'FINISHED' | 'CANCELLED' | 'ABANDONED';
  homeScore: number | null;
  awayScore: number | null;
  sourceNote: string;
  entryReason: string;
  confirmed: boolean;
};

export type AdminManualResultSettlement = {
  recalculationCandidatePredictionCount: number;
  recalculatedPredictionCount: number;
  recalculatedMarketCount: number;
  recalculationFailureCount: number;
  recalculationManualReviewCount: number;
  settlementCandidatePredictionCount: number;
  settledPredictionCount: number;
  settledMarketCount: number;
  settlementFailureCount: number;
  settlementManualReviewCount: number;
};

export type AdminManualMatchResult = {
  factId: number;
  factVersion: number;
  writeOutcome: 'APPENDED' | 'SUPERSEDED' | 'UNCHANGED';
  resultSource: 'MANUAL';
  factStatus: 'FINAL' | 'VOID';
  matchStatus: 'FINISHED' | 'CANCELLED' | 'ABANDONED';
  homeScore: number | null;
  awayScore: number | null;
  sourceNote: string;
  entryReason: string;
  enteredBy: string;
  enteredAt: string;
  settlementTriggered: boolean;
  settlement: AdminManualResultSettlement;
};

export type AdminSportteryResultSync = {
  syncRunId: number;
  startDate: string;
  endDate: string;
  syncStatus: SyncStatus;
  fetchedCount: number;
  successCount: number;
  failureCount: number;
  retryCount: number;
  quotaCost: number;
  appendedFactCount: number;
  supersededFactCount: number;
  unchangedFactCount: number;
  duplicatePayload: boolean;
  errorSummary: string | null;
};

/** 使用管理员账号换取短期 Bearer Token。 */
export function loginAdmin(request: AdminLoginDto) {
  return requestApi<AdminSession>('/api/admin/auth/login', {
    method: 'POST',
    body: request,
  });
}

/** 使当前管理员账号的既有 JWT 立即失效。 */
export function logoutAdmin() {
  return requestApi<void>('/api/admin/auth/logout', {
    method: 'POST',
    authenticated: true,
  });
}

/** 分页读取管理员可见的同步运行摘要。 */
export function fetchAdminSyncRuns(query: AdminSyncRunListQuery, signal?: AbortSignal) {
  return requestApi<PageResult<AdminSyncRunListItem>>('/api/admin/provider/sync-runs/list', {
    method: 'POST', body: query, signal, authenticated: true,
  });
}

/** 读取单次同步的计数、错误及受控原始响应片段。 */
export function fetchAdminSyncRunDetail(syncRunId: number, signal?: AbortSignal) {
  return requestApi<AdminSyncRunDetail>('/api/admin/provider/sync-runs/detail', {
    method: 'POST', body: { syncRunId }, signal, authenticated: true,
  });
}

/** 分页读取失败或部分成功同步的错误摘要。 */
export function fetchAdminSyncRunErrors(
  query: Pick<AdminSyncRunListQuery, 'providerCode' | 'dataType' | 'pageNo' | 'pageSize'>,
  signal?: AbortSignal,
) {
  return requestApi<PageResult<AdminSyncRunError>>('/api/admin/provider/sync-runs/errors/list', {
    method: 'POST', body: query, signal, authenticated: true,
  });
}

/** 汇总指定上海业务日的已消耗额度和预警阈值。 */
export function fetchAdminSyncRunQuotaSummary(businessDate: string, signal?: AbortSignal) {
  return requestApi<AdminSyncRunQuotaSummary>('/api/admin/provider/sync-runs/quota/summary', {
    method: 'POST', body: { businessDate }, signal, authenticated: true,
  });
}

/** 分页读取已发布预测的锁定状态。 */
export function fetchAdminPredictionLocks(query: AdminPredictionLockListQuery, signal?: AbortSignal) {
  return requestApi<AdminPredictionStatusPage>('/api/admin/prediction-status/locks/list', {
    method: 'POST', body: query, signal, authenticated: true,
  });
}

/** 分页读取仅供管理员发布前复核的 DRAFT 预测。 */
export function fetchAdminDraftPredictions(query: AdminDraftPredictionListQuery, signal?: AbortSignal) {
  return requestApi<AdminDraftPredictionPage>('/api/admin/predictions/drafts/list', {
    method: 'POST', body: query, signal, authenticated: true,
  });
}

/** 仅基于已持久化且完整的盘口生成 V2 草稿；不会发布预测。 */
export function generateAdminBaselinePredictions(lotteryDate: string) {
  return requestApi<AdminBaselinePredictionGeneration>('/api/admin/predictions/baseline/generate', {
    method: 'POST', body: { lotteryDate }, authenticated: true,
  });
}

/** 逐条复用后端既有发布规则，不在前端合并或绕过发布校验。 */
export function publishAdminPrediction(predictionId: number) {
  return requestApi<AdminPredictionPublishResult>('/api/admin/predictions/publish', {
    method: 'POST', body: { predictionId }, authenticated: true,
  });
}

/**
 * 一次请求发布多条。
 *
 * <p>此前是前端并发逐条调用，同场多条预测会互相争抢 `matches` 行锁；服务端按
 * (matchId, predictionId) 顺序串行执行，并把逐条结果原样返回。
 */
export function publishAdminPredictions(predictionIds: number[]) {
  return requestApi<AdminBatchPublishResult>('/api/admin/predictions/publish/batch', {
    method: 'POST', body: { predictionIds }, authenticated: true,
  });
}

/** 读取一条亚盘预测当时的盘口阶梯与冻结行。 */
export function fetchAdminOddsLadder(predictionId: number, signal?: AbortSignal) {
  return requestApi<AdminOddsLadder>('/api/admin/predictions/odds-ladder', {
    method: 'POST', body: { predictionId }, signal, authenticated: true,
  });
}

/** 分页读取已锁定预测的待赛果、待结算及需重算状态。 */
export function fetchAdminSettlementStatuses(query: AdminSettlementStatusListQuery, signal?: AbortSignal) {
  return requestApi<AdminPredictionStatusPage>('/api/admin/prediction-status/settlements/list', {
    method: 'POST', body: query, signal, authenticated: true,
  });
}

/** 读取后台预测状态及不可变版本链。 */
export function fetchAdminPredictionStatusDetail(predictionId: number, signal?: AbortSignal) {
  return requestApi<AdminPredictionStatusDetail>('/api/admin/prediction-status/detail', {
    method: 'POST', body: { predictionId }, signal, authenticated: true,
  });
}

/** 手动同步指定竞彩业务日范围的官方赛果；服务端不会自动结算。 */
export function syncAdminSportteryResults(
  request: { startDate?: string; endDate?: string }, signal?: AbortSignal,
) {
  return requestApi<AdminSportteryResultSync>('/api/admin/provider/sporttery/results/sync', {
    method: 'POST', body: request, signal, authenticated: true,
  });
}

/** 受控人工补录赛果；服务端仅定向联动该比赛的自动结算与重算。 */
export function recordAdminManualMatchResult(request: AdminManualMatchResultRequest) {
  return requestApi<AdminManualMatchResult>('/api/admin/manual-results', {
    method: 'POST', body: request, authenticated: true,
  });
}
