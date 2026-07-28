import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { clearAdminSession, getAdminSession, setAdminSession } from './adminSession';
import { ApiClientError, requestApi, resolveApiUrl } from './http';

function apiResponse(data: unknown, options: { code?: string; message?: string; status?: number; traceId?: string } = {}) {
  const status = options.status ?? 200;
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: new Headers({ 'X-Trace-Id': options.traceId ?? 'http-test' }),
    text: async () => JSON.stringify({
      code: options.code ?? 'SUCCESS',
      message: options.message ?? '操作成功',
      data,
      traceId: options.traceId ?? 'http-test',
    }),
  } as Response;
}

describe('requestApi', () => {
  beforeEach(() => {
    clearAdminSession();
    vi.stubGlobal('fetch', vi.fn());
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('uses the same-origin API path and JSON body for successful requests', async () => {
    vi.mocked(fetch).mockResolvedValue(apiResponse({ accepted: true }));

    await expect(requestApi<{ accepted: boolean }>('/api/public/check', {
      method: 'POST',
      body: { value: 'test' },
    })).resolves.toEqual({ accepted: true });

    expect(resolveApiUrl('/api/public/check')).toBe('/api/public/check');
    expect(fetch).toHaveBeenCalledWith('/api/public/check', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ value: 'test' }),
    }));
    const headers = vi.mocked(fetch).mock.calls[0][1]?.headers as Headers;
    expect(headers.get('Content-Type')).toBe('application/json');
  });

  it('preserves business error details and trace ID', async () => {
    vi.mocked(fetch).mockResolvedValue(apiResponse(null, {
      code: 'DATA_SOURCE_UNAVAILABLE',
      message: '外部数据源暂时不可用',
      status: 503,
      traceId: 'trace-503',
    }));

    await expect(requestApi('/api/public/check')).rejects.toMatchObject({
      name: 'ApiClientError',
      status: 503,
      code: 'DATA_SOURCE_UNAVAILABLE',
      traceId: 'trace-503',
      message: '外部数据源暂时不可用（追踪号：trace-503）',
    });
  });

  it('uses the response header trace ID when an error body is not JSON', async () => {
    vi.mocked(fetch).mockResolvedValue({
      ok: false,
      status: 502,
      headers: new Headers({ 'X-Trace-Id': 'header-trace' }),
      text: async () => 'upstream gateway error',
    } as Response);

    await expect(requestApi('/api/public/check')).rejects.toMatchObject({
      message: '请求失败（HTTP 502）（追踪号：header-trace）',
      traceId: 'header-trace',
    });
  });

  it('adds the bearer token and clears the session after an unauthorized response', async () => {
    setAdminSession({
      accessToken: 'signed-jwt',
      tokenType: 'Bearer',
      expiresAt: '2099-01-01T00:00:00Z',
      adminId: 7,
      username: 'operator',
      role: 'ADMIN',
    });
    vi.mocked(fetch).mockResolvedValue(apiResponse(null, {
      code: 'AUTH_UNAUTHORIZED',
      message: '请先登录或重新登录',
      status: 401,
    }));

    await expect(requestApi('/api/admin/check', { authenticated: true })).rejects.toBeInstanceOf(ApiClientError);

    const headers = vi.mocked(fetch).mock.calls[0][1]?.headers as Headers;
    expect(headers.get('Authorization')).toBe('Bearer signed-jwt');
    expect(getAdminSession()).toBeNull();
  });

  it('reports a timeout after 15 seconds', async () => {
    vi.useFakeTimers();
    vi.mocked(fetch).mockImplementation((_, init) => new Promise((_, reject) => {
      (init?.signal as AbortSignal).addEventListener('abort', () => {
        reject(new DOMException('aborted', 'AbortError'));
      });
    }) as Promise<Response>);

    const request = requestApi('/api/public/slow');
    const assertion = expect(request).rejects.toMatchObject({
      message: '请求超时，请稍后重试',
    });
    await vi.advanceTimersByTimeAsync(15_000);

    await assertion;
  });

  it('keeps caller initiated cancellation distinct from a network failure', async () => {
    vi.mocked(fetch).mockImplementation((_, init) => new Promise((_, reject) => {
      (init?.signal as AbortSignal).addEventListener('abort', () => {
        reject(new DOMException('aborted', 'AbortError'));
      });
    }) as Promise<Response>);
    const controller = new AbortController();
    const request = requestApi('/api/public/cancelled', { signal: controller.signal });

    controller.abort();

    await expect(request).rejects.toMatchObject({ name: 'AbortError' });
  });
});
