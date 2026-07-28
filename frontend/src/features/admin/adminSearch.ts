import {
  MAPPING_STATUSES,
  PROVIDER_DATA_TYPES,
  SYNC_STATUSES,
  type AdminSyncRunListQuery,
  type MappingReviewListQuery,
  type MappingReviewStatus,
  type ProviderDataType,
  type SyncStatus,
} from '../../services/admin';

export const ADMIN_PAGE_SIZE = 20;

function validPositive(value: string | null) {
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : 1;
}

function validEnum<T extends string>(value: string | null, options: readonly T[]): T | undefined {
  return value !== null && (options as readonly string[]).includes(value) ? value as T : undefined;
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
