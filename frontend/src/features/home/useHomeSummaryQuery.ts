import { useQuery } from '@tanstack/react-query';
import { fetchHomeSummary } from '../../services/public';

export const homeSummaryQueryKey = ['public', 'home', 'summary'] as const;

/** 读取数据库事实驱动的公共首页摘要。 */
export function useHomeSummaryQuery() {
  return useQuery({
    queryKey: homeSummaryQueryKey,
    queryFn: ({ signal }) => fetchHomeSummary(signal),
  });
}
