import { QueryClient } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { clearAdminSession, getAdminSession, setAdminSession } from '../services/adminSession';
import type { HomeSummaryVo, MatchDetailVo, MatchListItemVo, PageResult, PredictionDetailVo } from '../services/public';
import App from './App';
import AppProviders from './AppProviders';

const match: MatchListItemVo = {
  matchId: 42,
  lotteryDate: '2026-07-22',
  lotteryMatchNo: '周三042',
  leagueId: 7,
  leagueName: '英超',
  homeTeamName: '曼彻斯特城',
  awayTeamName: '阿森纳',
  kickoffTime: '2026-07-22T19:30:00+08:00',
  matchStatus: 'SCHEDULED',
  officialHandicap: -1,
  sportteryAvailability: 'AVAILABLE',
  sportteryDataSource: 'CHINA_SPORTTERY',
  sportteryCapturedAt: '2026-07-22T10:00:00+08:00',
  sportteryProviderUpdatedAt: null,
};

const detail: MatchDetailVo = {
  ...match,
  homeScore: null,
  awayScore: null,
  sportteryMarket: {
    availability: 'AVAILABLE',
    dataSource: 'CHINA_SPORTTERY',
    capturedAt: '2026-07-22T10:00:00+08:00',
    providerUpdatedAt: null,
    officialHandicap: -1,
    hadHomeSp: 1.8,
    hadDrawSp: 3.4,
    hadAwaySp: 4.2,
    hhadHomeSp: 2.4,
    hhadDrawSp: 3.2,
    hhadAwaySp: 2.7,
    sellStatus: 'ON_SALE',
  },
  asianOddsAvailability: 'AVAILABLE',
  asianOddsMarkets: [{
    providerCode: 'ODDS_API',
    bookmakerCode: 'BOOK_A',
    handicapLine: -0.5,
    homeOdds: 1.92,
    awayOdds: 1.96,
    totalLine: 2.5,
    overOdds: 1.88,
    underOdds: 2.02,
    snapshotType: 'PRE_KICKOFF',
    capturedAt: '2026-07-22T10:01:00+08:00',
    providerUpdatedAt: '2026-07-22T10:00:00+08:00',
  }],
  mappingAvailability: 'AVAILABLE',
  sourceMappings: [{
    providerCode: 'ODDS_API',
    externalMatchId: 'external-42',
    mappingStatus: 'AUTO_CONFIRMED',
    mappingConfidence: 0.98,
    mappingMethod: 'TEAM_AND_TIME',
    mappingExplanation: '球队和开赛时间一致。',
    mappingUpdatedAt: '2026-07-22T09:00:00+08:00',
  }],
};

const predictionDetail: PredictionDetailVo = {
  matchId: 42,
  modelPredictions: [{
    modelVersion: 'model-v1',
    currentPrediction: {
      predictionId: 102,
      predictionVersion: 2,
      replacesPredictionId: 101,
      predictionStatus: 'LOCKED',
      featureVersion: 'feature-v2',
      homeWinProb: 0.45,
      drawProb: 0.3,
      awayWinProb: 0.25,
      handicapPick: 'HOME_WIN',
      expectedTotalGoals: 2.5,
      confidenceLevel: 'HIGH',
      analysisSummary: '主队近期状态稳定。',
      generatedAt: '2026-07-22T08:00:00+08:00',
      publishTime: '2026-07-22T08:01:00+08:00',
      lockTime: '2026-07-22T12:00:00+08:00',
      predictionHash: 'a'.repeat(64),
      snapshotAvailability: 'AVAILABLE',
      snapshot: {
        snapshotId: 501,
        snapshotDate: '2026-07-22',
        snapshotVersion: 2,
        snapshotHash: 'b'.repeat(64),
        contentType: 'application/json',
        contentLength: 99,
        publishedAt: '2026-07-22T09:00:00+08:00',
      },
    },
    historicalPredictions: [{
      predictionId: 101,
      predictionVersion: 1,
      replacesPredictionId: null,
      predictionStatus: 'PUBLISHED',
      featureVersion: 'feature-v1',
      homeWinProb: 0.4,
      drawProb: 0.32,
      awayWinProb: 0.28,
      handicapPick: 'DRAW',
      expectedTotalGoals: 2.4,
      confidenceLevel: 'MEDIUM',
      analysisSummary: '历史公开摘要。',
      generatedAt: '2026-07-21T08:00:00+08:00',
      publishTime: '2026-07-21T08:01:00+08:00',
      lockTime: '2026-07-22T12:00:00+08:00',
      predictionHash: 'c'.repeat(64),
      snapshotAvailability: 'UNAVAILABLE',
      snapshot: null,
    }],
  }],
};

