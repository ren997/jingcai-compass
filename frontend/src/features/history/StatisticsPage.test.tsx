import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { StatisticsMetricsVo, StatisticsSummaryVo } from '../../services/public';
import StatisticsPage from './StatisticsPage';

const metrics: StatisticsMetricsVo = {
  lockedPredictionCount: 8,
  finalFactCount: 6,
  pendingFactCount: 1,
  voidFactCount: 1,
  probabilityMetrics: {
    sampleSize: 6,
    brierScore: 0.1234,
    logLoss: 0.5678,
    unavailableReasons: [],
  },
  had: {
    marketType: 'HAD',
    settledSampleSize: 6,
    hitCount: 4,
    missCount: 2,
    pendingCount: 1,
    voidCount: 1,
    hitRate: 2 / 3,
  },
  hhad: {
    marketType: 'HHAD',
    settledSampleSize: 6,
    hitCount: 3,
    missCount: 3,
    pendingCount: 1,
    voidCount: 1,
    hitRate: 0.5,
  },
  roi: {
    available: false,
    roi: null,
    yield: null,
    sampleSize: 0,
    unavailableReasons: ['MISSING_FIXED_BETTING_RULE', 'MISSING_LOCKED_ODDS_INPUT'],
  },
};

const summary: StatisticsSummaryVo = {
  asOfDate: '2026-07-28',
  appliedFilter: { leagueId: null, modelVersion: null },
  requestedWindow: { startDate: '2026-06-29', endDate: '2026-07-28', metrics },
  trailingSevenDays: { startDate: '2026-07-22', endDate: '2026-07-28', metrics },
  trailingThirtyDays: { startDate: '2026-06-29', endDate: '2026-07-28', metrics },
  byLeague: [{ leagueId: 7, leagueName: '英超', metrics }],
  byModelVersion: [{ modelVersion: 'model-v1', metrics }],
};

function apiResponse(data: unknown, options: { code?: string; message?: string; status?: number; traceId?: string } = {}) {
  const status = options.status ?? 200;
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: new Headers({ 'X-Trace-Id': options.traceId ?? 'statistics-page-test' }),
    text: async () => JSON.stringify({
      code: options.code ?? 'SUCCESS',
      message: options.message ?? '操作成功',
      data,
      traceId: options.traceId ?? 'statistics-page-test',
    }),
  } as Response;
}

function LocationProbe() {
  const location = useLocation();
  return <output data-testid="location">{location.search}</output>;
}

function renderStatistics(path = '/statistics') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } });
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/statistics" element={<><StatisticsPage /><LocationProbe /></>} />
      </Routes>
    </MemoryRouter>,
    { wrapper: ({ children }) => <QueryClientProvider client={queryClient}>{children}</QueryClientProvider> },
  );
}

function statisticBodies() {
  return vi.mocked(fetch).mock.calls
    .filter(([url]) => String(url).endsWith('/api/public/statistics/summary'))
    .map(([, init]) => JSON.parse(String(init?.body)) as Record<string, unknown>);
}

describe('StatisticsPage', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('shows requested and trailing windows without inventing unavailable ROI', async () => {
    vi.mocked(fetch).mockResolvedValue(apiResponse(summary));
    renderStatistics();

    expect(await screen.findByText('请求范围')).toBeInTheDocument();
    expect(screen.getByText('近 7 天')).toBeInTheDocument();
    expect(screen.getByText('近 30 天')).toBeInTheDocument();
    expect(screen.getAllByText('0.1234').length).toBeGreaterThan(0);
    expect(screen.getAllByText(/ROI \/ Yield 暂不可用：缺少冻结的固定下注规则；缺少锁定时点赔率输入/).length).toBeGreaterThan(0);
    expect(screen.getByRole('heading', { name: '按联赛分布' })).toBeInTheDocument();
    expect(screen.getByText('英超')).toBeInTheDocument();
  });

  it('writes time, league and model filters to the URL and request body', async () => {
    const user = userEvent.setup();
    vi.mocked(fetch).mockResolvedValue(apiResponse(summary));
    renderStatistics();

    await screen.findByText('请求范围');
    await user.type(screen.getByLabelText('统计模型版本'), 'model-v2');

    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('modelVersion=model-v2'));
    await waitFor(() => expect(statisticBodies()).toEqual(expect.arrayContaining([
      expect.objectContaining({ modelVersion: 'model-v2' }),
    ])));
  });

  it('shows empty and traceable error states', async () => {
    vi.mocked(fetch)
      .mockResolvedValueOnce(apiResponse({
        ...summary,
        requestedWindow: { ...summary.requestedWindow, metrics: { ...metrics, lockedPredictionCount: 0 } },
      }))
      .mockResolvedValueOnce(apiResponse(null, { code: 'DATA_SOURCE_UNAVAILABLE', message: '统计查询暂不可用', status: 503, traceId: 'statistics-page-trace' }));
    const { unmount } = renderStatistics();

    expect(await screen.findByText('当前筛选范围暂无已锁定预测，指标按无样本口径展示。')).toBeInTheDocument();
    unmount();
    renderStatistics();

    expect(await screen.findByText('公开统计暂不可用：统计查询暂不可用（追踪号：statistics-page-trace）')).toBeInTheDocument();
  });
});
