import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { clearAdminSession, setAdminSession } from '../../services/adminSession';
import type { AdminDraftPredictionPage } from '../../services/admin';
import AdminDraftPredictionsPage from './AdminDraftPredictionsPage';

const first: AdminDraftPredictionPage['records'][number] = {
  predictionId: 101, modelVersion: 't306-odds-baseline-v1', featureVersion: 't306-sporttery-asian-v1',
  generationBatchId: 't306-baseline-2026-08-11-a', generationBatchHash: 'a'.repeat(64), predictionVersion: 1,
  homeWinProb: 0.46, drawProb: 0.28, awayWinProb: 0.26, handicapPick: 'HOME_WIN', expectedTotalGoals: 2.5,
  confidenceLevel: 'MEDIUM', analysisSummary: '主队盘口与体彩概率一致。', generatedAt: '2026-08-11T01:00:00Z',
  match: { matchId: 27, lotteryDate: '2026-08-11', lotteryMatchNo: '周一001', leagueName: '欧冠', homeTeamName: '主队', awayTeamName: '客队', kickoffTime: '2026-08-11T12:00:00Z' },
};
const second: AdminDraftPredictionPage['records'][number] = {
  ...first, predictionId: 102, match: { ...first.match, matchId: 28, lotteryMatchNo: '周一002', homeTeamName: '另一主队', awayTeamName: '另一客队' },
};
const page: AdminDraftPredictionPage = { records: [first, second], pageNo: 1, pageSize: 20, total: 2 };

function response(data: unknown, ok = true, message = '操作成功') {
  return {
    ok, status: ok ? 200 : 409, headers: new Headers(),
    text: async () => JSON.stringify({ code: ok ? 'SUCCESS' : 'COMMON_BUSINESS_ERROR', message, data, traceId: 'draft-page-trace' }),
  } as Response;
}

function LocationProbe() {
  return <output data-testid="location">{useLocation().search}</output>;
}

function renderPage(path = '/admin/predictions/drafts?date=2026-08-11') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } });
  return render(<MemoryRouter initialEntries={[path]}><Routes><Route path="/admin/predictions/drafts" element={<><AdminDraftPredictionsPage /><LocationProbe /></>} /></Routes></MemoryRouter>, {
    wrapper: ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>,
  });
}

describe('admin draft prediction page', () => {
  beforeEach(() => {
    setAdminSession({ accessToken: 'admin-jwt', tokenType: 'Bearer', expiresAt: '2099-01-01T00:00:00Z', adminId: 1, username: 'admin', role: 'ADMIN' });
    vi.stubGlobal('fetch', vi.fn());
  });
  afterEach(() => { clearAdminSession(); vi.unstubAllGlobals(); });

  it('loads draft-only data, keeps filters in the URL, and refreshes after confirmed publication', async () => {
    const user = userEvent.setup();
    vi.mocked(fetch).mockImplementation(async (input) => {
      const url = String(input);
      if (url.endsWith('/api/admin/predictions/drafts/list')) return response(page);
      if (url.endsWith('/api/admin/predictions/publish')) return response({
        predictionId: 101, matchId: 27, modelVersion: first.modelVersion, predictionVersion: 1,
        predictionStatus: 'PUBLISHED', publishTime: '2026-08-11T01:10:00Z', lockTime: '2026-08-11T11:30:00Z', predictionHash: 'b'.repeat(64), alreadyPublished: false,
      });
      return response(null);
    });
    renderPage();

    expect(await screen.findByText(/周一001.*主队.*vs.*客队/)).toBeInTheDocument();
    expect(screen.getByText(/草稿仅在此后台可见/)).toBeInTheDocument();
    await user.type(screen.getByLabelText('草稿模型版本'), 'baseline');
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('modelVersion=baseline'));
    await user.clear(screen.getByLabelText('草稿模型版本'));
    await user.click(screen.getByRole('checkbox', { name: '选择 周一001 · 主队 vs 客队' }));
    await user.click(screen.getByRole('button', { name: '发布所选' }));
    expect(await screen.findByText(/将发布.*1.*条已选草稿/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '确认逐条发布' }));

    expect(await screen.findByText('发布成功，当前为第 1 版。')).toBeInTheDocument();
    expect(fetch).toHaveBeenCalledWith('/api/admin/predictions/publish', expect.objectContaining({
      method: 'POST', body: JSON.stringify({ predictionId: 101 }),
    }));
    await waitFor(() => expect(vi.mocked(fetch).mock.calls.filter(([url]) => String(url).endsWith('/api/admin/predictions/drafts/list')).length).toBeGreaterThan(1));
  });

  it('keeps per-item failures visible after a partial publication', async () => {
    const user = userEvent.setup();
    vi.mocked(fetch).mockImplementation(async (input, init) => {
      const url = String(input);
      if (url.endsWith('/api/admin/predictions/drafts/list')) return response(page);
      if (url.endsWith('/api/admin/predictions/publish')) {
        const request = JSON.parse(String(init?.body)) as { predictionId: number };
        return request.predictionId === 101
          ? response({ predictionId: 101, matchId: 27, modelVersion: first.modelVersion, predictionVersion: 1, predictionStatus: 'PUBLISHED', publishTime: '2026-08-11T01:10:00Z', lockTime: '2026-08-11T11:30:00Z', predictionHash: 'b'.repeat(64), alreadyPublished: false })
          : response(null, false, '比赛已开赛，无法发布');
      }
      return response(null);
    });
    renderPage();

    await screen.findByText(/周一002.*另一主队.*vs.*另一客队/);
    await user.click(screen.getByRole('checkbox', { name: '全选当前页' }));
    await user.click(screen.getByRole('button', { name: '发布所选' }));
    await user.click(await screen.findByRole('button', { name: '确认逐条发布' }));

    expect(await screen.findByText('成功 1 条 · 失败 1 条')).toBeInTheDocument();
    expect(screen.getByText(/比赛已开赛，无法发布（追踪号：draft-page-trace）/)).toBeInTheDocument();
  });
});
