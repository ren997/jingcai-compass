import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  confirmMappingReview,
  fetchAdminSyncRunDetail,
  fetchAdminSyncRunErrors,
  fetchAdminSyncRunQuotaSummary,
  fetchAdminSyncRuns,
  fetchMappingReviewDetail,
  fetchMappingReviews,
  rejectMappingReview,
  reopenMappingReview,
  type AdminSyncRunListQuery,
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

export function mappingReviewDetailQueryKey(mappingId: number) {
  return ['admin', 'mappings', 'detail', mappingId] as const;
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
