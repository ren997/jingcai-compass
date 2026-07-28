import { QueryClient } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { clearAdminSession, getAdminSession, setAdminSession } from '../services/adminSession';
import type { MatchDetailVo, MatchListItemVo, PageResult } from '../services/public';
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
  list?: PageResult<MatchListItemVo>;
  detail?: MatchDetailVo;
  error?: Parameters<typeof apiResponse>[1];
} = {}) {
  vi.mocked(fetch).mockImplementation(async (input) => {
    if (options.error) {
      return apiResponse(null, options.error);
    }
    const url = String(input);
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

  it('redirects the root route and loads the paged public list with POST', async () => {
    mockPublicMatches();
    renderApp();

    expect(await screen.findByRole('heading', { name: '今日竞彩比赛' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '竞彩罗盘' })).toBeInTheDocument();
    expect(await screen.findByText('曼彻斯特城')).toBeInTheDocument();
    expect(screen.getByText('曼彻斯特城').closest('a')).toHaveAttribute('href', expect.stringMatching(/^\/matches\/42/));
    expect(window.location.pathname).toBe('/matches');
    expect(publicListBodies()).toEqual(expect.arrayContaining([
      expect.objectContaining({ pageNo: 1, pageSize: 20, sort: 'KICKOFF_ASC' }),
      expect.objectContaining({ pageNo: 1, pageSize: 100, sort: 'KICKOFF_ASC' }),
    ]));
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

    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(2));
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
