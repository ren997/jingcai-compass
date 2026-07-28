import { requestApi } from './http';

export type MatchStatus =
  | 'SCHEDULED'
  | 'LOCKED'
  | 'IN_PROGRESS'
  | 'FINISHED'
  | 'POSTPONED'
  | 'CANCELLED'
  | 'ABANDONED';

/** 公开比赛列表项。 */
export type MatchSummaryVo = {
  matchId: string;
  lotteryDate: string;
  lotteryMatchNo: string;
  leagueName: string;
  homeTeamName: string;
  awayTeamName: string;
  kickoffTime: string;
  officialHandicap: number | null;
  matchStatus: MatchStatus;
  dataSource: string;
};

/** 查询指定竞彩日期的公开比赛。 */
export function fetchDailyMatches(lotteryDate: string, signal?: AbortSignal) {
  return requestApi<MatchSummaryVo[]>(
    `/api/public/matches?lotteryDate=${encodeURIComponent(lotteryDate)}`,
    { signal },
  );
}