const homeSummary: HomeSummaryVo = {
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
      probabilityMetrics: { sampleSize: 5, brierScore: 0.19, logLoss: 0.72, unavailableReasons: [] },
      had: { marketType: 'HAD', settledSampleSize: 5, hitCount: 3, missCount: 2, pendingCount: 2, voidCount: 1, hitRate: 0.6 },
      hhad: { marketType: 'HHAD', settledSampleSize: 5, hitCount: 2, missCount: 3, pendingCount: 2, voidCount: 1, hitRate: 0.4 },
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
      probabilityMetrics: { sampleSize: 30, brierScore: 0.2, logLoss: 0.75, unavailableReasons: [] },
      had: { marketType: 'HAD', settledSampleSize: 30, hitCount: 17, missCount: 13, pendingCount: 8, voidCount: 4, hitRate: 0.57 },
      hhad: { marketType: 'HHAD', settledSampleSize: 30, hitCount: 15, missCount: 15, pendingCount: 8, voidCount: 4, hitRate: 0.5 },
      roi: { available: false, roi: null, yield: null, sampleSize: 0, unavailableReasons: ['MISSING_FIXED_BETTING_RULE'] },
    },
  },
  dataFreshness: { sportteryLastCapturedAt: '2026-07-28T00:45:00Z', sportteryDataAgeSeconds: 900 },
  latestPublishedSnapshotAt: '2026-07-28T00:30:00Z',
  generatedAt: '2026-07-28T01:00:00Z',
};

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

function page(records: MatchListItemVo[] = [match], pageNo = 1, total = records.length): PageResult<MatchListItemVo> {
  return { records, pageNo, pageSize: 20, total };
}

function mockPublicMatches(options: {
  home?: HomeSummaryVo;
  list?: PageResult<MatchListItemVo>;
  detail?: MatchDetailVo;
  prediction?: PredictionDetailVo;
  predictionError?: Parameters<typeof apiResponse>[1];
  error?: Parameters<typeof apiResponse>[1];
} = {}) {
  vi.mocked(fetch).mockImplementation(async (input) => {
    if (options.error) {
      return apiResponse(null, options.error);
    }
    const url = String(input);
    if (url.endsWith('/api/public/home/summary')) {
      return apiResponse(options.home ?? homeSummary);
    }
    if (url.endsWith('/api/public/predictions/detail')) {
      return options.predictionError
        ? apiResponse(null, options.predictionError)
        : apiResponse(options.prediction ?? predictionDetail);
    }
    if (url.endsWith('/api/public/predictions/snapshots/501/verify')) {
      return apiResponse({ snapshotId: 501, snapshotHash: 'b'.repeat(64), contentLength: 99, verified: true });
    }
    if (url.endsWith('/api/public/matches/detail')) {
      return apiResponse(options.detail ?? detail);
    }
    if (url.endsWith('/api/public/matches/list')) {
      return apiResponse(options.list ?? page());
    }
    return apiResponse(null);
  });
}

function renderApp(path = '/') {
  window.history.pushState({}, '', path);
  return render(
    <AppProviders queryClient={createTestQueryClient()}>
      <App />
    </AppProviders>,
  );
}

function publicListBodies() {
  return vi.mocked(fetch).mock.calls
    .filter(([url]) => String(url).endsWith('/api/public/matches/list'))
    .map(([, init]) => JSON.parse(String(init?.body)) as Record<string, unknown>);
}

