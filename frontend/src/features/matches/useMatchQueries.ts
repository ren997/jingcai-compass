import { useQuery } from '@tanstack/react-query';
import { fetchMatchDetail, fetchMatchList, type MatchListQuery } from '../../services/public';
import { LEAGUE_OPTIONS_PAGE_SIZE } from './matchSearch';

export function matchListQueryKey(query: MatchListQuery) {
  return ['public', 'matches', 'list', query] as const;
}

export function matchDetailQueryKey(matchId: number) {
  return ['public', 'matches', 'detail', matchId] as const;
}

/** 读取当前筛选下的服务端分页比赛列表。 */
export function useMatchListQuery(query: MatchListQuery) {
  return useQuery({
    queryKey: matchListQueryKey(query),
    queryFn: ({ signal }) => fetchMatchList(query, signal),
  });
}

/** 读取指定比赛的完整基础详情。 */
export function useMatchDetailQuery(matchId: number | undefined) {
  return useQuery({
    queryKey: matchDetailQueryKey(matchId ?? 0),
    queryFn: ({ signal }) => fetchMatchDetail(matchId!, signal),
    enabled: matchId !== undefined,
  });
}

/** 从当日无筛选比赛中派生可读的联赛筛选选项。 */
export function useLeagueOptionsQuery(lotteryDate: string) {
  return useQuery({
    queryKey: ['public', 'matches', 'league-options', lotteryDate],
    queryFn: ({ signal }) => fetchMatchList({
      lotteryDate,
      sort: 'KICKOFF_ASC',
      pageNo: 1,
      pageSize: LEAGUE_OPTIONS_PAGE_SIZE,
    }, signal),
    select: (page) => Array.from(new Map(
      page.records
        .filter((match) => match.leagueId !== null)
        .map((match) => [match.leagueId!, { id: match.leagueId!, name: match.leagueName }]),
    ).values()).sort((left, right) => left.name.localeCompare(right.name, 'zh-CN')),
  });
}
