import { requestApi } from './http';

export const MATCH_STATUSES = [
  'SCHEDULED',
  'LOCKED',
  'IN_PROGRESS',
  'FINISHED',
  'POSTPONED',
  'CANCELLED',
  'ABANDONED',
] as const;

export type MatchStatus = (typeof MATCH_STATUSES)[number];

export const MATCH_LIST_SORTS = [
  'KICKOFF_ASC',
  'KICKOFF_DESC',
  'LOTTERY_MATCH_NO_ASC',
  'LOTTERY_MATCH_NO_DESC',
] as const;

export type MatchListSort = (typeof MATCH_LIST_SORTS)[number];

export type MatchDataAvailability =
  | 'AVAILABLE'
  | 'NO_SPORTTERY_SNAPSHOT'
  | 'NO_ASIAN_ODDS_SNAPSHOT'
  | 'NO_SOURCE_MAPPING'
  | 'MAPPING_UNCONFIRMED';

export type OddsSnapshotType = 'FIRST_SEEN' | 'PRE_KICKOFF' | 'OTHER';

export type MappingStatus = 'PENDING' | 'AUTO_CONFIRMED' | 'MANUAL_CONFIRMED' | 'REJECTED';

export type PageResult<T> = {
  records: T[];
  pageNo: number;
  pageSize: number;
  total: number;
};

export type MatchListQuery = {
  lotteryDate: string;
  leagueId?: number;
  matchStatuses?: MatchStatus[];
  sort: MatchListSort;
  pageNo: number;
  pageSize: number;
};

/** T501 分页公开比赛列表项。 */
export type MatchListItemVo = {
  matchId: number;
  lotteryDate: string;
  lotteryMatchNo: string;
  leagueId: number | null;
  leagueName: string;
  homeTeamName: string;
  awayTeamName: string;
  kickoffTime: string;
  matchStatus: MatchStatus;
  officialHandicap: number | null;
  sportteryAvailability: MatchDataAvailability;
  sportteryDataSource: string | null;
  sportteryCapturedAt: string | null;
  sportteryProviderUpdatedAt: string | null;
};

export type SportteryMarketVo = {
  availability: MatchDataAvailability;
  dataSource: string | null;
  capturedAt: string | null;
  providerUpdatedAt: string | null;
  officialHandicap: number | null;
  hadHomeSp: number | null;
  hadDrawSp: number | null;
  hadAwaySp: number | null;
  hhadHomeSp: number | null;
  hhadDrawSp: number | null;
  hhadAwaySp: number | null;
  sellStatus: string | null;
};

export type AsianOddsMarketVo = {
  providerCode: string;
  bookmakerCode: string;
  handicapLine: number | null;
  homeOdds: number | null;
  awayOdds: number | null;
  totalLine: number | null;
  overOdds: number | null;
  underOdds: number | null;
  snapshotType: OddsSnapshotType;
  capturedAt: string | null;
  providerUpdatedAt: string | null;
};

export type MatchSourceMappingVo = {
  providerCode: string;
  externalMatchId: string;
  mappingStatus: MappingStatus;
  mappingConfidence: number | null;
  mappingMethod: string | null;
  mappingExplanation: string | null;
  mappingUpdatedAt: string | null;
};

/** T501 公开比赛基础详情。 */
export type MatchDetailVo = {
  matchId: number;
  lotteryDate: string;
  lotteryMatchNo: string;
  leagueId: number | null;
  leagueName: string;
  homeTeamName: string;
  awayTeamName: string;
  kickoffTime: string;
  matchStatus: MatchStatus;
  homeScore: number | null;
  awayScore: number | null;
  sportteryMarket: SportteryMarketVo;
  asianOddsAvailability: MatchDataAvailability;
  asianOddsMarkets: AsianOddsMarketVo[];
  mappingAvailability: MatchDataAvailability;
  sourceMappings: MatchSourceMappingVo[];
};

/** 使用 T501 的服务端分页、筛选和排序白名单查询比赛。 */
export function fetchMatchList(query: MatchListQuery, signal?: AbortSignal) {
  return requestApi<PageResult<MatchListItemVo>>('/api/public/matches/list', {
    method: 'POST',
    body: query,
    signal,
  });
}

/** 读取单场比赛的基础资料、体彩和亚盘市场。 */
export function fetchMatchDetail(matchId: number, signal?: AbortSignal) {
  return requestApi<MatchDetailVo>('/api/public/matches/detail', {
    method: 'POST',
    body: { matchId },
    signal,
  });
}
