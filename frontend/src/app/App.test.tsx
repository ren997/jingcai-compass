import { QueryClient } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { clearAdminSession, getAdminSession, setAdminSession } from '../services/adminSession';
import App from './App';
import AppProviders from './AppProviders';

function createTestQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
        gcTime: Infinity,
      },
    },
  });
}

function apiResponse(data: unknown, options: { code?: string; message?: string; status?: number; traceId?: string } = {}) {
  const status = options.status ?? 200;
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: new Headers({ 'X-Trace-Id': options.traceId ?? 'router-test' }),
    text: async () => JSON.stringify({
      code: options.code ?? 'SUCCESS',
      message: options.message ?? '操作成功',
      data,
      traceId: options.traceId ?? 'router-test',
    }),
  } as Response;
}

function renderApp(path = '/') {
  window.history.pushState({}, '', path);
  return render(
    <AppProviders queryClient={createTestQueryClient()}>
      <App />
    </AppProviders>,
  );
}

describe('App routes', () => {
  beforeEach(() => {
    clearAdminSession();
    vi.stubGlobal('fetch', vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('redirects the root route and renders the match list through the public layout', async () => {
    vi.mocked(fetch).mockResolvedValue(apiResponse([
      {
        matchId: 'stub-001',
        lotteryDate: '2026-07-22',
        lotteryMatchNo: '周三001',
        leagueName: '英超',
        homeTeamName: '曼彻斯特城',
        awayTeamName: '阿森纳',
        kickoffTime: '2026-07-22T19:30:00+08:00',
        officialHandicap: -1,
        matchStatus: 'SCHEDULED',
        dataSource: 'STUB',
      },
    ]));

    renderApp();

    expect(await screen.findByRole('heading', { name: '今日竞彩比赛' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '竞彩罗盘' })).toBeInTheDocument();
    expect(await screen.findByText('曼彻斯特城')).toBeInTheDocument();
    expect(window.location.pathname).toBe('/matches');
  });

  it('shows the server message and trace ID for a failed public query', async () => {
    vi.mocked(fetch).mockResolvedValue(apiResponse(null, {
      code: 'DATA_SOURCE_UNAVAILABLE',
      message: '外部数据源暂时不可用',
      status: 503,
      traceId: 'trace-error-001',
    }));

    renderApp('/matches');

    expect(
      await screen.findByText(/外部数据源暂时不可用（追踪号：trace-error-001）/),
    ).toBeInTheDocument();
  });

  it('redirects anonymous visitors from the admin route to login', async () => {
    renderApp('/admin');

    expect(await screen.findByRole('heading', { name: '管理员登录' })).toBeInTheDocument();
    expect(window.location.pathname).toBe('/admin/login');
  });

  it('logs in and returns to the originally requested admin route', async () => {
    const user = userEvent.setup();
    vi.mocked(fetch).mockResolvedValue(apiResponse({
      accessToken: 'signed-jwt',
      tokenType: 'Bearer',
      expiresAt: '2099-01-01T00:00:00Z',
      adminId: 7,
      username: 'operator',
      role: 'ADMIN',
    }));

    renderApp('/admin');

    await user.type(await screen.findByLabelText('用户名'), 'operator');
    await user.type(screen.getByLabelText('密码'), 'correct-password');
    await user.click(screen.getByRole('button', { name: '登录后台' }));

    expect(await screen.findByRole('heading', { name: '后台已就绪' })).toBeInTheDocument();
    expect(window.location.pathname).toBe('/admin');
    expect(getAdminSession()?.username).toBe('operator');
  });

  it('clears the session and returns to login after logout', async () => {
    const user = userEvent.setup();
    setAdminSession({
      accessToken: 'signed-jwt',
      tokenType: 'Bearer',
      expiresAt: '2099-01-01T00:00:00Z',
      adminId: 7,
      username: 'operator',
      role: 'ADMIN',
    });
    vi.mocked(fetch).mockResolvedValue(apiResponse(null));

    renderApp('/admin');

    await user.click(await screen.findByRole('button', { name: '退出登录' }));

    expect(await screen.findByRole('heading', { name: '管理员登录' })).toBeInTheDocument();
    expect(getAdminSession()).toBeNull();
  });

  it('renders a stable 404 page for unmatched routes', async () => {
    renderApp('/missing-page');

    expect(await screen.findByRole('heading', { name: '页面不存在' })).toBeInTheDocument();
  });
});
