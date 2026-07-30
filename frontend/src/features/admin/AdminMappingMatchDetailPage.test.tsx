import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import AdminMappingMatchDetailPage from './AdminMappingMatchDetailPage';
import { useMappingReviewActions, useMappingReviewMatchDetailQuery } from './useAdminQueries';

vi.mock('./useAdminQueries', () => ({
  useMappingReviewActions: vi.fn(),
  useMappingReviewMatchDetailQuery: vi.fn(),
}));

const confirmMutation = vi.fn().mockResolvedValue(undefined);
const confirmBundleMutation = vi.fn().mockResolvedValue(undefined);
const refetch = vi.fn().mockResolvedValue(undefined);

describe('AdminMappingMatchDetailPage', () => {
  beforeEach(() => {
    confirmMutation.mockClear();
    confirmBundleMutation.mockClear();
    refetch.mockClear();
    vi.mocked(useMappingReviewMatchDetailQuery).mockReturnValue({
      data: {
        match: {
          matchId: 42, lotteryMatchNo: '周四006', lotteryDate: '2099-07-31', leagueName: '巴甲',
          homeTeamName: '科林蒂安', awayTeamName: '巴竞技', kickoffTime: '2099-07-31T06:30:00+08:00',
        },
        externalCandidates: [{
          mappingId: 12, providerCode: 'THE_ODDS_API', externalMatchId: 'event-12',
          externalHomeTeamName: 'Corinthians', externalAwayTeamName: 'Atletico Paranaense',
          externalKickoffTime: '2099-07-31T06:30:00+08:00', mappingStatus: 'PENDING',
          score: 0.15, reasons: ['TIME_LE_15'], mappingExplanation: 'TIME_LE_15',
        }],
        normalizationProposals: [{
          sourceMappingId: 12, role: 'LEAGUE', providerMappingId: 101,
          externalDisplayName: 'Brazil Serie A', targetEntityId: 7, targetEntityName: '巴甲',
          mappingStatus: 'PENDING', selectable: true, unavailableReason: null,
        }, {
          sourceMappingId: 12, role: 'HOME_TEAM', providerMappingId: 102,
          externalDisplayName: 'Corinthians', targetEntityId: 8, targetEntityName: '科林蒂安',
          mappingStatus: 'PENDING', selectable: true, unavailableReason: null,
        }, {
          sourceMappingId: 12, role: 'AWAY_TEAM', providerMappingId: 103,
          externalDisplayName: 'Atletico Paranaense', targetEntityId: 9, targetEntityName: '巴竞技',
          mappingStatus: 'PENDING', selectable: true, unavailableReason: null,
        }],
      },
      isPending: false, isError: false, isFetching: false, isStale: false, refetch,
    } as never);
    vi.mocked(useMappingReviewActions).mockReturnValue({
      confirm: { mutateAsync: confirmMutation, isPending: false, error: null },
      confirmBundle: { mutateAsync: confirmBundleMutation, isPending: false, error: null },
    } as never);
  });

  it('confirms the selected external event from the dialog button without a typed phrase', async () => {
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/admin/mappings/matches/42?reviewScope=ACTIVE']}><Routes>
      <Route path="/admin/mappings/matches/:matchId" element={<AdminMappingMatchDetailPage />} />
    </Routes></MemoryRouter>);

    await user.click(screen.getByRole('button', { name: '确认关联' }));

    const dialog = screen.getByRole('dialog');
    expect(within(dialog).queryByText(/输入“确认关联”/)).not.toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: '确认关联' }));

    expect(confirmBundleMutation).toHaveBeenCalledWith({
      mappingId: 12, targetMatchId: 42, confirmLeague: false, confirmHomeTeam: false, confirmAwayTeam: false,
    });
    expect(refetch).toHaveBeenCalledOnce();
  });

  it('submits only explicitly selected normalization confirmations with the event', async () => {
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/admin/mappings/matches/42?reviewScope=ACTIVE']}><Routes>
      <Route path="/admin/mappings/matches/:matchId" element={<AdminMappingMatchDetailPage />} />
    </Routes></MemoryRouter>);

    await user.click(screen.getByRole('button', { name: '确认关联' }));
    const dialog = screen.getByRole('dialog');
    await user.click(within(dialog).getByText(/同时确认联赛/));
    await user.click(within(dialog).getByText(/同时确认主队/));
    await user.click(within(dialog).getByRole('button', { name: '确认关联' }));

    expect(confirmBundleMutation).toHaveBeenCalledWith({
      mappingId: 12, targetMatchId: 42, confirmLeague: true, confirmHomeTeam: true, confirmAwayTeam: false,
    });
  });
});
