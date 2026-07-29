import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { setAdminSession, clearAdminSession } from '../../services/adminSession';
import type { AdminPredictionStatusDetail, AdminPredictionStatusPage } from '../../services/admin';
import AdminPredictionLocksPage from './AdminPredictionLocksPage';
import AdminPredictionStatusDetailPage from './AdminPredictionStatusDetailPage';
import AdminSettlementStatusesPage from './AdminSettlementStatusesPage';

const item: AdminPredictionStatusPage['records'][number] = {
  predictionId: 7,
  modelVersion: 'model-v1',
  featureVersion: 'feature-v2',
  predictionVersion: 2,
  predictionStatus: 'LOCKED',
  publishTime: '2026-07-29T00:00:00Z',
  lockTime: '2026-07-29T01:00:00Z',
  predictionHash: 'a'.repeat(64),
  match: { matchId: 42, lotteryDate: '2026-07-29', lotteryMatchNo: '周三042', leagueName: '英超', homeTeamName: '主队', awayTeamName: '客队', kickoffTime: '2026-07-29T12:00:00Z' },
  lockDiagnostics: [{ code: 'LOCKED', description: '预测已锁定' }],
  currentResultFact: { factId: 102, factVersion: 2, supersedesFactVersion: 1, factStatus: 'FINAL', matchStatus: 'FINISHED', homeScore: 2, awayScore: 1, providerUpdatedAt: '2026-07-29T14:00:00Z', current: true, createdAt: '2026-07-29T14:01:00Z' },
  hadSettlement: { marketType: 'HAD', currentStatus: 'MISS', currentSettlementPersisted: true, settlementId: 302, settlementVersion: 2, matchFactId: 102, ruleVersion: 't403-v1', stale: false },
  hhadSettlement: { marketType: 'HHAD', currentStatus: 'PENDING', currentSettlementPersisted: false, settlementId: null, settlementVersion: null, matchFactId: null, ruleVersion: null, stale: false },
  settlementDiagnostics: [{ code: 'SETTLEMENT_MISSING_HHAD', description: 'HHAD 当前结算缺失' }],
};

const page: AdminPredictionStatusPage = { records: [item], pageNo: 2, pageSize: 20, total: 21, manualAttentionCount: 1 };
const detail: AdminPredictionStatusDetail = {
  prediction: item,
  resultFactHistory: [
    { ...item.currentResultFact!, factId: 101, factVersion: 1, supersedesFactVersion: null, homeScore: 1, awayScore: 1, current: false },
    item.currentResultFact!,
  ],
  settlementMarkets: [
    { marketType: 'HAD', currentStatus: 'MISS', currentSettlementPersisted: true, currentSettlementStale: false, versions: [
      { settlementId: 301, settlementVersion: 1, supersedesSettlementVersion: null, settlementStatus: 'HIT', matchFactId: 101, ruleVersion: 't403-v1', current: false, createdAt: '2026-07-29T13:00:00Z' },
      { settlementId: 302, settlementVersion: 2, supersedesSettlementVersion: 1, settlementStatus: 'MISS', matchFactId: 102, ruleVersion: 't403-v1', current: true, createdAt: '2026-07-29T14:01:00Z' },
    ] },
    { marketType: 'HHAD', currentStatus: 'PENDING', currentSettlementPersisted: false, currentSettlementStale: false, versions: [] },
  ],
};

function response(data: unknown) {
  return { ok: true, status: 200, headers: new Headers(), text: async () => JSON.stringify({ code: 'SUCCESS', message: '操作成功', data, traceId: 'operation-page-trace' }) } as Response;
}

function LocationProbe() {
  return <output data-testid="location">{useLocation().search}</output>;
}

function renderRoute(path: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } });
  return render(<MemoryRouter initialEntries={[path]}><Routes>
    <Route path="/admin/predictions" element={<><AdminPredictionLocksPage /><LocationProbe /></>} />
    <Route path="/admin/settlements" element={<><AdminSettlementStatusesPage /><LocationProbe /></>} />
    <Route path="/admin/settlements/:predictionId" element={<AdminPredictionStatusDetailPage />} />
  </Routes></MemoryRouter>, { wrapper: ({ children }) => <QueryClientProvider client={queryClient}>{children}</QueryClientProvider> });
}

describe('admin prediction status pages', () => {
  beforeEach(() => {
    setAdminSession({ accessToken: 'admin-jwt', tokenType: 'Bearer', expiresAt: '2099-01-01T00:00:00Z', adminId: 1, username: 'admin', role: 'ADMIN' });
    vi.stubGlobal('fetch', vi.fn());
  });
  afterEach(() => { clearAdminSession(); vi.unstubAllGlobals(); });

  it('shows lock state, preserves links, and resets pagination after filters change', async () => {
    const user = userEvent.setup();
    vi.mocked(fetch).mockResolvedValue(response(page));
    renderRoute('/admin/predictions?page=2');

    expect(await screen.findByText('主队 vs 客队')).toBeInTheDocument();
    expect(screen.getByText(/待人工处理 1 条/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /主队 vs 客队/ })).toHaveAttribute('href', '/admin/predictions/7?page=2');
    await user.click(screen.getByRole('checkbox', { name: '已锁定' }));
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('statuses=PUBLISHED'));
  });

  it('shows settlement diagnostics and the read-only current versus history chains', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(response(page)).mockResolvedValueOnce(response(detail));
    renderRoute('/admin/settlements');
    expect(await screen.findByText('HHAD 待结算')).toBeInTheDocument();
    expect(screen.getByText('PENDING（未落库）')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /主队 vs 客队/ })).toHaveAttribute('href', '/admin/settlements/7');

    renderRoute('/admin/settlements/7?diagnostics=SETTLEMENT_MISSING_HHAD');
    expect(await screen.findByText('官方赛果版本链')).toBeInTheDocument();
    expect(screen.getByText(/当前权威事实/)).toBeInTheDocument();
    expect(screen.getByText('市场结算版本链')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '返回结算状态' })).toHaveAttribute('href', '/admin/settlements?diagnostics=SETTLEMENT_MISSING_HHAD');
  });
});
