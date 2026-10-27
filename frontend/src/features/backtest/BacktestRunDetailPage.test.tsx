import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type {
  BacktestCaseVo,
  BacktestComparisonVo,
  BacktestMetricsVo,
  BacktestRunVo,
} from '../../services/backtest';
import BacktestRunDetailPage from './BacktestRunDetailPage';

vi.mock('../../services/adminSession', () => ({
  getAdminSession: () => ({
    accessToken: 'detail-test-token',
    tokenType: 'Bearer',
    expiresAt: '2030-01-01T00:00:00Z',
    username: 'admin',
  }),
  setAdminSession: () => {},
  clearAdminSession: () => {},
  subscribeAdminSession: () => () => {},
}));

function run(overrides: Partial<BacktestRunVo> = {}): BacktestRunVo {
  return {
    id: 6,
    runKey: '8c63ebb9f0384925a6584b3b572a4fa4',
    runName: '十月全月 10-01~10-26',
    runStatus: 'PARTIAL',
    runStage: 'DONE',
    operatorUsername: 'admin',
    dateFrom: '2026-10-01',
    dateTo: '2026-10-26',
    leagueNames: [],
    decisionLeadMinutes: 105,
    parameterSets: [
      {
        groupKey: 'baseline',
        highDirectionSignal: 0.075,
        highConfidenceMargin: 0.1,
        mediumDirectionSignal: 0.025,
        mediumConfidenceMargin: 0.16,
      },
    ],
    providerCode: 'NOVA_API',
    modelVersion: 't306-asian-baseline-v6',
    selectorVersion: 'freeze-selector-v2',
    aggregationVersion: 'consensus-devig-median-v1',
    builderVersion: 'odds-history-slice-v1',
    gitSha: null,
    totalMatchCount: 81,
    processedMatchCount: 81,
    archiveReusedCount: 50,
    archiveFetchedCount: 31,
    archiveFailedCount: 0,
    caseCount: 160,
    estimateQuotaCost: 32,
    quotaCost: 31,
    cancelRequested: false,
    resumeCount: 0,
    errorCode: null,
    errorMessage: '补采失败 0 场 / 上游无可用序列 1 场 / 待结算样本 36',
    createdAt: '2026-10-27T12:26:45Z',
    startedAt: '2026-10-27T12:27:27Z',
    finishedAt: '2026-10-27T12:30:23Z',
    lastHeartbeatAt: '2026-10-27T12:30:23Z',
    progressRatio: 1,
    ...overrides,
  };
}

function metrics(overrides: Partial<BacktestMetricsVo> = {}): BacktestMetricsVo {
  return {
    runId: 6,
    groupKey: 'baseline',
    marketScope: 'ALL',
    caseCount: 80,
    decidedCount: 32,
    skippedCount: 48,
    pickCount: 46,
    settledCount: 40,
    pushCount: 6,
    halfWinCount: 1,
    halfLossCount: 4,
    hitCount: 14,
    missCount: 15,
    hitRate: 0.441176,
    weightedHitRate: 0.426471,
    profitSum: -4.605,
    roi: -0.115125,
    yieldRate: -0.135441,
    brierModel: 0.190517,
    brierMarket: 0.190517,
    brierDelta: 0,
    brierSampleSize: 40,
    skipReasonCounts: null,
    metricsJson: null,
    computedAt: '2026-10-27T12:30:23Z',
    ...overrides,
  };
}

const comparison: BacktestComparisonVo = {
  runId: 6,
  groupKeys: ['baseline'],
  bestGroupKey: null,
  bestGroupReason: '仅一组参数，无需比较',
  metrics: [
    metrics(),
    metrics({ marketScope: 'ASIAN_HANDICAP', pickCount: 25 }),
    metrics({ marketScope: 'ASIAN_TOTAL_GOALS', pickCount: 21, roi: 0.156176 }),
  ],
};

