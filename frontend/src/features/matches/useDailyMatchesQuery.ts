import { useQuery } from '@tanstack/react-query';
import { fetchDailyMatches } from '../../services/public';

/** 按竞彩日期读取比赛池，并由 QueryClient 管理缓存与重试。 */
export function useDailyMatchesQuery(lotteryDate: string) {
  return useQuery({
    queryKey: ['public', 'daily-matches', lotteryDate],
    queryFn: ({ signal }) => fetchDailyMatches(lotteryDate, signal),
  });
}
