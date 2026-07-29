import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  confirmMappingReview,
  fetchAdminPredictionLocks,
  fetchAdminPredictionStatusDetail,
  fetchAdminSettlementStatuses,
  fetchAdminSyncRunDetail,
  fetchAdminSyncRunErrors,
  fetchAdminSyncRunQuotaSummary,
  fetchAdminSyncRuns,
  fetchMappingReviewDetail,
  fetchMappingReviewMatchDetail,
  fetchMappingReviewMatches,
  fetchMappingReviews,
  rejectMappingReview,
  reopenMappingReview,
  type AdminSyncRunListQuery,
  type AdminPredictionLockListQuery,
  type AdminSettlementStatusListQuery,
  type MappingReviewListQuery,
} from '../../services/admin';

export function adminSyncRunsQueryKey(query: AdminSyncRunListQuery) {
  return ['admin', 'sync-runs', 'list', query] as const;
}

export function adminSyncRunDetailQueryKey(syncRunId: number) {
  return ['admin', 'sync-runs', 'detail', syncRunId] as const;
}

export function adminSyncRunErrorsQueryKey(query: Pick<AdminSyncRunListQuery, 'providerCode' | 'dataType' | 'pageNo' | 'pageSize'>) {
  return ['admin', 'sync-runs', 'errors', query] as const;
}

export function adminSyncRunQuotaQueryKey(businessDate: string) {
  return ['admin', 'sync-runs', 'quota', businessDate] as const;
}

export function mappingReviewsQueryKey(query: MappingReviewListQuery) {
  return ['admin', 'mappings', 'list', query] as const;
}

export function mappingReviewMatchesQueryKey(query: MappingReviewListQuery) {
  return ['admin', 'mappings', 'matches', query] as const;
}

export function mappingReviewDetailQueryKey(mappingId: number) {
  return ['admin', 'mappings', 'detail', mappingId] as const;
}

export function mappingReviewMatchDetailQueryKey(
  matchId: number,
  query: Pick<MappingReviewListQuery, 'providerCode' | 'mappingStatus'>,
) {
  return ['admin', 'mappings', 'matches', 'detail', matchId, query] as const;
}

export function adminPredictionLocksQueryKey(query: AdminPredictionLockListQuery) {
  return ['admin', 'prediction-status', 'locks', query] as const;
}

export function adminSettlementStatusesQueryKey(query: AdminSettlementStatusListQuery) {
  return ['admin', 'prediction-status', 'settlements', query] as const;
}

export function adminPredictionStatusDetailQueryKey(predictionId: number) {
  return ['admin', 'prediction-status', 'detail', predictionId] as const;
}

/** 读取后台同步运行列表。 */
export function useAdminSyncRunsQuery(query: AdminSyncRunListQuery) {
  return useQuery({
    queryKey: adminSyncRunsQueryKey(query),
    queryFn: ({ signal }) => fetchAdminSyncRuns(query, signal),
  });
}

/** 读取单次同步详情。 */
export function useAdminSyncRunDetailQuery(syncRunId: number | undefined) {
  return useQuery({
    queryKey: adminSyncRunDetailQueryKey(syncRunId ?? 0),
    queryFn: ({ signal }) => fetchAdminSyncRunDetail(syncRunId!, signal),
    enabled: syncRunId !== undefined,
  });
}

/** 读取同步错误队列。 */
export function useAdminSyncRunErrorsQuery(
  query: Pick<AdminSyncRunListQuery, 'providerCode' | 'dataType' | 'pageNo' | 'pageSize'>,
) {
  return useQuery({
    queryKey: adminSyncRunErrorsQueryKey(query),
    queryFn: ({ signal }) => fetchAdminSyncRunErrors(query, signal),
  });
}

/** 读取同步额度业务日汇总。 */
export function useAdminSyncRunQuotaSummaryQuery(businessDate: string) {
  return useQuery({
    queryKey: adminSyncRunQuotaQueryKey(businessDate),
    queryFn: ({ signal }) => fetchAdminSyncRunQuotaSummary(businessDate, signal),
  });
}

/** 读取映射复核分页队列。 */
export function useMappingReviewsQuery(query: MappingReviewListQuery) {
  return useQuery({
    queryKey: mappingReviewsQueryKey(query),
    queryFn: ({ signal }) => fetchMappingReviews(query, signal),
  });
}

/** 读取候选对比详情。 */
export function useMappingReviewDetailQuery(mappingId: number | undefined) {
  return useQuery({
    queryKey: mappingReviewDetailQueryKey(mappingId ?? 0),
    queryFn: ({ signal }) => fetchMappingReviewDetail(mappingId!, signal),
    enabled: mappingId !== undefined,
  });
}

/** 按竞彩比赛读取外部映射候选。 */
export function useMappingReviewMatchesQuery(query: MappingReviewListQuery) {
  return useQuery({
    queryKey: mappingReviewMatchesQueryKey(query),
    queryFn: ({ signal }) => fetchMappingReviewMatches(query, signal),
  });
}

/** 读取竞彩比赛主体的外部候选详情。 */
export function useMappingReviewMatchDetailQuery(
  matchId: number | undefined,
  query: Pick<MappingReviewListQuery, 'providerCode' | 'mappingStatus'>,
) {
  return useQuery({
    queryKey: mappingReviewMatchDetailQueryKey(matchId ?? 0, query),
    queryFn: ({ signal }) => fetchMappingReviewMatchDetail(matchId!, query, signal),
    enabled: matchId !== undefined,
  });
}

/** 读取预测锁定运营列表。 */
export function useAdminPredictionLocksQuery(query: AdminPredictionLockListQuery) {
  return useQuery({
    queryKey: adminPredictionLocksQueryKey(query),
    queryFn: ({ signal }) => fetchAdminPredictionLocks(query, signal),
  });
}

/** 读取结算运营列表。 */
export function useAdminSettlementStatusesQuery(query: AdminSettlementStatusListQuery) {
  return useQuery({
    queryKey: adminSettlementStatusesQueryKey(query),
    queryFn: ({ signal }) => fetchAdminSettlementStatuses(query, signal),
  });
}

/** 读取一条预测的当前状态与版本链。 */
export function useAdminPredictionStatusDetailQuery(predictionId: number | undefined) {
  return useQuery({
    queryKey: adminPredictionStatusDetailQueryKey(predictionId ?? 0),
    queryFn: ({ signal }) => fetchAdminPredictionStatusDetail(predictionId!, signal),
    enabled: predictionId !== undefined,
  });
}

/** 确认、拒绝和重新打开后刷新相关后台缓存。 */
export function useMappingReviewActions() {
  const queryClient = useQueryClient();
  const refresh = async () => {
    await queryClient.invalidateQueries({ queryKey: ['admin', 'mappings'] });
  };
  return {
    confirm: useMutation({ mutationFn: ({ mappingId, targetMatchId }: { mappingId: number; targetMatchId: number }) =>
      confirmMappingReview(mappingId, targetMatchId), onSuccess: refresh }),
    reject: useMutation({ mutationFn: ({ mappingId, reason }: { mappingId: number; reason?: string }) =>
      rejectMappingReview(mappingId, reason), onSuccess: refresh }),
    reopen: useMutation({ mutationFn: (mappingId: number) => reopenMappingReview(mappingId), onSuccess: refresh }),
  };
}
