import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { setAdminSession, clearAdminSession } from './adminSession';
import {
  fetchAdminPredictionLocks,
  fetchAdminPredictionStatusDetail,
  fetchAdminSettlementStatuses,
  fetchAdminSyncRunDetail,
  fetchAdminSyncRuns,
  confirmMappingReviewBundle,
  fetchMappingReviewDetail,
  fetchMappingReviewMatchDetail,
  fetchMappingReviewMatches,
  fetchProviderNormalizationCandidates,
  fetchProviderNormalizationDetail,
  fetchProviderNormalizations,
} from './admin';

function response(data: unknown, options: { status?: number; code?: string; message?: string; traceId?: string } = {}) {
  const status = options.status ?? 200;
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: new Headers({ 'X-Trace-Id': options.traceId ?? 'admin-trace' }),
    text: async () => JSON.stringify({
      code: options.code ?? 'SUCCESS', message: options.message ?? '操作成功', data,
      traceId: options.traceId ?? 'admin-trace',
    }),
  } as Response;
}

describe('admin API services', () => {
  beforeEach(() => {
    setAdminSession({ accessToken: 'admin-jwt', tokenType: 'Bearer', expiresAt: '2099-01-01T00:00:00Z', adminId: 1, username: 'admin', role: 'ADMIN' });
    vi.stubGlobal('fetch', vi.fn());
  });

  afterEach(() => {
    clearAdminSession();
    vi.unstubAllGlobals();
  });

  it('posts authenticated typed sync queries and forwards external cancellation', async () => {
    vi.mocked(fetch).mockResolvedValue(response({ records: [], pageNo: 1, pageSize: 20, total: 0 }));
    const controller = new AbortController();
    await fetchAdminSyncRuns({ providerCode: 'STUB', syncStatuses: ['FAILED'], pageNo: 1, pageSize: 20 }, controller.signal);

    expect(fetch).toHaveBeenCalledWith('/api/admin/provider/sync-runs/list', expect.objectContaining({
      method: 'POST', body: JSON.stringify({ providerCode: 'STUB', syncStatuses: ['FAILED'], pageNo: 1, pageSize: 20 }), signal: expect.any(AbortSignal),
    }));
    const headers = vi.mocked(fetch).mock.calls[0][1]?.headers as Headers;
    expect(headers.get('Authorization')).toBe('Bearer admin-jwt');
  });

  it('uses detail paths and retains traceable backend errors', async () => {
    vi.mocked(fetch).mockResolvedValue(response(null, { status: 404, code: 'SYNC_RUN_NOT_FOUND', message: '同步运行记录不存在', traceId: 'sync-404' }));

    await expect(fetchAdminSyncRunDetail(77)).rejects.toMatchObject({ code: 'SYNC_RUN_NOT_FOUND', traceId: 'sync-404' });
    expect(fetch).toHaveBeenCalledWith('/api/admin/provider/sync-runs/detail', expect.objectContaining({ body: JSON.stringify({ syncRunId: 77 }) }));
  });

  it('posts mapping detail with the protected API contract', async () => {
    vi.mocked(fetch).mockResolvedValue(response({ mappingId: 9, candidates: [] }));
    await expect(fetchMappingReviewDetail(9)).resolves.toMatchObject({ mappingId: 9 });
    expect(fetch).toHaveBeenCalledWith('/api/admin/provider/mappings/detail', expect.objectContaining({ body: JSON.stringify({ mappingId: 9 }) }));
  });

  it('posts an atomic mapping confirmation bundle and retains traceable errors', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(response({ mappingId: 9, candidates: [] }));
    const controller = new AbortController();
    await confirmMappingReviewBundle({
      mappingId: 9, targetMatchId: 42, confirmLeague: true, confirmHomeTeam: true, confirmAwayTeam: false,
    }, controller.signal);

    expect(fetch).toHaveBeenCalledWith('/api/admin/provider/mappings/confirm-bundle', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ mappingId: 9, targetMatchId: 42, confirmLeague: true, confirmHomeTeam: true, confirmAwayTeam: false }),
      signal: expect.any(AbortSignal),
    }));

    vi.mocked(fetch).mockResolvedValueOnce(response(null, {
      status: 409, code: 'COMMON_BUSINESS_ERROR', message: '标准化关系已被其他管理员更新', traceId: 'bundle-conflict',
    }));
    await expect(confirmMappingReviewBundle({
      mappingId: 9, targetMatchId: 42, confirmLeague: true, confirmHomeTeam: false, confirmAwayTeam: false,
    })).rejects.toMatchObject({ traceId: 'bundle-conflict', code: 'COMMON_BUSINESS_ERROR' });
  });

  it('posts lottery-match-oriented mapping candidates with cancellation', async () => {
    vi.mocked(fetch).mockResolvedValue(response({ records: [], pageNo: 1, pageSize: 20, total: 0 }));
    const controller = new AbortController();
    await fetchMappingReviewMatches({ providerCode: 'THE_ODDS_API', mappingStatus: 'PENDING', reviewScope: 'ACTIVE', pageNo: 1, pageSize: 20 }, controller.signal);

    expect(fetch).toHaveBeenCalledWith('/api/admin/provider/mappings/matches/list', expect.objectContaining({
      method: 'POST', body: JSON.stringify({ providerCode: 'THE_ODDS_API', mappingStatus: 'PENDING', reviewScope: 'ACTIVE', pageNo: 1, pageSize: 20 }), signal: expect.any(AbortSignal),
    }));
  });

  it('posts one lottery match detail with filters and forwards cancellation', async () => {
    vi.mocked(fetch).mockResolvedValue(response({ match: { matchId: 42 }, externalCandidates: [] }));
    const controller = new AbortController();

    await fetchMappingReviewMatchDetail(42, { providerCode: 'THE_ODDS_API', mappingStatus: 'PENDING' }, controller.signal);

    expect(fetch).toHaveBeenCalledWith('/api/admin/provider/mappings/matches/detail', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ matchId: 42, providerCode: 'THE_ODDS_API', mappingStatus: 'PENDING' }),
      signal: expect.any(AbortSignal),
    }));
  });

  it('posts authenticated prediction status queries and keeps detail trace errors', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(response({ records: [], pageNo: 1, pageSize: 20, total: 0, manualAttentionCount: 0 }))
      .mockResolvedValueOnce(response({ records: [], pageNo: 1, pageSize: 20, total: 0, manualAttentionCount: 0 }))
      .mockResolvedValueOnce(response(null, { status: 404, code: 'PREDICTION_NOT_FOUND', message: '预测不存在', traceId: 'prediction-404' }));
    const controller = new AbortController();

    await fetchAdminPredictionLocks({ lotteryDate: '2026-07-29', predictionStatuses: ['PUBLISHED'], pageNo: 1, pageSize: 20 }, controller.signal);
    await fetchAdminSettlementStatuses({ diagnostics: ['SETTLEMENT_MISSING_HAD'], pageNo: 2, pageSize: 20 });
    await expect(fetchAdminPredictionStatusDetail(77)).rejects.toMatchObject({ code: 'PREDICTION_NOT_FOUND', traceId: 'prediction-404' });

    expect(fetch).toHaveBeenCalledWith('/api/admin/prediction-status/locks/list', expect.objectContaining({
      method: 'POST', body: JSON.stringify({ lotteryDate: '2026-07-29', predictionStatuses: ['PUBLISHED'], pageNo: 1, pageSize: 20 }), signal: expect.any(AbortSignal),
    }));
    expect(fetch).toHaveBeenCalledWith('/api/admin/prediction-status/settlements/list', expect.objectContaining({
      body: JSON.stringify({ diagnostics: ['SETTLEMENT_MISSING_HAD'], pageNo: 2, pageSize: 20 }),
    }));
    expect(fetch).toHaveBeenCalledWith('/api/admin/prediction-status/detail', expect.objectContaining({ body: JSON.stringify({ predictionId: 77 }) }));
  });

  it('posts protected normalization list, detail and internal candidate requests with trace errors', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(response({ records: [], pageNo: 1, pageSize: 20, total: 0 }))
      .mockResolvedValueOnce(response({ mappingId: 8, entityType: 'TEAM', auditHistory: [] }))
      .mockResolvedValueOnce(response(null, { status: 404, code: 'NORMALIZATION_MAPPING_NOT_FOUND', message: '标准化映射不存在', traceId: 'normalization-404' }));
    const controller = new AbortController();

    await fetchProviderNormalizations({ entityType: 'TEAM', providerCode: 'THE_ODDS_API', mappingStatus: 'PENDING', pageNo: 1, pageSize: 20 }, controller.signal);
    await fetchProviderNormalizationDetail('TEAM', 8);
    await expect(fetchProviderNormalizationCandidates('TEAM', 8, 'United')).rejects.toMatchObject({
      code: 'NORMALIZATION_MAPPING_NOT_FOUND', traceId: 'normalization-404',
    });

    expect(fetch).toHaveBeenCalledWith('/api/admin/provider/normalizations/list', expect.objectContaining({
      method: 'POST', signal: expect.any(AbortSignal),
      body: JSON.stringify({ entityType: 'TEAM', providerCode: 'THE_ODDS_API', mappingStatus: 'PENDING', pageNo: 1, pageSize: 20 }),
    }));
    expect(fetch).toHaveBeenCalledWith('/api/admin/provider/normalizations/detail', expect.objectContaining({
      body: JSON.stringify({ entityType: 'TEAM', mappingId: 8 }),
    }));
    expect(fetch).toHaveBeenCalledWith('/api/admin/provider/normalizations/candidates/list', expect.objectContaining({
      body: JSON.stringify({ entityType: 'TEAM', mappingId: 8, keyword: 'United' }),
    }));
  });
});
