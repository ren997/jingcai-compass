import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  confirmMappingReview,
  confirmMappingReviewBundle,
  fetchAdminPredictionLocks,
  fetchAdminDraftPredictions,
  fetchAdminOddsLadder,
  generateAdminBaselinePredictions,
  fetchAdminPredictionStatusDetail,
  fetchAdminSettlementStatuses,
  fetchAdminSyncRunDetail,
  fetchAdminSyncRunErrors,
  fetchAdminSyncRunQuotaSummary,
  syncAdminSportteryResults,
  fetchAdminSyncRuns,
  fetchMappingReviewDetail,
  fetchMappingReviewMatchDetail,
  fetchMappingReviewMatches,
  fetchMappingReviews,
  rejectMappingReview,
  reopenMappingReview,
  confirmProviderNormalization,
  fetchProviderNormalizationCandidates,
  fetchProviderNormalizationDetail,
  fetchProviderNormalizations,
  recordAdminManualMatchResult,
  publishAdminPredictions,
  rejectProviderNormalization,
  reopenProviderNormalization,
  type AdminSyncRunListQuery,
  type AdminPredictionLockListQuery,
  type AdminDraftPredictionListItem,
  type AdminDraftPredictionListQuery,
  type AdminBaselinePredictionGeneration,
  type AdminSettlementStatusListQuery,
  type MappingReviewListQuery,
  type NormalizationEntityType,
  type ProviderNormalizationListQuery,
  type AdminManualMatchResultRequest,
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

export function providerNormalizationsQueryKey(query: ProviderNormalizationListQuery) {
  return ['admin', 'normalizations', 'list', query] as const;
}

export function providerNormalizationDetailQueryKey(entityType: NormalizationEntityType, mappingId: number) {
  return ['admin', 'normalizations', 'detail', entityType, mappingId] as const;
}

export function providerNormalizationCandidatesQueryKey(entityType: NormalizationEntityType, mappingId: number, keyword?: string) {
  return ['admin', 'normalizations', 'candidates', entityType, mappingId, keyword?.trim() || ''] as const;
}

export function adminPredictionLocksQueryKey(query: AdminPredictionLockListQuery) {
  return ['admin', 'prediction-status', 'locks', query] as const;
}

export function adminDraftPredictionsQueryKey(query: AdminDraftPredictionListQuery) {
  return ['admin', 'draft-predictions', 'list', query] as const;
}

export function adminSettlementStatusesQueryKey(query: AdminSettlementStatusListQuery) {
  return ['admin', 'prediction-status', 'settlements', query] as const;
}

export function adminPredictionStatusDetailQueryKey(predictionId: number) {
  return ['admin', 'prediction-status', 'detail', predictionId] as const;
}

export function adminOddsLadderQueryKey(predictionId: number) {
  return ['admin', 'odds-ladder', predictionId] as const;
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

/** 手动赛果同步成功或失败后均刷新可追溯的运行视图。 */
export function useAdminSportteryResultSyncAction() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: { startDate?: string; endDate?: string }) => syncAdminSportteryResults(request),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['admin', 'sync-runs'] });
    },
  });
}

/** 人工赛果事实写入后刷新后台结算状态与对应可追溯视图。 */
export function useAdminManualMatchResultAction() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: AdminManualMatchResultRequest) => recordAdminManualMatchResult(request),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['admin', 'prediction-status'] }),
        queryClient.invalidateQueries({ queryKey: ['history'] }),
      ]);
    },
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

/** 分页读取供应商联赛或球队标准化复核项。 */
export function useProviderNormalizationsQuery(query: ProviderNormalizationListQuery) {
  return useQuery({
    queryKey: providerNormalizationsQueryKey(query),
    queryFn: ({ signal }) => fetchProviderNormalizations(query, signal),
  });
}

/** 读取一条供应商标准化映射。 */
export function useProviderNormalizationDetailQuery(entityType: NormalizationEntityType, mappingId: number | undefined) {
  return useQuery({
    queryKey: providerNormalizationDetailQueryKey(entityType, mappingId ?? 0),
    queryFn: ({ signal }) => fetchProviderNormalizationDetail(entityType, mappingId!, signal),
    enabled: mappingId !== undefined,
  });
}

/** 搜索管理员可明确选择的内部标准实体。 */
export function useProviderNormalizationCandidatesQuery(
  entityType: NormalizationEntityType,
  mappingId: number | undefined,
  keyword?: string,
) {
  return useQuery({
    queryKey: providerNormalizationCandidatesQueryKey(entityType, mappingId ?? 0, keyword),
    queryFn: ({ signal }) => fetchProviderNormalizationCandidates(entityType, mappingId!, keyword, signal),
    enabled: mappingId !== undefined,
  });
}

/** 读取预测锁定运营列表。 */
export function useAdminPredictionLocksQuery(query: AdminPredictionLockListQuery) {
  return useQuery({
    queryKey: adminPredictionLocksQueryKey(query),
    queryFn: ({ signal }) => fetchAdminPredictionLocks(query, signal),
  });
}

/**
 * 读取一条草稿的盘口阶梯。
 *
 * <p>只在抽屉真正打开时请求（predictionId 未定义即关闭），阶梯数据量大且只在排障时使用，
 * 提前拉取会让草稿列表多出几十次无谓请求。
 */
export function useAdminOddsLadderQuery(predictionId: number | undefined) {
  return useQuery({
    queryKey: adminOddsLadderQueryKey(predictionId ?? 0),
    queryFn: ({ signal }) => fetchAdminOddsLadder(predictionId!, signal),
    enabled: predictionId !== undefined,
  });
}

