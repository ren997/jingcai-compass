import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import AdminNormalizationDetailPage from './AdminNormalizationDetailPage';
import {
  useProviderNormalizationActions,
  useProviderNormalizationCandidatesQuery,
  useProviderNormalizationDetailQuery,
} from './useAdminQueries';

vi.mock('./useAdminQueries', () => ({
  useProviderNormalizationActions: vi.fn(),
  useProviderNormalizationCandidatesQuery: vi.fn(),
  useProviderNormalizationDetailQuery: vi.fn(),
}));

const confirmMutation = vi.fn().mockResolvedValue(undefined);
const noopMutation = { mutateAsync: vi.fn(), isPending: false, error: null };

describe('AdminNormalizationDetailPage', () => {
  beforeEach(() => {
    confirmMutation.mockClear();
    vi.mocked(useProviderNormalizationDetailQuery).mockReturnValue({
      data: {
        mappingId: 3, entityType: 'LEAGUE', providerCode: 'THE_ODDS_API', externalId: 'soccer_brazil_campeonato',
        externalScope: null, externalDisplayName: 'soccer_brazil_campeonato', externalNormalizedKey: 'soccerbrazilcampeonato',
        mappingStatus: 'PENDING', mappingConfidence: null, mappingMethod: 'NAME_CANDIDATE',
        currentEntity: { entityId: 3, nameZh: 'soccer_brazil_campeonato', nameEn: null }, auditHistory: [], updatedAt: '2026-07-30T06:02:00Z',
      }, isPending: false, isError: false, isFetching: false, refetch: vi.fn(),
    } as never);
    vi.mocked(useProviderNormalizationCandidatesQuery).mockReturnValue({
      data: [{ entityId: 2, nameZh: '巴甲', nameEn: null }], isPending: false, isError: false,
    } as never);
    vi.mocked(useProviderNormalizationActions).mockReturnValue({
      confirm: { mutateAsync: confirmMutation, isPending: false, error: null }, reject: noopMutation, reopen: noopMutation,
    } as never);
  });

  it('confirms a selected normalization from the dialog button without a typed phrase', async () => {
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/admin/normalizations/leagues/3']}><Routes>
      <Route path="/admin/normalizations/leagues/:mappingId" element={<AdminNormalizationDetailPage entityType="LEAGUE" />} />
    </Routes></MemoryRouter>);

    await user.click(screen.getByRole('radio', { name: /巴甲/ }));
    await user.click(screen.getByRole('button', { name: '确认标准化' }));

    const dialog = screen.getByRole('dialog');
    expect(within(dialog).queryByText(/输入“确认标准化”/)).not.toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: '确认标准化' }));

    expect(confirmMutation).toHaveBeenCalledWith({ entityType: 'LEAGUE', mappingId: 3, targetEntityId: 2 });
  });
});
