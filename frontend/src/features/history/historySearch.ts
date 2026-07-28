import {
  SETTLEMENT_MARKETS,
  SETTLEMENT_STATUSES,
  type HistoryListQuery,
  type SettlementMarket,
  type SettlementStatus,
  type StatisticsSummaryQuery,
} from '../../services/public';

export const HISTORY_PAGE_SIZE = 20;

export type HistorySearch = {
  startDate?: string;
  endDate?: string;
  leagueId?: number;
  modelVersion?: string;
  lockedOnly: boolean;
  settlementMarket: SettlementMarket;
  settlementStatuses: SettlementStatus[];
  pageNo: number;
};

export type StatisticsSearch = Omit<StatisticsSummaryQuery, 'leagueId'> & {
  leagueId?: number;
};

function isCalendarDate(value: string | null): value is string {
  if (!value || !/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    return false;
  }
  const date = new Date(`${value}T00:00:00.000Z`);
  return !Number.isNaN(date.getTime()) && date.toISOString().slice(0, 10) === value;
}

function asPositiveInteger(value: string | null) {
  if (!value || !/^\d+$/.test(value)) {
    return undefined;
  }
  const number = Number(value);
  return Number.isSafeInteger(number) && number > 0 ? number : undefined;
}

function parseModelVersion(value: string | null) {
  const trimmed = value?.trim();
  return trimmed ? trimmed : undefined;
}

function parseSettlementStatuses(value: string | null): SettlementStatus[] {
  const requested = new Set((value ?? '').split(',').filter((status): status is SettlementStatus => (
    SETTLEMENT_STATUSES.includes(status as SettlementStatus)
  )));
  return SETTLEMENT_STATUSES.filter((status) => requested.has(status));
}

function parseSettlementMarket(value: string | null): SettlementMarket {
  return SETTLEMENT_MARKETS.includes(value as SettlementMarket)
    ? value as SettlementMarket
    : 'HAD';
}

function normalizeDateRange(startDate?: string, endDate?: string) {
  return startDate && endDate && startDate > endDate
    ? { startDate: undefined, endDate: undefined }
    : { startDate, endDate };
}

/** 解析可分享的历史页 URL，并回退到 T507 的服务端默认筛选。 */
export function parseHistorySearch(params: URLSearchParams): HistorySearch {
  const range = normalizeDateRange(
    isCalendarDate(params.get('startDate')) ? params.get('startDate')! : undefined,
    isCalendarDate(params.get('endDate')) ? params.get('endDate')! : undefined,
  );
  return {
    ...range,
    leagueId: asPositiveInteger(params.get('leagueId')),
    modelVersion: parseModelVersion(params.get('modelVersion')),
    lockedOnly: params.get('locked') === '1',
    settlementMarket: parseSettlementMarket(params.get('market')),
    settlementStatuses: parseSettlementStatuses(params.get('statuses')),
    pageNo: asPositiveInteger(params.get('page')) ?? 1,
  };
}

/** 将归一化历史筛选写回 URL；默认值保留为稳定、可恢复的参数。 */
export function toHistorySearchParams(search: HistorySearch): URLSearchParams {
  const params = new URLSearchParams({
    market: search.settlementMarket,
    page: String(search.pageNo),
  });
  if (search.startDate) {
    params.set('startDate', search.startDate);
  }
  if (search.endDate) {
    params.set('endDate', search.endDate);
  }
  if (search.leagueId) {
    params.set('leagueId', String(search.leagueId));
  }
  if (search.modelVersion) {
    params.set('modelVersion', search.modelVersion);
  }
  if (search.lockedOnly) {
    params.set('locked', '1');
  }
  if (search.settlementStatuses.length > 0) {
    params.set('statuses', search.settlementStatuses.join(','));
  }
  return params;
}

/** 为历史接口补充固定的公共分页大小。 */
export function toHistoryListQuery(search: HistorySearch): HistoryListQuery {
  return {
    startDate: search.startDate,
    endDate: search.endDate,
    leagueId: search.leagueId,
    modelVersion: search.modelVersion,
    lockedOnly: search.lockedOnly,
    settlementMarket: search.settlementMarket,
    settlementStatuses: search.settlementStatuses.length > 0 ? search.settlementStatuses : undefined,
    pageNo: search.pageNo,
    pageSize: HISTORY_PAGE_SIZE,
  };
}

/** 解析统计 URL；省略日期时明确交由后端采用近 30 天口径。 */
export function parseStatisticsSearch(params: URLSearchParams): StatisticsSearch {
  const range = normalizeDateRange(
    isCalendarDate(params.get('startDate')) ? params.get('startDate')! : undefined,
    isCalendarDate(params.get('endDate')) ? params.get('endDate')! : undefined,
  );
  return {
    ...range,
    leagueId: asPositiveInteger(params.get('leagueId')),
    modelVersion: parseModelVersion(params.get('modelVersion')),
  };
}

/** 将统计筛选序列化为稳定 URL，空筛选不覆盖后端默认窗口。 */
export function toStatisticsSearchParams(search: StatisticsSearch): URLSearchParams {
  const params = new URLSearchParams();
  if (search.startDate) {
    params.set('startDate', search.startDate);
  }
  if (search.endDate) {
    params.set('endDate', search.endDate);
  }
  if (search.leagueId) {
    params.set('leagueId', String(search.leagueId));
  }
  if (search.modelVersion) {
    params.set('modelVersion', search.modelVersion);
  }
  return params;
}