describe('App routes', () => {
  beforeEach(() => {
    clearAdminSession();
    vi.stubGlobal('fetch', vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('lazy loads the fact-driven public home from the root route', async () => {
    mockPublicMatches();
    renderApp();

    expect(await screen.findByRole('heading', { name: '公开事实，持续检验每一场预测' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '竞彩罗盘' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '首页' })).toHaveAttribute('href', '/');
    expect(await screen.findByRole('link', { name: '查看每日比赛' })).toHaveAttribute('href', '/matches');
    expect(window.location.pathname).toBe('/');
    expect(vi.mocked(fetch)).toHaveBeenCalledWith('/api/public/home/summary', expect.objectContaining({ method: 'GET' }));
  });

  it('lazy loads public history and statistics routes from the shared navigation', async () => {
    mockPublicMatches();
    const historyView = renderApp('/history');

    expect(await screen.findByRole('heading', { name: '公开预测历史' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '预测历史' })).toHaveAttribute('href', '/history');
    expect(screen.getByRole('link', { name: '表现统计' })).toHaveAttribute('href', '/statistics');

    historyView.unmount();
    renderApp('/statistics');

    expect(await screen.findByRole('heading', { name: '预测表现统计' })).toBeInTheDocument();
  });

  it('persists filters in the URL and resets pagination before requesting the filtered page', async () => {
    const user = userEvent.setup();
    mockPublicMatches({ list: page([match], 2, 41) });
    renderApp('/matches?date=2026-07-22&leagueId=7&statuses=FINISHED&sort=KICKOFF_DESC&page=2');

    expect(await screen.findByText('第 2 / 3 页')).toBeInTheDocument();
    expect(screen.getByLabelText('联赛')).toHaveValue('7');
    expect(screen.getByLabelText('排序')).toHaveValue('KICKOFF_DESC');

    await user.click(screen.getByRole('checkbox', { name: '未开赛' }));

    await waitFor(() => expect(window.location.search).toContain('statuses=SCHEDULED%2CFINISHED'));
    expect(window.location.search).toContain('page=1');
    await waitFor(() => expect(publicListBodies()).toEqual(expect.arrayContaining([
      expect.objectContaining({
        lotteryDate: '2026-07-22',
        leagueId: 7,
        matchStatuses: ['SCHEDULED', 'FINISHED'],
        sort: 'KICKOFF_DESC',
        pageNo: 1,
        pageSize: 20,
      }),
    ])));
  });

  it('resets the page when the date changes and clears the league filter', async () => {
    mockPublicMatches({ list: page([match], 2, 41) });
    renderApp('/matches?date=2026-07-22&leagueId=7&page=2');

    await screen.findByText('第 2 / 3 页');
    fireEvent.change(screen.getByLabelText('竞彩日期'), { target: { value: '2026-07-23' } });

    await waitFor(() => expect(window.location.search).toContain('date=2026-07-23'));
    expect(window.location.search).toContain('page=1');
    expect(window.location.search).not.toContain('leagueId');
  });

  it('moves to the next server page without changing the active filters', async () => {
    const user = userEvent.setup();
    mockPublicMatches({ list: page([match], 1, 41) });
    renderApp('/matches?date=2026-07-22&leagueId=7&sort=KICKOFF_DESC&page=1');

    await screen.findByText('第 1 / 3 页');
    await user.click(screen.getByRole('button', { name: '下一页' }));

    await waitFor(() => expect(window.location.search).toContain('page=2'));
    await waitFor(() => expect(publicListBodies()).toEqual(expect.arrayContaining([
      expect.objectContaining({
        lotteryDate: '2026-07-22',
        leagueId: 7,
        sort: 'KICKOFF_DESC',
        pageNo: 2,
        pageSize: 20,
      }),
    ])));
  });

  it('renders public detail in separate Sporttery and Asian Odds sections and preserves the list URL', async () => {
    mockPublicMatches();
    renderApp('/matches/42?date=2026-07-22&leagueId=7&sort=KICKOFF_DESC&page=2');

    expect(await screen.findByRole('heading', { name: '体彩市场' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '亚洲盘快照' })).toBeInTheDocument();
    expect(screen.getByText('体彩官方让球')).toBeInTheDocument();
    expect(screen.getByText('亚洲让球线')).toBeInTheDocument();
    expect(screen.getByText('胜平负 SP（HAD）')).toBeInTheDocument();
    expect(screen.getByText('让球胜平负 SP（HHAD）')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '返回比赛列表' })).toHaveAttribute(
      'href',
      '/matches?date=2026-07-22&leagueId=7&sort=KICKOFF_DESC&page=2',
    );
    expect(vi.mocked(fetch)).toHaveBeenCalledWith('/api/public/matches/detail', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ matchId: 42 }),
    }));
  });

  it('renders model transparency, version history and controlled snapshot verification', async () => {
    const user = userEvent.setup();
    mockPublicMatches();
    renderApp('/matches/42');

    expect(await screen.findByRole('heading', { name: '模型分析与透明信息' })).toBeInTheDocument();
    expect(screen.getByText('model-v1')).toBeInTheDocument();
    expect(screen.getByText('主队近期状态稳定。')).toBeInTheDocument();
    expect(screen.getByText('快照可校验')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '下载快照' })).toHaveAttribute(
      'href',
      '/api/public/predictions/snapshots/501/download',
    );
    expect(screen.getByText('查看 1 个历史公开版本')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '校验快照' }));

    expect(await screen.findByText('当前对象与记录的哈希和长度一致。')).toBeInTheDocument();
    expect(vi.mocked(fetch)).toHaveBeenCalledWith(
      '/api/public/predictions/snapshots/501/verify',
      expect.objectContaining({ method: 'POST' }),
    );
  });

  it('keeps the base match detail readable when predictions are empty or fail', async () => {
    mockPublicMatches({ prediction: { matchId: 42, modelPredictions: [] } });
    renderApp('/matches/42');

    expect(await screen.findByText('当前没有可公开展示的模型预测。')).toBeInTheDocument();
  });

  it('shows a traceable prediction error without hiding the base match detail', async () => {
    mockPublicMatches({ predictionError: {
      code: 'DATA_SOURCE_UNAVAILABLE',
      message: '预测查询暂不可用',
      status: 503,
      traceId: 'prediction-trace-503',
    } });
    renderApp('/matches/42');

    expect(await screen.findByRole('heading', { name: '体彩市场' })).toBeInTheDocument();
    expect(await screen.findByText('公开预测暂不可用：预测查询暂不可用（追踪号：prediction-trace-503）')).toBeInTheDocument();
  });

  it('shows a stable 404 without requesting an invalid match ID', async () => {
    renderApp('/matches/not-a-number');

    expect(await screen.findByRole('heading', { name: '页面不存在' })).toBeInTheDocument();
    expect(fetch).not.toHaveBeenCalled();
  });

  it('shows the server message and trace ID when the detail does not exist', async () => {
    mockPublicMatches({ error: {
      code: 'MATCH_NOT_FOUND',
      message: '比赛不存在',
      status: 404,
      traceId: 'trace-match-404',
    } });
    renderApp('/matches/404');

    expect(await screen.findByRole('heading', { name: '比赛不存在' })).toBeInTheDocument();
    expect(screen.getByText('比赛不存在（追踪号：trace-match-404）')).toBeInTheDocument();
  });

  it('explains missing markets and mappings and allows a detail refresh', async () => {
    const user = userEvent.setup();
    mockPublicMatches({ detail: {
      ...detail,
      sportteryMarket: { ...detail.sportteryMarket, availability: 'NO_SPORTTERY_SNAPSHOT' },
      asianOddsAvailability: 'NO_ASIAN_ODDS_SNAPSHOT',
      asianOddsMarkets: [],
      mappingAvailability: 'NO_SOURCE_MAPPING',
      sourceMappings: [],
    } });
    renderApp('/matches/42');

    expect(await screen.findByText('暂无体彩快照，暂不展示 SP 或官方让球。')).toBeInTheDocument();
    expect(screen.getByText('暂无亚盘快照。')).toBeInTheDocument();
    expect(screen.getByText('暂无来源映射。')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '刷新' }));

    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(4));
  });

  it('shows a traceable error for a failed public list query', async () => {
    mockPublicMatches({ error: {
      code: 'DATA_SOURCE_UNAVAILABLE',
      message: '外部数据源暂时不可用',
      status: 503,
      traceId: 'trace-error-001',
    } });
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
