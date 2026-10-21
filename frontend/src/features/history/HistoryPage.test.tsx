import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { HistoryListItemVo, PageResult } from '../../services/public';
import HistoryPage from './HistoryPage';

const record: HistoryListItemVo = {
  predictionId: 301,
  predictionVersion: 2,
  modelVersion: 'model-v1',
  featureVersion: 'feature-v2',
  predictionStatus: 'LOCKED',
  predictionType: 'SPORTTERY',
  homeWinProb: 0.45,
  drawProb: 0.3,
  awayWinProb: 0.25,
  handicapPick: 'HOME_WIN',
  expectedTotalGoals: 2.5,
  asianHandicapPick: null,
  totalGoalsPick: null,
  confidenceLevel: 'HIGH',
  analysisSummary: '公开历史分析。',
  predictionHash: 'a'.repeat(64),
  generatedAt: '2026-07-20T08:00:00Z',
  publishTime: '2026-07-20T08:01:00Z',
  lockTime: '2026-07-22T12:00:00Z',
  asianModel: null,
  match: {
    matchId: 42,
    lotteryDate: '2026-07-22',
    lotteryMatchNo: '周三042',
    leagueId: 7,
    leagueName: '英超',
    homeTeamName: '曼彻斯特城',
    awayTeamName: '阿森纳',
    kickoffTime: '2026-07-22T19:30:00Z',
  },
  resultFacts: [
    {
      factId: 401,
      factVersion: 1,
      supersedesFactVersion: null,
      factStatus: 'FINAL',
      matchStatus: 'FINISHED',
      homeScore: 1,
      awayScore: 1,
      providerUpdatedAt: '2026-07-22T22:00:00Z',
      current: false,
      createdAt: '2026-07-22T22:01:00Z',
    },
    {
      factId: 402,
      factVersion: 2,
      supersedesFactVersion: 1,
      factStatus: 'FINAL',
      matchStatus: 'FINISHED',
      homeScore: 2,
      awayScore: 1,
      providerUpdatedAt: '2026-07-22T23:00:00Z',
      current: true,
      createdAt: '2026-07-22T23:01:00Z',
    },
  ],
  settlementMarkets: [
    {
      marketType: 'HAD',
      currentStatus: 'MISS',
      currentSettlementPersisted: true,
      recalculatedAfterFactCorrection: true,
      versions: [
        { settlementId: 501, settlementVersion: 1, supersedesSettlementVersion: null, settlementStatus: 'HIT', matchFactId: 401, ruleVersion: 't403-v1', profit: 0.95, current: false, createdAt: '2026-07-22T22:02:00Z' },
        { settlementId: 502, settlementVersion: 2, supersedesSettlementVersion: 1, settlementStatus: 'MISS', matchFactId: 402, ruleVersion: 't403-v1', profit: -1, current: true, createdAt: '2026-07-22T23:02:00Z' },
      ],
    },
    {
      marketType: 'HHAD',
      currentStatus: 'PENDING',
      currentSettlementPersisted: false,
      recalculatedAfterFactCorrection: false,
      versions: [],
    },
  ],
  recalculatedAfterFactCorrection: true,
};

function apiResponse(data: unknown, options: { code?: string; message?: string; status?: number; traceId?: string } = {}) {
  const status = options.status ?? 200;
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: new Headers({ 'X-Trace-Id': options.traceId ?? 'history-page-test' }),
    text: async () => JSON.stringify({
      code: options.code ?? 'SUCCESS',
      message: options.message ?? '操作成功',
      data,
      traceId: options.traceId ?? 'history-page-test',
    }),
  } as Response;
}

function createTestQueryClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } });
}

function LocationProbe() {
  const location = useLocation();
  return <output data-testid="location">{location.search}</output>;
}

function renderHistoryWithQuery(path = '/history') {
  const queryClient = createTestQueryClient();
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/history" element={<><HistoryPage /><LocationProbe /></>} />
      </Routes>
    </MemoryRouter>,
    {
      wrapper: ({ children }) => {
        return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
      },
    },
  );
}

function historyBodies() {
  return vi.mocked(fetch).mock.calls
    .filter(([url]) => String(url).endsWith('/api/public/history/list'))
    .map(([, init]) => JSON.parse(String(init?.body)) as Record<string, unknown>);
}

