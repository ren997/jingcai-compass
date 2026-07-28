import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  fetchMatchDetail,
  fetchMatchList,
  fetchPredictionDetail,
  predictionSnapshotDownloadUrl,
  type MatchListQuery,
  verifyPredictionSnapshot,
} from './public';
import {
  matchDetailQueryKey,
  matchListQueryKey,
  matchPredictionDetailQueryKey,
} from '../features/matches/useMatchQueries';

function apiResponse(data: unknown, options: { code?: string; message?: string; status?: number; traceId?: string } = {}) {
  const status = options.status ?? 200;
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: new Headers({ 'X-Trace-Id': options.traceId ?? 'public-test' }),
    text: async () => JSON.stringify({
      code: options.code ?? 'SUCCESS',
      message: options.message ?? '操作成功',
      data,
      traceId: options.traceId ?? 'public-test',
    }),
  } as Response;
}

describe('public match API service', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('posts the list contract and exposes its matching query key', async () => {
    const query: MatchListQuery = {
      lotteryDate: '2026-07-22',
      leagueId: 7,
      matchStatuses: ['SCHEDULED'],
      sort: 'KICKOFF_ASC',
      pageNo: 1,
      pageSize: 20,
    };
    vi.mocked(fetch).mockResolvedValue(apiResponse({ records: [], pageNo: 1, pageSize: 20, total: 0 }));

    await expect(fetchMatchList(query)).resolves.toEqual({ records: [], pageNo: 1, pageSize: 20, total: 0 });

    expect(fetch).toHaveBeenCalledWith('/api/public/matches/list', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify(query),
    }));
    expect(matchListQueryKey(query)).toEqual(['public', 'matches', 'list', query]);
  });

  it('posts a detail ID and preserves the API trace ID on failures', async () => {
    vi.mocked(fetch).mockResolvedValue(apiResponse(null, {
      code: 'MATCH_NOT_FOUND',
      message: '比赛不存在',
      status: 404,
      traceId: 'detail-trace-404',
    }));

    await expect(fetchMatchDetail(404)).rejects.toMatchObject({
      code: 'MATCH_NOT_FOUND',
      traceId: 'detail-trace-404',
      message: '比赛不存在（追踪号：detail-trace-404）',
    });
    expect(fetch).toHaveBeenCalledWith('/api/public/matches/detail', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ matchId: 404 }),
    }));
    expect(matchDetailQueryKey(404)).toEqual(['public', 'matches', 'detail', 404]);
  });

  it('propagates caller cancellation through the public service request', async () => {
    vi.mocked(fetch).mockImplementation((_, init) => new Promise((_, reject) => {
      (init?.signal as AbortSignal).addEventListener('abort', () => {
        reject(new DOMException('aborted', 'AbortError'));
      });
    }) as Promise<Response>);
    const controller = new AbortController();
    const request = fetchMatchList({
      lotteryDate: '2026-07-22',
      sort: 'KICKOFF_ASC',
      pageNo: 1,
      pageSize: 20,
    }, controller.signal);

    controller.abort();

    await expect(request).rejects.toMatchObject({ name: 'AbortError' });
  });

  it('posts prediction detail and snapshot verification through public endpoints', async () => {
    vi.mocked(fetch)
      .mockResolvedValueOnce(apiResponse({ matchId: 42, modelPredictions: [] }))
      .mockResolvedValueOnce(apiResponse({
        snapshotId: 501,
        snapshotHash: 'a'.repeat(64),
        contentLength: 99,
        verified: true,
      }));

    await expect(fetchPredictionDetail(42)).resolves.toEqual({ matchId: 42, modelPredictions: [] });
    await expect(verifyPredictionSnapshot(501)).resolves.toMatchObject({ verified: true });

    expect(fetch).toHaveBeenNthCalledWith(1, '/api/public/predictions/detail', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ matchId: 42 }),
    }));
    expect(fetch).toHaveBeenNthCalledWith(2, '/api/public/predictions/snapshots/501/verify', expect.objectContaining({
      method: 'POST',
    }));
    expect(matchPredictionDetailQueryKey(42)).toEqual(['public', 'predictions', 'detail', 42]);
    expect(predictionSnapshotDownloadUrl(501)).toBe('/api/public/predictions/snapshots/501/download');
  });
});
