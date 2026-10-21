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
    scoringMode: 'ASIAN_DIRECTION',
    brierScore: 0.1234,
    logLoss: 0.5678,
    marketBrierScore: 0.1567,
    brierAdvantage: 0.0333,
    modelOutperformsMarket: true,
    unavailableReasons: [],
  },
  markets: [
    {
      marketType: 'ASIAN_HANDICAP',
      settledSampleSize: 6,
      hitCount: 3,
      missCount: 2,
      pushCount: 1,
      halfWinCount: 0,
      halfLossCount: 0,
      pendingCount: 1,
      voidCount: 1,
      hitRate: 0.6,
      weightedHitRate: 0.5833,
    },
    {
      marketType: 'ASIAN_TOTAL_GOALS',
      settledSampleSize: 6,
      hitCount: 2,
      missCount: 2,
      pushCount: 0,
      halfWinCount: 1,
      halfLossCount: 1,
      pendingCount: 1,
      voidCount: 1,
      hitRate: 0.5,
      weightedHitRate: 0.5,
    },
  ],
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

  it('renders every settled market of the selected product line', async () => {
    vi.mocked(fetch).mockResolvedValue(apiResponse(summary));
    renderStatistics();

    await screen.findByText('请求范围');
    expect(screen.getAllByText('亚盘让球 命中率').length).toBeGreaterThan(0);
    expect(screen.getAllByText('亚盘大小球 命中率').length).toBeGreaterThan(0);
    // 走盘与半赢半输必须单独出现，否则命中率的分母口径无从核对。
    expect(screen.getAllByText(/1 走盘/).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/1 半赢 · 1 半输/).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/加权命中率/).length).toBeGreaterThan(0);
    // 双 Brier：模型与市场必须并列，单看模型分数无法判断有没有跑赢盘口。
    expect(screen.getAllByText('0.1567').length).toBeGreaterThan(0);
    expect(screen.getAllByText('模型优于市场').length).toBeGreaterThan(0);
  });

  it('requests one product line at a time and records the switch in the URL', async () => {
    const user = userEvent.setup();
    vi.mocked(fetch).mockResolvedValue(apiResponse(summary));
    renderStatistics();

    await screen.findByText('请求范围');
    expect(statisticBodies()[0]).toEqual(expect.objectContaining({ predictionType: 'ASIAN' }));

    await user.click(screen.getByRole('tab', { name: '竞彩' }));

    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('type=SPORTTERY'));
    await waitFor(() => {
      const bodies = statisticBodies();
      expect(bodies[bodies.length - 1]).toEqual(expect.objectContaining({ predictionType: 'SPORTTERY' }));
    });
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
