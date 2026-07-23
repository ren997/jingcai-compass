export type MatchStatus =
  | 'SCHEDULED'
  | 'LOCKED'
  | 'FINISHED'
  | 'POSTPONED'
  | 'CANCELLED';

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

type ApiResponse<T> = {
  code: string;
  message: string;
  data: T;
  traceId: string;
};

export async function fetchDailyMatches(
  lotteryDate: string,
  signal?: AbortSignal,
): Promise<MatchSummaryVo[]> {
  const response = await fetch(
    `/api/public/matches?lotteryDate=${encodeURIComponent(lotteryDate)}`,
    { signal },
  );
  const body = (await response.json()) as ApiResponse<MatchSummaryVo[]>;
  if (!response.ok || body.code !== 'SUCCESS') {
    throw new Error(`${body.message}（追踪号：${body.traceId}）`);
  }
  return body.data;
}
