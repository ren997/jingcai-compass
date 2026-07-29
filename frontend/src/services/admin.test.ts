import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { setAdminSession, clearAdminSession } from './adminSession';
import {
  fetchAdminPredictionLocks,
  fetchAdminPredictionStatusDetail,
  fetchAdminSettlementStatuses,
  fetchAdminSyncRunDetail,
  fetchAdminSyncRuns,
  fetchMappingReviewDetail,
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
});
