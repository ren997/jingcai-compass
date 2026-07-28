import { useQuery } from '@tanstack/react-query';
import {
  fetchHistoryList,
  fetchStatisticsSummary,
  type HistoryListQuery,
  type StatisticsSummaryQuery,
} from '../../services/public';

export function historyListQueryKey(query: HistoryListQuery) {
  return ['public', 'history', 'list', query] as const;
}

export function statisticsSummaryQueryKey(query: StatisticsSummaryQuery) {
  return ['public', 'statistics', 'summary', query] as const;
}

/** 读取公开预测、赛果与结算版本历史。 */
export function useHistoryListQuery(query: HistoryListQuery) {
  return useQuery({
    queryKey: historyListQueryKey(query),
    queryFn: ({ signal }) => fetchHistoryList(query, signal),
  });
}

/** 读取当前筛选范围的公开表现统计。 */
export function useStatisticsSummaryQuery(query: StatisticsSummaryQuery) {
  return useQuery({
    queryKey: statisticsSummaryQueryKey(query),
    queryFn: ({ signal }) => fetchStatisticsSummary(query, signal),
  });
}
