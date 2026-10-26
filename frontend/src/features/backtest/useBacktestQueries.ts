import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  cancelBacktestRun,
  createBacktestRun,
  estimateBacktestRun,
  fetchAdminBacktestCases,
  fetchAdminBacktestComparison,
  fetchAdminBacktestLeagues,
  fetchAdminBacktestProgress,
  fetchAdminBacktestRuns,
  fetchPublicBacktestCases,
  fetchPublicBacktestComparison,
  fetchPublicBacktestRun,
  fetchPublicBacktestRuns,
  isActiveBacktestStatus,
  resumeBacktestRun,
  type BacktestCaseQuery,
  type BacktestRunCreateRequest,
  type BacktestRunListQuery,
} from '../../services/backtest';

/** 中间态的进度轮询周期。终态会自行停掉，不需要调用方管理定时器。 */
const PROGRESS_POLL_INTERVAL_MS = 3_000;

export function adminBacktestRunsQueryKey(query: BacktestRunListQuery) {
  return ['admin', 'backtest', 'runs', 'list', query] as const;
}

export function adminBacktestRunQueryKey(runId: number) {
  return ['admin', 'backtest', 'runs', 'detail', runId] as const;
}

export function adminBacktestCasesQueryKey(runId: number, query: BacktestCaseQuery) {
  return ['admin', 'backtest', 'cases', runId, query] as const;
}

export function adminBacktestComparisonQueryKey(runId: number) {
  return ['admin', 'backtest', 'compare', runId] as const;
}

export function adminBacktestLeaguesQueryKey(dateFrom?: string, dateTo?: string) {
  return ['admin', 'backtest', 'leagues', dateFrom ?? '', dateTo ?? ''] as const;
}

export function publicBacktestRunsQueryKey(query: BacktestRunListQuery) {
  return ['public', 'backtest', 'runs', 'list', query] as const;
}

export function publicBacktestRunQueryKey(runId: number) {
  return ['public', 'backtest', 'runs', 'detail', runId] as const;
}

export function publicBacktestCasesQueryKey(runId: number, query: BacktestCaseQuery) {
  return ['public', 'backtest', 'cases', runId, query] as const;
}

export function publicBacktestComparisonQueryKey(runId: number) {
  return ['public', 'backtest', 'compare', runId] as const;
}

/** 管理端 run 列表（含排队、失败与中断）。 */
export function useAdminBacktestRunsQuery(query: BacktestRunListQuery) {
  return useQuery({
    queryKey: adminBacktestRunsQueryKey(query),
    queryFn: ({ signal }) => fetchAdminBacktestRuns(query, signal),
    // 列表里可能有中间态在跑，5s 刷一次；不轮询就永远看到「排队中」。
    refetchInterval: 5_000,
  });
}

/**
 * 单个 run 的进度。
 *
 * <p>用 `progress` 端点而不是 `detail`：两者返回同一对象，但前者是给轮询用的，
 * 语义上明确「这个响应会重复取」。轮询到终态后自行停止——留一个只读的终态页
 * 每 3 秒发一次请求是纯粹的浪费。</p>
 */
export function useAdminBacktestRunQuery(runId: number | undefined) {
  return useQuery({
    queryKey: adminBacktestRunQueryKey(runId ?? 0),
    queryFn: ({ signal }) => fetchAdminBacktestProgress(runId!, signal),
    enabled: runId !== undefined,
    refetchInterval: (query) => {
      const status = query.state.data?.runStatus;
      return status && isActiveBacktestStatus(status) ? PROGRESS_POLL_INTERVAL_MS : false;
    },
  });
}

export function useAdminBacktestCasesQuery(runId: number | undefined, query: BacktestCaseQuery) {
  return useQuery({
    queryKey: adminBacktestCasesQueryKey(runId ?? 0, query),
    queryFn: ({ signal }) => fetchAdminBacktestCases(runId!, query, signal),
    enabled: runId !== undefined,
  });
}

/** 参数组对比。只在终态才有意义，中间态调用只会拿到空表。 */
export function useAdminBacktestComparisonQuery(runId: number | undefined, enabled: boolean) {
  return useQuery({
    queryKey: adminBacktestComparisonQueryKey(runId ?? 0),
    queryFn: ({ signal }) => fetchAdminBacktestComparison(runId!, signal),
    enabled: runId !== undefined && enabled,
  });
}

export function useAdminBacktestLeaguesQuery(dateFrom?: string, dateTo?: string) {
  return useQuery({
    queryKey: adminBacktestLeaguesQueryKey(dateFrom, dateTo),
    queryFn: ({ signal }) => fetchAdminBacktestLeagues(dateFrom, dateTo, signal),
  });
}

/** 手工触发一次预估；不做成 query 是因为它按「表单当前值」走，不是可缓存的资源。 */
export function useEstimateBacktestMutation() {
  return useMutation({
    mutationFn: (request: BacktestRunCreateRequest) => estimateBacktestRun(request),
  });
}

/** 创建并入队，成功后把列表标记为失效。 */
export function useCreateBacktestRunMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: BacktestRunCreateRequest) => createBacktestRun(request),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['admin', 'backtest', 'runs'] });
    },
  });
}

export function useCancelBacktestRunMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (runId: number) => cancelBacktestRun(runId),
    onSuccess: (run) => {
      queryClient.setQueryData(adminBacktestRunQueryKey(run.id), run);
      void queryClient.invalidateQueries({ queryKey: ['admin', 'backtest', 'runs', 'list'] });
    },
  });
}

export function useResumeBacktestRunMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (runId: number) => resumeBacktestRun(runId),
    onSuccess: (run) => {
      queryClient.setQueryData(adminBacktestRunQueryKey(run.id), run);
      void queryClient.invalidateQueries({ queryKey: ['admin', 'backtest', 'runs', 'list'] });
    },
  });
}

export function usePublicBacktestRunsQuery(query: BacktestRunListQuery) {
  return useQuery({
    queryKey: publicBacktestRunsQueryKey(query),
    queryFn: ({ signal }) => fetchPublicBacktestRuns(query, signal),
  });
}

export function usePublicBacktestRunQuery(runId: number | undefined) {
  return useQuery({
    queryKey: publicBacktestRunQueryKey(runId ?? 0),
    queryFn: ({ signal }) => fetchPublicBacktestRun(runId!, signal),
    enabled: runId !== undefined,
  });
}

export function usePublicBacktestComparisonQuery(runId: number | undefined) {
  return useQuery({
    queryKey: publicBacktestComparisonQueryKey(runId ?? 0),
    queryFn: ({ signal }) => fetchPublicBacktestComparison(runId!, signal),
    enabled: runId !== undefined,
  });
}

export function usePublicBacktestCasesQuery(runId: number | undefined, query: BacktestCaseQuery) {
  return useQuery({
    queryKey: publicBacktestCasesQueryKey(runId ?? 0, query),
    queryFn: ({ signal }) => fetchPublicBacktestCases(runId!, query, signal),
    enabled: runId !== undefined,
  });
}
