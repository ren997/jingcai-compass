import { clearAdminSession, getAdminSession } from './adminSession';
import type { ApiResponse } from '../types/api';

const DEFAULT_TIMEOUT_MS = 15_000;
const TRACE_ID_HEADER = 'X-Trace-Id';

type ApiErrorOptions = {
  status?: number;
  code?: string;
  traceId?: string;
};

/** API 调用失败，保留服务端错误码与追踪号。 */
export class ApiClientError extends Error {
  readonly status?: number;
  readonly code?: string;
  readonly traceId?: string;

  constructor(message: string, options: ApiErrorOptions = {}) {
    super(options.traceId ? `${message}（追踪号：${options.traceId}）` : message);
    this.name = 'ApiClientError';
    this.status = options.status;
    this.code = options.code;
    this.traceId = options.traceId;
  }

  get isUnauthorized() {
    return this.status === 401 || this.code === 'AUTH_UNAUTHORIZED';
  }
}

export type ApiRequestOptions = Omit<RequestInit, 'body' | 'signal'> & {
  body?: unknown;
  signal?: AbortSignal;
  authenticated?: boolean;
  timeoutMs?: number;
};

function apiBaseUrl() {
  return (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '');
}

/** 拼接同源或环境指定的 API 基础地址。 */
export function resolveApiUrl(path: string) {
  return `${apiBaseUrl()}${path.startsWith('/') ? path : `/${path}`}`;
}

function parseResponseBody(value: string): unknown {
  if (!value.trim()) {
    return null;
  }
  try {
    return JSON.parse(value) as unknown;
  } catch {
    return null;
  }
}

function isApiResponse(value: unknown): value is ApiResponse<unknown> {
  return typeof value === 'object'
    && value !== null
    && typeof (value as ApiResponse<unknown>).code === 'string'
    && typeof (value as ApiResponse<unknown>).message === 'string'
    && typeof (value as ApiResponse<unknown>).traceId === 'string';
}

function toApiError(response: Response, payload: unknown): ApiClientError {
  const apiResponse = isApiResponse(payload) ? payload : null;
  const traceId = apiResponse?.traceId ?? response.headers.get(TRACE_ID_HEADER) ?? undefined;
  const message = apiResponse?.message || `请求失败（HTTP ${response.status}）`;
  return new ApiClientError(message, {
    status: response.status,
    code: apiResponse?.code,
    traceId,
  });
}

/** 执行统一格式的后端 API 请求。 */
export async function requestApi<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
  const {
    body,
    signal,
    authenticated = false,
    timeoutMs = DEFAULT_TIMEOUT_MS,
    headers,
    ...requestOptions
  } = options;
  const controller = new AbortController();
  let timedOut = false;
  const timeoutId = window.setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, timeoutMs);
  const abortRequest = () => controller.abort();
  signal?.addEventListener('abort', abortRequest, { once: true });

  try {
    const requestHeaders = new Headers(headers);
    if (body !== undefined && !requestHeaders.has('Content-Type')) {
      requestHeaders.set('Content-Type', 'application/json');
    }
    if (authenticated) {
      const session = getAdminSession();
      if (!session) {
        throw new ApiClientError('请先登录或重新登录', { status: 401, code: 'AUTH_UNAUTHORIZED' });
      }
      requestHeaders.set('Authorization', `${session.tokenType} ${session.accessToken}`);
    }

    const response = await fetch(resolveApiUrl(path), {
      ...requestOptions,
      headers: requestHeaders,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal,
    });
    const payload = parseResponseBody(await response.text());
    if (!response.ok || !isApiResponse(payload) || payload.code !== 'SUCCESS') {
      throw toApiError(response, payload);
    }
    return payload.data as T;
  } catch (error) {
    if (timedOut) {
      throw new ApiClientError('请求超时，请稍后重试');
    }
    if (error instanceof ApiClientError) {
      if (error.isUnauthorized) {
        clearAdminSession();
      }
      throw error;
    }
    if (signal?.aborted) {
      throw error;
    }
    throw new ApiClientError('网络连接失败，请稍后重试');
  } finally {
    window.clearTimeout(timeoutId);
    signal?.removeEventListener('abort', abortRequest);
  }
}
