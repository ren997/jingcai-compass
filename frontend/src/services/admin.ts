import { requestApi } from './http';
import type { AdminSession } from '../types/api';
import type { PageResult } from './public';

export type AdminLoginDto = {
  username: string;
  password: string;
};

export const PROVIDER_DATA_TYPES = ['SPORTTERY_POOL', 'SPORTTERY_RESULT', 'ASIAN_ODDS', 'OTHER'] as const;
export type ProviderDataType = (typeof PROVIDER_DATA_TYPES)[number];

export const SYNC_STATUSES = ['RUNNING', 'SUCCESS', 'FAILED', 'PARTIAL'] as const;
export type SyncStatus = (typeof SYNC_STATUSES)[number];

export const MAPPING_STATUSES = ['PENDING', 'AUTO_CONFIRMED', 'MANUAL_CONFIRMED', 'REJECTED'] as const;
export type MappingReviewStatus = (typeof MAPPING_STATUSES)[number];

export const PREDICTION_STATUSES = ['PUBLISHED', 'LOCKED'] as const;
export type PredictionStatus = (typeof PREDICTION_STATUSES)[number];

export const LOCK_DIAGNOSTICS = ['OVERDUE', 'SCHEDULED', 'LOCKED'] as const;
export type LockDiagnostic = (typeof LOCK_DIAGNOSTICS)[number];

export const SETTLEMENT_DIAGNOSTICS = [
  'AWAITING_RESULT', 'SETTLEMENT_MISSING_HAD', 'SETTLEMENT_MISSING_HHAD',
  'SETTLEMENT_STALE_HAD', 'SETTLEMENT_STALE_HHAD',
] as const;
export type SettlementDiagnostic = (typeof SETTLEMENT_DIAGNOSTICS)[number];

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
};

export type AdminPredictionMatch = {
  matchId: number;
  lotteryDate: string;
  lotteryMatchNo: string;
  leagueName: string;
  homeTeamName: string;
  awayTeamName: string;
  kickoffTime: string;
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
  current: boolean;
  createdAt: string;
};

export type AdminSettlementMarket = {
  marketType: 'HAD' | 'HHAD';
  currentStatus: 'PENDING' | 'HIT' | 'MISS' | 'VOID';
  currentSettlementPersisted: boolean;
  settlementId: number | null;
  settlementVersion: number | null;
  matchFactId: number | null;
  ruleVersion: string | null;
  stale: boolean;
};

export type AdminSettlementVersion = {
  settlementId: number;
  settlementVersion: number;
  supersedesSettlementVersion: number | null;
  settlementStatus: 'HIT' | 'MISS' | 'VOID';
  matchFactId: number;
  ruleVersion: string;
  current: boolean;
  createdAt: string;
};

export type AdminSettlementMarketHistory = {
  marketType: 'HAD' | 'HHAD';
  currentStatus: AdminSettlementMarket['currentStatus'];
  currentSettlementPersisted: boolean;
  currentSettlementStale: boolean;
  versions: AdminSettlementVersion[];
};

export type AdminPredictionStatusItem = {
  predictionId: number;
  modelVersion: string;
  featureVersion: string;
  predictionVersion: number;
  predictionStatus: PredictionStatus;
  publishTime: string;
  lockTime: string;
  predictionHash: string;
  match: AdminPredictionMatch;
  lockDiagnostics: AdminStatusDiagnostic[];
  currentResultFact: AdminResultFact | null;
  hadSettlement: AdminSettlementMarket;
  hhadSettlement: AdminSettlementMarket;
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

export type MappingReviewListQuery = {
  providerCode?: string;
  mappingStatus?: MappingReviewStatus;
  pageNo: number;
  pageSize: number;
};

export type MappingReviewListItem = {
  mappingId: number;
  matchId: number | null;
  providerCode: string;
  externalMatchId: string;
  mappingStatus: MappingReviewStatus;
  mappingConfidence: number | null;
  mappingMethod: string | null;
  mappingExplanation: string | null;
  candidateCount: number;
  confirmedBy: string | null;
  updatedAt: string;
};

export type MappingMatchBrief = {
  matchId: number;
  lotteryMatchNo: string;
  lotteryDate: string;
  leagueName: string;
  homeTeamName: string;
  awayTeamName: string;
  kickoffTime: string;
};

export type MappingReviewCandidate = {
  matchId: number;
  score: number | null;
  reasons: string[];
  match: MappingMatchBrief | null;
};

export type MappingReviewDetail = {
  mappingId: number;
  matchId: number | null;
  providerCode: string;
  externalMatchId: string;
  externalLeagueId: string | null;
  externalHomeTeamId: string | null;
  externalAwayTeamId: string | null;
  mappingStatus: MappingReviewStatus;
  mappingConfidence: number | null;
  mappingMethod: string | null;
  mappingExplanation: string | null;
  candidates: MappingReviewCandidate[];
  confirmedBy: string | null;
  match: MappingMatchBrief | null;
  updatedAt: string;
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

/** 分页读取映射复核队列。 */
export function fetchMappingReviews(query: MappingReviewListQuery, signal?: AbortSignal) {
  return requestApi<PageResult<MappingReviewListItem>>('/api/admin/provider/mappings/list', {
    method: 'POST', body: query, signal, authenticated: true,
  });
}

/** 读取映射候选、内部比赛资料和当前人工状态。 */
export function fetchMappingReviewDetail(mappingId: number, signal?: AbortSignal) {
  return requestApi<MappingReviewDetail>('/api/admin/provider/mappings/detail', {
    method: 'POST', body: { mappingId }, signal, authenticated: true,
  });
}

/** 确认当前关联或已持久化候选比赛。 */
export function confirmMappingReview(mappingId: number, targetMatchId: number) {
  return requestApi<MappingReviewDetail>('/api/admin/provider/mappings/confirm', {
    method: 'POST', body: { mappingId, targetMatchId }, authenticated: true,
  });
}

/** 拒绝当前待复核映射，可记录操作原因。 */
export function rejectMappingReview(mappingId: number, reason?: string) {
  return requestApi<MappingReviewDetail>('/api/admin/provider/mappings/reject', {
    method: 'POST', body: { mappingId, reason: reason?.trim() || null }, authenticated: true,
  });
}

/** 将被拒绝映射恢复为待复核状态。 */
export function reopenMappingReview(mappingId: number) {
  return requestApi<MappingReviewDetail>('/api/admin/provider/mappings/reopen', {
    method: 'POST', body: { mappingId }, authenticated: true,
  });
}
