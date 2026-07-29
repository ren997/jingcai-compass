import {
  MAPPING_STATUSES,
  LOCK_DIAGNOSTICS,
  PREDICTION_STATUSES,
  PROVIDER_DATA_TYPES,
  SETTLEMENT_DIAGNOSTICS,
  SYNC_STATUSES,
  type AdminSyncRunListQuery,
  type AdminPredictionLockListQuery,
  type AdminSettlementStatusListQuery,
  type LockDiagnostic,
  type MappingReviewListQuery,
  type MappingReviewStatus,
  type PredictionStatus,
  type ProviderDataType,
  type SyncStatus,
  type SettlementDiagnostic,
} from '../../services/admin';

export const ADMIN_PAGE_SIZE = 20;

function validPositive(value: string | null) {
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : 1;
}

function validEnum<T extends string>(value: string | null, options: readonly T[]): T | undefined {
  return value !== null && (options as readonly string[]).includes(value) ? value as T : undefined;
}

function validDate(value: string | null) {
  if (!value || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return undefined;
  const date = new Date(`${value}T00:00:00Z`);
  return Number.isNaN(date.getTime()) || date.toISOString().slice(0, 10) !== value ? undefined : value;
}

function validEnumList<T extends string>(value: string | null, options: readonly T[]) {
  return Array.from(new Set((value ?? '').split(',')
    .map((item) => validEnum(item, options)).filter((item): item is T => item !== undefined)));
}

export function todayInShanghai() {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
  }).format(new Date());
}

export function parseSyncRunSearch(search: URLSearchParams) {
  const statuses = (search.get('statuses') ?? '').split(',')
    .map((status) => validEnum(status, SYNC_STATUSES)).filter((status): status is SyncStatus => status !== undefined);
  return {
    providerCode: search.get('providerCode')?.trim() || undefined,
    dataType: validEnum(search.get('dataType'), PROVIDER_DATA_TYPES),
    syncStatuses: Array.from(new Set(statuses)),
    pageNo: validPositive(search.get('page')),
    businessDate: /^\d{4}-\d{2}-\d{2}$/.test(search.get('date') ?? '') ? search.get('date')! : todayInShanghai(),
  };
}

export function toSyncRunQuery(filters: ReturnType<typeof parseSyncRunSearch>): AdminSyncRunListQuery {
  return {
    providerCode: filters.providerCode, dataType: filters.dataType,
    syncStatuses: filters.syncStatuses, pageNo: filters.pageNo, pageSize: ADMIN_PAGE_SIZE,
  };
}

export function toSyncRunSearch(filters: ReturnType<typeof parseSyncRunSearch>) {
  const params = new URLSearchParams();
  if (filters.providerCode) params.set('providerCode', filters.providerCode);
  if (filters.dataType) params.set('dataType', filters.dataType);
  if (filters.syncStatuses.length) params.set('statuses', filters.syncStatuses.join(','));
  if (filters.pageNo > 1) params.set('page', String(filters.pageNo));
  if (filters.businessDate !== todayInShanghai()) params.set('date', filters.businessDate);
  return params;
}

export function parseMappingSearch(search: URLSearchParams) {
  return {
    providerCode: search.get('providerCode')?.trim() || undefined,
    mappingStatus: validEnum(search.get('status'), MAPPING_STATUSES) ?? 'PENDING' as MappingReviewStatus,
    pageNo: validPositive(search.get('page')),
  };
}

export function toMappingQuery(filters: ReturnType<typeof parseMappingSearch>): MappingReviewListQuery {
  return {
    providerCode: filters.providerCode, mappingStatus: filters.mappingStatus,
    pageNo: filters.pageNo, pageSize: ADMIN_PAGE_SIZE,
  };
}

export function toMappingSearch(filters: ReturnType<typeof parseMappingSearch>) {
  const params = new URLSearchParams();
  if (filters.providerCode) params.set('providerCode', filters.providerCode);
  if (filters.mappingStatus !== 'PENDING') params.set('status', filters.mappingStatus);
  if (filters.pageNo > 1) params.set('page', String(filters.pageNo));
  return params;
}

export function parsePredictionLockSearch(search: URLSearchParams) {
  return {
    lotteryDate: validDate(search.get('date')),
    modelVersion: search.get('modelVersion')?.trim() || undefined,
    predictionStatuses: (() => {
      const statuses = validEnumList(search.get('statuses'), PREDICTION_STATUSES);
      return statuses.length ? statuses : [...PREDICTION_STATUSES];
    })(),
    lockDiagnostics: validEnumList(search.get('diagnostics'), LOCK_DIAGNOSTICS),
    pageNo: validPositive(search.get('page')),
  };
}

export function toPredictionLockQuery(filters: ReturnType<typeof parsePredictionLockSearch>): AdminPredictionLockListQuery {
  return {
    lotteryDate: filters.lotteryDate, modelVersion: filters.modelVersion,
    predictionStatuses: filters.predictionStatuses, lockDiagnostics: filters.lockDiagnostics,
    pageNo: filters.pageNo, pageSize: ADMIN_PAGE_SIZE,
  };
}

export function toPredictionLockSearch(filters: ReturnType<typeof parsePredictionLockSearch>) {
  const params = new URLSearchParams();
  if (filters.lotteryDate) params.set('date', filters.lotteryDate);
  if (filters.modelVersion) params.set('modelVersion', filters.modelVersion);
  if (filters.predictionStatuses.length !== PREDICTION_STATUSES.length) params.set('statuses', filters.predictionStatuses.join(','));
  if (filters.lockDiagnostics.length) params.set('diagnostics', filters.lockDiagnostics.join(','));
  if (filters.pageNo > 1) params.set('page', String(filters.pageNo));
  return params;
}

export function parseSettlementStatusSearch(search: URLSearchParams) {
  return {
    lotteryDate: validDate(search.get('date')),
    modelVersion: search.get('modelVersion')?.trim() || undefined,
    diagnostics: validEnumList(search.get('diagnostics'), SETTLEMENT_DIAGNOSTICS),
    pageNo: validPositive(search.get('page')),
  };
}

export function toSettlementStatusQuery(filters: ReturnType<typeof parseSettlementStatusSearch>): AdminSettlementStatusListQuery {
  return {
    lotteryDate: filters.lotteryDate, modelVersion: filters.modelVersion,
    diagnostics: filters.diagnostics, pageNo: filters.pageNo, pageSize: ADMIN_PAGE_SIZE,
  };
}

export function toSettlementStatusSearch(filters: ReturnType<typeof parseSettlementStatusSearch>) {
  const params = new URLSearchParams();
  if (filters.lotteryDate) params.set('date', filters.lotteryDate);
  if (filters.modelVersion) params.set('modelVersion', filters.modelVersion);
  if (filters.diagnostics.length) params.set('diagnostics', filters.diagnostics.join(','));
  if (filters.pageNo > 1) params.set('page', String(filters.pageNo));
  return params;
}
