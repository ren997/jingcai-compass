import {
  ASIAN_SETTLEMENT_MARKETS,
  PREDICTION_TYPES,
  SETTLEMENT_MARKETS,
  SETTLEMENT_STATUSES,
  settlementMarketsFor,
  settlementStatusesFor,
  type HistoryListQuery,
  type PredictionType,
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
  predictionType: PredictionType;
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

function parseSettlementStatuses(
  value: string | null,
  predictionType: PredictionType,
): SettlementStatus[] {
  const requested = new Set((value ?? '').split(',').filter((status): status is SettlementStatus => (
    SETTLEMENT_STATUSES.includes(status as SettlementStatus)
  )));
  // 按产品线裁剪：竞彩不可能出现走盘/半赢/半输，保留这些状态会得到一个必然为空的结果集。
  return settlementStatusesFor(predictionType).filter((status) => requested.has(status));
}

/**
 * 产品线缺省值取亚盘。
 *
 * <p>页面的默认视图必须落在当前主力产品线上，否则新做的亚盘口径默认不可见，而竞彩口径天天可见。
 * 老链接可能只带 market 不带 type，这时按市场反推，避免把「亚盘让球」读成竞彩后回退到 HAD。
 */
const DEFAULT_PREDICTION_TYPE: PredictionType = 'ASIAN';

function parsePredictionType(value: string | null, market: string | null): PredictionType {
  if (PREDICTION_TYPES.includes(value as PredictionType)) {
    return value as PredictionType;
  }
  // 市场能双向定位产品线：老链接只带 market=HHAD 时不能被读成亚盘后回退到亚盘让球。
  if (market && (SETTLEMENT_MARKETS as readonly string[]).includes(market)) {
    return (ASIAN_SETTLEMENT_MARKETS as readonly string[]).includes(market) ? 'ASIAN' : 'SPORTTERY';
  }
  return DEFAULT_PREDICTION_TYPE;
}

/**
 * 解析市场，并强制与产品线自洽。
 *
 * <p>手工改过 URL 时可能出现「亚盘 + HAD」这种组合，后端会直接 400。这里回退到该产品线的
 * 默认市场，让分享出去的链接即使参数错乱也能打开，而不是给用户一个报错页。
 */
function parseSettlementMarket(value: string | null, predictionType: PredictionType): SettlementMarket {
  const allowed = settlementMarketsFor(predictionType);
  return allowed.includes(value as SettlementMarket)
    ? value as SettlementMarket
    : allowed[0];
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
  const predictionType = parsePredictionType(params.get('type'), params.get('market'));
  return {
    ...range,
    leagueId: asPositiveInteger(params.get('leagueId')),
    modelVersion: parseModelVersion(params.get('modelVersion')),
    lockedOnly: params.get('locked') === '1',
    predictionType,
    settlementMarket: parseSettlementMarket(params.get('market'), predictionType),
    settlementStatuses: parseSettlementStatuses(params.get('statuses'), predictionType),
    pageNo: asPositiveInteger(params.get('page')) ?? 1,
  };
}

/** 将归一化历史筛选写回 URL；默认值保留为稳定、可恢复的参数。 */
export function toHistorySearchParams(search: HistorySearch): URLSearchParams {
  const params = new URLSearchParams({
    type: search.predictionType,
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
    predictionType: search.predictionType,
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
    predictionType: parsePredictionType(params.get('type'), null),
  };
}

/** 将统计筛选序列化为稳定 URL，空筛选不覆盖后端默认窗口。 */
export function toStatisticsSearchParams(search: StatisticsSearch): URLSearchParams {
  const params = new URLSearchParams({ type: search.predictionType });
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