const cases: BacktestCaseVo = {
  id: 71,
  runId: 6,
  groupKey: 'baseline',
  externalMatchId: '2042096',
  matchId: 75,
  lotteryMatchNo: '周二001',
  leagueName: '欧洲冠军联赛',
  decisionAt: '2026-10-20T15:00:00Z',
  kickoffTime: '2026-10-20T16:45:00Z',
  decisionStage: 'DECIDED',
  skipReason: null,
  frozenBookmakerCode: 'pinnacle',
  frozenHandicapLine: -0.75,
  frozenHomeOdds: 1.88,
  frozenAwayOdds: 2.03,
  frozenTotalLine: 3,
  frozenOverOdds: 2.03,
  frozenUnderOdds: 1.86,
  frozenCapturedAt: '2026-10-19T03:33:02Z',
  handicapPick: 'HOME_COVER',
  handicapConfidence: 'MEDIUM',
  totalsPick: null,
  totalsConfidence: null,
  handicapDispersion: 0.002625,
  totalsDispersion: 0.005946,
  lambdaHome: 1.961293,
  lambdaAway: 1.109696,
  modelCoverProb: 0.514986,
  marketCoverProb: 0.514986,
  edgeCover: 0,
  modelOverProb: 0.475,
  marketOverProb: 0.475,
  edgeOver: 0,
  neighborMaxDeviation: 0.035683,
  maxResidual: 1.1102230246251565e-16,
  handicapGatePass: false,
  totalsGatePass: null,
  resultStatus: 'FINAL',
  homeScore: 2,
  awayScore: 2,
  handicapStatus: 'MISS',
  handicapProfit: -1,
  totalsStatus: null,
  totalsProfit: null,
  settledAt: '2026-10-27T12:30:11Z',
};

type Payloads = {
  run: BacktestRunVo;
  compare: BacktestComparisonVo;
  cases: { records: BacktestCaseVo[]; pageNo: number; pageSize: number; total: number };
};

function stubApi(payloads: Payloads) {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    const data = url.includes('/compare') ? payloads.compare
      : url.includes('/cases') ? payloads.cases
        : payloads.run;
    return {
      ok: true,
      status: 200,
      headers: new Headers({ 'X-Trace-Id': 'detail-test' }),
      text: async () => JSON.stringify({
        code: 'SUCCESS', message: '操作成功', data, traceId: 'detail-test',
      }),
    } as unknown as Response;
  }));
}

function renderDetail() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/admin/backtest/6']}>
        <Routes>
          <Route path="/admin/backtest/:runId" element={<BacktestRunDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('BacktestRunDetailPage', () => {
  beforeEach(() => {
    stubApi({
      run: run(),
      compare: comparison,
      cases: { records: [cases], pageNo: 1, pageSize: 20, total: 1 },
    });
  });

  it('renders the whole page when the metrics jsonb columns come back null', async () => {
    // 回归：`skip_reason_counts` 曾整列读回 null（后端用 @Select 绕过了 jsonb typeHandler），
    // 于是 `Object.keys(null)` 抛 TypeError，被错误边界接住 —— 用户看到的是「页面报错」，
    // 而真正坏掉的只是一列附注。这里用 null 的 skipReasonCounts 钉住它。
    renderDetail();

    await waitFor(() => {
      expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('十月全月');
    });
    expect(screen.getByRole('heading', { name: '参数组指标' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '逐场明细' })).toBeInTheDocument();
    // 没有分布就不显示分布，而不是渲染一行「跳过分布：」后面什么都不跟。
    expect(screen.queryByText(/跳过分布/)).not.toBeInTheDocument();
  });

  it('prints the skip distribution when the column is present', async () => {
    stubApi({
      run: run(),
      compare: {
        ...comparison,
        metrics: [
          metrics({ skipReasonCounts: { LOW_CONFIDENCE_ASIAN_MARKETS: 40, MARKET_DISPERSION_TOO_HIGH: 8 } }),
          metrics({ marketScope: 'ASIAN_HANDICAP', skipReasonCounts: null }),
          metrics({ marketScope: 'ASIAN_TOTAL_GOALS', skipReasonCounts: null }),
        ],
      },
      cases: { records: [cases], pageNo: 1, pageSize: 20, total: 1 },
    });
    renderDetail();

    await waitFor(() => {
      expect(screen.getAllByText(/跳过分布/).length).toBeGreaterThan(0);
    });
    expect(screen.getByText(/两市场都没方向 40/)).toBeInTheDocument();
    expect(screen.getByText(/跨庄分歧过大 8/)).toBeInTheDocument();
  });

  it('keeps a case with one leg settled and the other empty readable', async () => {
    renderDetail();

    await waitFor(() => {
      expect(screen.getAllByText('周二001').length).toBeGreaterThan(0);
    });
    // 让球有方向、大小球没方向：收益只累加让球那条腿，不能因为另一半是 null 就显示 0。
    expect(screen.getAllByText(/-1\.000/).length).toBeGreaterThan(0);
    expect(screen.getByText('主 -0.75')).toBeInTheDocument();
    // λ 是期望进球数，不是概率。按概率渲染会变成「196.1% / 111.0%」——数字没变，量纲全错。
    expect(screen.getByText('1.96 / 1.11')).toBeInTheDocument();
  });
});
