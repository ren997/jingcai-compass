import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { clearAdminSession, setAdminSession } from '../../services/adminSession';
import AdminManualResultsPage from './AdminManualResultsPage';

function response(data: unknown) {
  return { ok: true, status: 200, headers: new Headers(), text: async () => JSON.stringify({
    code: 'SUCCESS', message: '操作成功', data, traceId: 'manual-result-page-test',
  }) } as Response;
}

describe('AdminManualResultsPage', () => {
  beforeEach(() => {
    setAdminSession({ accessToken: 'admin-jwt', tokenType: 'Bearer', expiresAt: '2099-01-01T00:00:00Z', adminId: 1, username: 'admin', role: 'ADMIN' });
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({
      records: [{
        matchId: 7, lotteryDate: '2026-08-09', lotteryMatchNo: '周日007', leagueId: 1, leagueName: '测试联赛',
        homeTeamName: '主队', awayTeamName: '客队', kickoffTime: '2020-01-01T12:00:00Z', matchStatus: 'FINISHED',
        officialHandicap: null, sportteryAvailability: 'AVAILABLE', sportteryDataSource: 'STUB', sportteryCapturedAt: null, sportteryProviderUpdatedAt: null,
      }], pageNo: 1, pageSize: 100, total: 1,
    })));
  });

  afterEach(() => { clearAdminSession(); vi.unstubAllGlobals(); });

  it('shows the non-official warning and requires a confirmation modal after a complete entry', async () => {
    const user = userEvent.setup();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<MemoryRouter><AdminManualResultsPage /></MemoryRouter>, {
      wrapper: ({ children }) => <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>,
    });

    expect(await screen.findByText('人工补录，非官方源')).toBeInTheDocument();
    await user.click(await screen.findByRole('button', { name: /周日007 · 主队 vs 客队/ }));
    await user.type(screen.getByLabelText('主队比分'), '2');
    await user.type(screen.getByLabelText('客队比分'), '1');
    await user.type(screen.getByLabelText('来源说明'), '已核实记录');
    await user.type(screen.getByLabelText('补录原因'), '开发验证');
    await user.click(screen.getByRole('button', { name: '提交人工赛果' }));

    expect(screen.getByText('确认补录人工赛果')).toBeInTheDocument();
    expect(screen.getByText(/不能直接修改结算结果/)).toBeInTheDocument();
  });
});