describe('HistoryPage', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('keeps missed, pending and corrected history versions visible', async () => {
    vi.mocked(fetch).mockResolvedValue(apiResponse({ records: [record], pageNo: 1, pageSize: 20, total: 1 } satisfies PageResult<HistoryListItemVo>));
    renderHistoryWithQuery();

    expect(await screen.findByText('曼彻斯特城')).toBeInTheDocument();
    expect(screen.getByText('赛果修正后重算')).toBeInTheDocument();
    expect(screen.getByText(/胜平负（HAD）：/)).toHaveTextContent('未中');
    expect(screen.getByText(/让球胜平负（HHAD）：/)).toHaveTextContent('待结算');
    expect(screen.getByText('查看赛果与结算版本')).toBeInTheDocument();
    expect(screen.getByText(/第 2 版 · 2 : 1 · 最终赛果 · 当前/)).toBeInTheDocument();
  });

  it('writes settlement filters to the URL and resets pagination', async () => {
    const user = userEvent.setup();
    vi.mocked(fetch).mockResolvedValue(apiResponse({ records: [record], pageNo: 2, pageSize: 20, total: 41 } satisfies PageResult<HistoryListItemVo>));
    renderHistoryWithQuery('/history?market=HHAD&page=2');

    expect(await screen.findByText('第 2 / 3 页')).toBeInTheDocument();
    await user.click(screen.getByRole('checkbox', { name: '待结算' }));

    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('type=SPORTTERY&market=HHAD&page=1&statuses=PENDING'));
    await waitFor(() => expect(historyBodies()).toEqual(expect.arrayContaining([
      expect.objectContaining({ settlementMarket: 'HHAD', settlementStatuses: ['PENDING'], pageNo: 1, pageSize: 20 }),
    ])));
  });

  it('shows the asian direction, direction-adjusted probability and profit', async () => {
    const asianRecord: HistoryListItemVo = {
      ...record,
      predictionId: 302,
      predictionType: 'ASIAN',
      homeWinProb: null,
      drawProb: null,
      awayWinProb: null,
      handicapPick: null,
      expectedTotalGoals: null,
      asianHandicapPick: 'AWAY_COVER',
      totalGoalsPick: 'UNDER',
      asianModel: {
        lambdaHome: 1.42,
        lambdaAway: 1.18,
        // 首边口径：主队赢盘 53%，那么大球 49%。
        modelCoverProb: 0.53,
        marketCoverProb: 0.51,
        edgeCover: 0.02,
        modelOverProb: 0.49,
        marketOverProb: 0.5,
        edgeOver: -0.01,
        handicapDispersion: 0.013,
        totalsDispersion: 0.021,
      },
      settlementMarkets: [
        {
          marketType: 'ASIAN_HANDICAP',
          currentStatus: 'HALF_WIN',
          currentSettlementPersisted: true,
          recalculatedAfterFactCorrection: false,
          versions: [
            { settlementId: 601, settlementVersion: 1, supersedesSettlementVersion: null, settlementStatus: 'HALF_WIN', matchFactId: 402, ruleVersion: 'asian-v1', profit: 0.475, current: true, createdAt: '2026-07-22T23:05:00Z' },
          ],
        },
      ],
    };
    vi.mocked(fetch).mockResolvedValue(apiResponse({ records: [asianRecord], pageNo: 1, pageSize: 20, total: 1 } satisfies PageResult<HistoryListItemVo>));
    renderHistoryWithQuery('/history?type=ASIAN&market=ASIAN_HANDICAP');

    expect(await screen.findByText('曼彻斯特城')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: '亚盘' })).toHaveAttribute('aria-selected', 'true');
    // 卡片上的产品线徽标与 tab 同文案，因此这里按"至少两处"断言。
    expect(screen.getAllByText('亚盘').length).toBeGreaterThan(1);
    expect(screen.getByText(/客队受让 \/ 小球/)).toBeInTheDocument();
    // 押客队受让 ⇒ 1 − 53% = 47%；押小球 ⇒ 1 − 49% = 51%。把首边概率直接显示会得出相反结论。
    expect(screen.getByText(/让球 47.0% \/ 大小球 51.0%/)).toBeInTheDocument();
    // 市场共识列的是首边（主队赢盘 / 大球）去水概率，与所选方向无关。
    expect(screen.getByText(/让球主队 51.0% \/ 大球 50.0%/)).toBeInTheDocument();
    expect(screen.getAllByText(/半赢/).length).toBeGreaterThan(0);
    expect(screen.getByText(/收益 \+0\.4750/)).toBeInTheDocument();
  });

  it('shows explicit empty and traceable error states', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(apiResponse({ records: [], pageNo: 1, pageSize: 20, total: 0 }))
      .mockResolvedValueOnce(apiResponse(null, { code: 'DATA_SOURCE_UNAVAILABLE', message: '历史查询暂不可用', status: 503, traceId: 'history-page-trace' }));
    const { unmount } = renderHistoryWithQuery();

    expect(await screen.findByText('当前筛选条件下暂无公开预测历史。')).toBeInTheDocument();
    unmount();
    renderHistoryWithQuery();

    expect(await screen.findByText('公开历史暂不可用：历史查询暂不可用（追踪号：history-page-trace）')).toBeInTheDocument();
  });
});
