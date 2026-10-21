import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { HomeSummaryVo } from '../../services/public';
import HomePage from './HomePage';

const summary: HomeSummaryVo = {
  asOfDate: '2026-07-28',
  today: { matchCount: 8, publishedPredictionMatchCount: 5 },
  pendingSettlementMatchCount: 3,
  historicalPublishedMatchCount: 42,
  trailingSevenDays: {
    startDate: '2026-07-22',
    endDate: '2026-07-28',
    metrics: {
      lockedPredictionCount: 8,
      finalFactCount: 5,
      pendingFactCount: 2,
      voidFactCount: 1,
      probabilityMetrics: { sampleSize: 5, scoringMode: 'ASIAN_DIRECTION', brierScore: 0.1234, logLoss: 0.4, marketBrierScore: 0.13, brierAdvantage: 0.0066, modelOutperformsMarket: true, unavailableReasons: [] },
      markets: [
      { marketType: 'ASIAN_HANDICAP', settledSampleSize: 5, hitCount: 3, missCount: 2, pushCount: 1, halfWinCount: 0, halfLossCount: 0, pendingCount: 2, voidCount: 1, hitRate: 0.6, weightedHitRate: 0.5 },
      { marketType: 'ASIAN_TOTAL_GOALS', settledSampleSize: 5, hitCount: 2, missCount: 2, pushCount: 0, halfWinCount: 1, halfLossCount: 1, pendingCount: 2, voidCount: 1, hitRate: 0.5, weightedHitRate: 0.5 },
      ],
      roi: { available: false, roi: null, yield: null, sampleSize: 0, unavailableReasons: ['MISSING_FIXED_BETTING_RULE'] },
    },
  },
  trailingThirtyDays: {
    startDate: '2026-06-29',
    endDate: '2026-07-28',
    metrics: {
      lockedPredictionCount: 42,
      finalFactCount: 30,
      pendingFactCount: 8,
      voidFactCount: 4,
      probabilityMetrics: { sampleSize: 0, scoringMode: 'ASIAN_DIRECTION', brierScore: null, logLoss: null, marketBrierScore: null, brierAdvantage: null, modelOutperformsMarket: null, unavailableReasons: ['NO_SETTLED_ASIAN_SAMPLE'] },
      markets: [
      { marketType: 'ASIAN_HANDICAP', settledSampleSize: 30, hitCount: 17, missCount: 13, pushCount: 0, halfWinCount: 0, halfLossCount: 0, pendingCount: 8, voidCount: 4, hitRate: 0.57, weightedHitRate: 0.57 },
      { marketType: 'ASIAN_TOTAL_GOALS', settledSampleSize: 30, hitCount: 15, missCount: 15, pushCount: 0, halfWinCount: 0, halfLossCount: 0, pendingCount: 8, voidCount: 4, hitRate: 0.5, weightedHitRate: 0.5 },
      ],
      roi: { available: false, roi: null, yield: null, sampleSize: 0, unavailableReasons: ['MISSING_FIXED_BETTING_RULE'] },
    },
  },
  dataFreshness: { sportteryLastCapturedAt: null, sportteryDataAgeSeconds: null },
  latestPublishedSnapshotAt: null,
  generatedAt: '2026-07-28T01:00:00Z',
};

function apiResponse(data: unknown, options: { code?: string; message?: string; status?: number; traceId?: string } = {}) {
  const status = options.status ?? 200;
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: new Headers({ 'X-Trace-Id': options.traceId ?? 'home-page-test' }),
    text: async () => JSON.stringify({
      code: options.code ?? 'SUCCESS',
      message: options.message ?? '操作成功',
      data,
      traceId: options.traceId ?? 'home-page-test',
    }),
  } as Response;
}

function renderHome() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } });
  return render(
    <MemoryRouter>
      <QueryClientProvider client={queryClient}><HomePage /></QueryClientProvider>
    </MemoryRouter>,
  );
}

describe('HomePage', () => {
  beforeEach(() => vi.stubGlobal('fetch', vi.fn()));
  afterEach(() => vi.unstubAllGlobals());

  it('shows fact cards, unavailable metrics, freshness gaps and public entries', async () => {
    vi.mocked(fetch).mockResolvedValue(apiResponse(summary));
    renderHome();

    expect(await screen.findByRole('heading', { name: '公开事实，持续检验每一场预测' })).toBeInTheDocument();
    expect(await screen.findByText('今日竞彩比赛')).toBeInTheDocument();
    expect(screen.getByText('累计公开预测比赛')).toBeInTheDocument();
    expect(screen.getByText(/暂无当天体彩采集/)).toBeInTheDocument();
    expect(screen.getByText('当前没有已发布快照')).toBeInTheDocument();
    // 首页固定亚盘，缺样本时的原因文案必须是亚盘口径的那一个。
    expect(screen.getAllByText('概率指标暂不可用：没有已结算的亚盘方向样本')).toHaveLength(1);
    expect(screen.getByText('ROI / Yield 暂不可用：缺少冻结的固定下注规则')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '查看每日比赛' })).toHaveAttribute('href', '/matches');
    expect(screen.getByText('分析工具，不构成投注建议')).toBeInTheDocument();
  });

  it('keeps the API trace ID and can refresh the summary', async () => {
    vi.mocked(fetch)
      .mockResolvedValueOnce(apiResponse(null, { code: 'DATA_SOURCE_UNAVAILABLE', message: '首页暂不可用', status: 503, traceId: 'home-trace-503' }))
      .mockResolvedValueOnce(apiResponse(summary));
    renderHome();

    expect(await screen.findByRole('alert')).toHaveTextContent('首页汇总暂不可用：首页暂不可用（追踪号：home-trace-503）');

    fireEvent.click(screen.getByRole('button', { name: '刷新' }));
    await waitFor(() => expect(screen.getByText('今日已发布预测')).toBeInTheDocument());
    expect(vi.mocked(fetch)).toHaveBeenCalledTimes(2);
  });
});
