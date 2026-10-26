import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { BacktestRunVo } from '../../services/backtest';
import BacktestProgressPanel from './BacktestProgressPanel';

function run(overrides: Partial<BacktestRunVo> = {}): BacktestRunVo {
  return {
    id: 9,
    runKey: 'bt-20260620-abcdef12',
    runName: '六月复盘',
    runStatus: 'EVALUATING',
    runStage: 'EVALUATING',
    operatorUsername: 'admin',
    dateFrom: '2026-06-20',
    dateTo: '2026-07-19',
    leagueNames: [],
    decisionLeadMinutes: 105,
    parameterSets: [{ groupKey: 'baseline' }],
    providerCode: 'NOVA_API',
    modelVersion: 'asian-v3',
    selectorVersion: 'freeze-selector-v2',
    aggregationVersion: 'consensus-devig-median-v1',
    builderVersion: 'case-builder-v1',
    gitSha: 'abcdef1234567890',
    totalMatchCount: 40,
    processedMatchCount: 10,
    archiveReusedCount: 8,
    archiveFetchedCount: 2,
    archiveFailedCount: 0,
    caseCount: 20,
    estimateQuotaCost: 40,
    quotaCost: 10,
    cancelRequested: false,
    resumeCount: 0,
    errorCode: null,
    errorMessage: null,
    createdAt: '2026-06-20T10:00:00Z',
    startedAt: '2026-06-20T10:01:00Z',
    finishedAt: null,
    lastHeartbeatAt: '2026-06-20T10:05:00Z',
    progressRatio: 0.25,
    ...overrides,
  };
}

function renderPanel(overrides: Partial<BacktestRunVo> = {}) {
  const onCancel = vi.fn();
  const onResume = vi.fn();
  render(
    <BacktestProgressPanel
      run={run(overrides)}
      cancelling={false}
      resuming={false}
      onCancel={onCancel}
      onResume={onResume}
    />,
  );
  return { onCancel, onResume };
}

describe('BacktestProgressPanel', () => {
  it('asks for confirmation before cancelling a running backtest', async () => {
    const user = userEvent.setup();
    const { onCancel } = renderPanel();

    // 取消只置标志、不停机，这一点必须先讲清楚再让人点。
    await user.click(screen.getByRole('button', { name: '请求取消' }));
    expect(screen.getByText(/只会置取消标志/)).toBeInTheDocument();
    expect(onCancel).not.toHaveBeenCalled();

    await user.click(screen.getByRole('button', { name: '确认取消' }));
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it('offers resume instead of cancel for an interrupted run', () => {
    renderPanel({ runStatus: 'INTERRUPTED', runStage: 'ARCHIVING', processedMatchCount: 12 });
    expect(screen.getByRole('button', { name: /续\s*跑/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '请求取消' })).not.toBeInTheDocument();
  });

  it('hides both actions once the run reached a terminal state', () => {
    renderPanel({ runStatus: 'SUCCEEDED', runStage: 'DONE', processedMatchCount: 40, progressRatio: 1 });
    expect(screen.queryByRole('button', { name: /续\s*跑/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '请求取消' })).not.toBeInTheDocument();
  });

  it('separates archive reuse from fresh fetches so quota cost is explainable', () => {
    renderPanel();
    expect(screen.getByText('归档复用')).toBeInTheDocument();
    expect(screen.getByText('归档新采')).toBeInTheDocument();
    expect(screen.getByText('归档失败')).toBeInTheDocument();
    // 额度按「已消耗 / 预估」并列，单看消耗无法判断是否超支。
    expect(screen.getByText('10 / 预估 40')).toBeInTheDocument();
  });

  it('surfaces the error code with the run instead of a generic failure', () => {
    renderPanel({ runStatus: 'FAILED', runStage: 'ARCHIVING', errorCode: 'UPSTREAM_ERROR', errorMessage: '上游 502' });
    expect(screen.getByText('UPSTREAM_ERROR：上游 502')).toBeInTheDocument();
  });

  it('says the samples are kept when a cancel was requested', () => {
    renderPanel({ cancelRequested: true });
    expect(screen.getByText(/已请求取消/)).toBeInTheDocument();
  });
});
