import { MATCH_LIST_SORTS, MATCH_STATUSES, type MatchListQuery, type MatchListSort, type MatchStatus } from '../../services/public';

export const MATCH_PAGE_SIZE = 20;
export const LEAGUE_OPTIONS_PAGE_SIZE = 100;

export type MatchListSearch = Omit<MatchListQuery, 'pageSize' | 'matchStatuses'> & {
  matchStatuses: MatchStatus[];
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

function parseStatuses(value: string | null): MatchStatus[] {
  const requested = new Set((value ?? '').split(',').filter((status): status is MatchStatus => (
    MATCH_STATUSES.includes(status as MatchStatus)
  )));
  return MATCH_STATUSES.filter((status) => requested.has(status));
}

function parseSort(value: string | null): MatchListSort {
  return MATCH_LIST_SORTS.includes(value as MatchListSort)
    ? value as MatchListSort
    : 'KICKOFF_ASC';
}

/** 从公开 URL 解析并归一化比赛筛选。 */
export function parseMatchListSearch(params: URLSearchParams, fallbackDate: string): MatchListSearch {
  const date = params.get('date');
  const leagueId = asPositiveInteger(params.get('leagueId'));
  return {
    lotteryDate: isCalendarDate(date) ? date : fallbackDate,
    leagueId,
    matchStatuses: parseStatuses(params.get('statuses')),
    sort: parseSort(params.get('sort')),
    pageNo: asPositiveInteger(params.get('page')) ?? 1,
  };
}

/** 将归一化筛选序列化为可分享、可返回的 URL 参数。 */
export function toMatchListSearchParams(search: MatchListSearch): URLSearchParams {
  const params = new URLSearchParams({
    date: search.lotteryDate,
    sort: search.sort,
    page: String(search.pageNo),
  });
  if (search.leagueId) {
    params.set('leagueId', String(search.leagueId));
  }
  if (search.matchStatuses.length > 0) {
    params.set('statuses', search.matchStatuses.join(','));
  }
  return params;
}

/** 为 T501 列表接口补充固定的公共页大小。 */
export function toMatchListQuery(search: MatchListSearch): MatchListQuery {
  return { ...search, pageSize: MATCH_PAGE_SIZE };
}

export function todayInShanghai() {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(new Date());
  const values = Object.fromEntries(parts.map((part) => [part.type, part.value]));
  return `${values.year}-${values.month}-${values.day}`;
}
