import { QueryClient } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import App from './App';
import AppErrorBoundary from './AppErrorBoundary';
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

function renderApp() {
  return render(
    <AppProviders queryClient={createTestQueryClient()}>
      <App />
    </AppProviders>,
  );
}

describe('App', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('renders the match list from the common API response', async () => {
    vi.mocked(fetch).mockResolvedValue({
      ok: true,
      json: async () => ({
        code: 'SUCCESS',
        message: '操作成功',
        traceId: 'match-list-test',
        data: [
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
        ],
      }),
    } as Response);

    renderApp();

    expect(screen.getByRole('heading', { name: '今日竞彩比赛' })).toBeInTheDocument();
    expect(screen.getByText('正在加载比赛池……')).toBeInTheDocument();
    expect(await screen.findByText('曼彻斯特城')).toBeInTheDocument();
    expect(screen.getByText('阿森纳')).toBeInTheDocument();
    expect(screen.getByText('Stub 演示数据')).toBeInTheDocument();
  });

  it('shows the API message and trace ID when loading fails', async () => {
    vi.mocked(fetch).mockResolvedValue({
      ok: false,
      json: async () => ({
        code: 'DATA_SOURCE_UNAVAILABLE',
        message: '外部数据源暂时不可用',
        data: null,
        traceId: 'trace-error-001',
      }),
    } as Response);

    renderApp();

    expect(
      await screen.findByText(/外部数据源暂时不可用（追踪号：trace-error-001）/),
    ).toBeInTheDocument();
  });
});

describe('AppErrorBoundary', () => {
  it('renders a stable fallback for an unexpected render error', () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const preventExpectedWindowError = (event: ErrorEvent) => event.preventDefault();
    window.addEventListener('error', preventExpectedWindowError);

    function BrokenView(): JSX.Element {
      throw new Error('render failed');
    }

    try {
      render(
        <AppErrorBoundary>
          <BrokenView />
        </AppErrorBoundary>,
      );
    } finally {
      window.removeEventListener('error', preventExpectedWindowError);
    }

    expect(screen.getByRole('alert')).toHaveTextContent('页面加载失败，请刷新后重试。');
  });
});