/** 读取与公共展示严格隔离的管理员草稿队列。 */
export function useAdminDraftPredictionsQuery(query: AdminDraftPredictionListQuery) {
  return useQuery({
    queryKey: adminDraftPredictionsQueryKey(query),
    queryFn: ({ signal }) => fetchAdminDraftPredictions(query, signal),
  });
}

/** 生成成功后刷新草稿、公开比赛和预测状态缓存；生成本身绝不发布。 */
export function useAdminBaselinePredictionGenerationAction() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (lotteryDate: string): Promise<AdminBaselinePredictionGeneration> =>
      generateAdminBaselinePredictions(lotteryDate),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['admin', 'draft-predictions'] }),
        queryClient.invalidateQueries({ queryKey: ['admin', 'prediction-status'] }),
        queryClient.invalidateQueries({ queryKey: ['matches'] }),
      ]);
    },
  });
}

type DraftPublishOutcome = {
  predictionId: number;
  matchLabel: string;
  success: boolean;
  message: string;
};

/**
 * 一次请求发布整批草稿，并保留每一条成功或失败的结果。
 *
 * <p>此前是并发逐条调用：同场多条预测会互相争抢 `matches` 行锁。服务端改为按
 * (matchId, predictionId) 顺序串行执行，因此返回顺序与传入顺序不一致——
 * 结果必须按 predictionId 回填，按下标对齐会把结果贴到别的比赛上。
 */
export function useAdminDraftPredictionPublishAction() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (items: AdminDraftPredictionListItem[]): Promise<DraftPublishOutcome[]> => {
      const itemsById = new Map(items.map((item) => [item.predictionId, item]));
      const label = (predictionId: number) => {
        const item = itemsById.get(predictionId);
        return item
          ? `${item.match.lotteryMatchNo} · ${item.match.homeTeamName} vs ${item.match.awayTeamName}`
          : `预测 #${predictionId}`;
      };
      const batch = await publishAdminPredictions(items.map((item) => item.predictionId));
      const outcomes = batch.results.map((entry) => (entry.success
        ? {
          predictionId: entry.predictionId,
          matchLabel: label(entry.predictionId),
          success: true,
          message: entry.reused ? '已按既有发布结果返回。' : `发布成功，当前为第 ${entry.predictionVersion} 版。`,
        }
        : {
          predictionId: entry.predictionId,
          matchLabel: label(entry.predictionId),
          success: false,
          message: entry.errorMessage ?? '发布失败，请稍后重试。',
        }));

      // 请求里有、响应里没有的条目按失败处理：静默丢掉会让"全成功"的提示与事实不符
      const returned = new Set(batch.results.map((entry) => entry.predictionId));
      items
        .filter((item) => !returned.has(item.predictionId))
        .forEach((item) => outcomes.push({
          predictionId: item.predictionId,
          matchLabel: label(item.predictionId),
          success: false,
          message: '服务端未返回该条结果，请刷新后确认状态。',
        }));
      return outcomes;
    },
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['admin', 'draft-predictions'] }),
        queryClient.invalidateQueries({ queryKey: ['admin', 'prediction-status'] }),
        queryClient.invalidateQueries({ queryKey: ['home'] }),
        queryClient.invalidateQueries({ queryKey: ['matches'] }),
      ]);
    },
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
    await queryClient.invalidateQueries({ queryKey: ['admin', 'normalizations'] });
  };
  return {
    confirm: useMutation({ mutationFn: ({ mappingId, targetMatchId }: { mappingId: number; targetMatchId: number }) =>
      confirmMappingReview(mappingId, targetMatchId), onSuccess: refresh }),
    confirmBundle: useMutation({ mutationFn: (request: {
      mappingId: number; targetMatchId: number; confirmLeague: boolean; confirmHomeTeam: boolean; confirmAwayTeam: boolean;
    }) => confirmMappingReviewBundle(request), onSuccess: refresh }),
    reject: useMutation({ mutationFn: ({ mappingId, reason }: { mappingId: number; reason?: string }) =>
      rejectMappingReview(mappingId, reason), onSuccess: refresh }),
    reopen: useMutation({ mutationFn: (mappingId: number) => reopenMappingReview(mappingId), onSuccess: refresh }),
  };
}

/** 标准化复核写操作成功后刷新列表、详情与候选缓存。 */
export function useProviderNormalizationActions() {
  const queryClient = useQueryClient();
  const refresh = async () => {
    await queryClient.invalidateQueries({ queryKey: ['admin', 'normalizations'] });
    await queryClient.invalidateQueries({ queryKey: ['admin', 'mappings'] });
  };
  return {
    confirm: useMutation({ mutationFn: ({ entityType, mappingId, targetEntityId }: {
      entityType: NormalizationEntityType; mappingId: number; targetEntityId: number;
    }) => confirmProviderNormalization(entityType, mappingId, targetEntityId), onSuccess: refresh }),
    reject: useMutation({ mutationFn: ({ entityType, mappingId, reason }: {
      entityType: NormalizationEntityType; mappingId: number; reason?: string;
    }) => rejectProviderNormalization(entityType, mappingId, reason), onSuccess: refresh }),
    reopen: useMutation({ mutationFn: ({ entityType, mappingId }: {
      entityType: NormalizationEntityType; mappingId: number;
    }) => reopenProviderNormalization(entityType, mappingId), onSuccess: refresh }),
  };
}
