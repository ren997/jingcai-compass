import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { BacktestEstimateVo } from '../../services/backtest';
import BacktestCreateForm from './BacktestCreateForm';

function apiResponse(data: unknown) {
  return {
    ok: true,
    status: 200,
    headers: new Headers({ 'X-Trace-Id': 'backtest-create-test' }),
    text: async () => JSON.stringify({ code: 'SUCCESS', message: '操作成功', data, traceId: 'backtest-create-test' }),
  } as unknown as Response;
}

const estimate: BacktestEstimateVo = {
  dateFrom: '2026-06-20',
  dateTo: '2026-07-01',
  matchCount: 42,
  reusableArchiveCount: 30,
  estimatedQuotaCost: 4200,
  estimatedSeconds: 900,
  budgetConfirmRequired: true,
  parameterGroupCount: 1,
  warnings: [],
};

function renderForm(overrides: Partial<React.ComponentProps<typeof BacktestCreateForm>> = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } });
  const onEstimate = vi.fn();
  const onCreate = vi.fn();
  const props = {
    estimate: undefined as BacktestEstimateVo | undefined,
    estimating: false,
    creating: false,
    ...overrides,
    onEstimate: overrides.onEstimate ?? onEstimate,
    onCreate: overrides.onCreate ?? onCreate,
  };
  render(
    <QueryClientProvider client={queryClient}>
      <BacktestCreateForm {...props} />
    </QueryClientProvider>,
  );
  return { onEstimate, onCreate };
}

describe('BacktestCreateForm', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(apiResponse([])));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('refuses to create until the estimated budget is explicitly confirmed', async () => {
    const user = userEvent.setup();
    renderForm({ estimate });

    await user.type(screen.getByLabelText('回测名称'), '六月复盘');
    // 预估说这次要花 4200 次额度，未勾选前创建必须关着。
    expect(screen.getByRole('button', { name: '创建并入队' })).toBeDisabled();
    expect(screen.getByText(/我确认消耗约 4200 次接口额度/)).toBeInTheDocument();

    await user.click(screen.getByRole('checkbox'));
    expect(screen.getByRole('button', { name: '创建并入队' })).toBeEnabled();
  });

  it('keeps create disabled when the estimate does not require a budget confirmation', async () => {
    const user = userEvent.setup();
    renderForm({ estimate: { ...estimate, budgetConfirmRequired: false, estimatedQuotaCost: 30 } });

    await user.type(screen.getByLabelText('回测名称'), '小额回测');
    expect(screen.getByRole('button', { name: '创建并入队' })).toBeEnabled();
    expect(screen.getByText(/不需要额外确认/)).toBeInTheDocument();
  });

  it('blocks dates earlier than the odds-history horizon and explains why', async () => {
    const user = userEvent.setup();
    renderForm();

    fireEvent.change(screen.getByLabelText('开始日期'), { target: { value: '2026-05-01' } });
    expect(screen.getByText(/历史赔率接口仅回溯约 4 个月/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '预估成本' })).toBeDisabled();

    // 结束日期要一起收窄：只把起点移回可回溯起点、终点留在今天，跨度仍然超限。
    fireEvent.change(screen.getByLabelText('开始日期'), { target: { value: '2026-06-20' } });
    fireEvent.change(screen.getByLabelText('结束日期'), { target: { value: '2026-07-19' } });
    await user.type(screen.getByLabelText('回测名称'), '合法区间');
    expect(screen.queryByText(/历史赔率接口仅回溯约 4 个月/)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '预估成本' })).toBeEnabled();
  });

  it('rejects a span longer than the configured limit', () => {
    renderForm();
    // 默认起点是「今天往回 30 天」，所以要把终点推到远超 120 天才触发跨度上限。
    fireEvent.change(screen.getByLabelText('结束日期'), { target: { value: '2027-06-01' } });
    expect(screen.getByText(/超过上限 120 天/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '预估成本' })).toBeDisabled();
  });

  it('treats a duplicated group key as an error, not as a second row', async () => {
    const user = userEvent.setup();
    renderForm();

    await user.click(screen.getByRole('button', { name: '新增组' }));
    const secondKey = screen.getByLabelText('第 2 组标识');
    await user.clear(secondKey);
    await user.type(secondKey, 'baseline');

    expect(screen.getByText(/参数组标识重复/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '预估成本' })).toBeDisabled();
  });

  it('sends the parameter sets and the lead minutes it displays', async () => {
    const user = userEvent.setup();
    const { onEstimate } = renderForm();

    await user.type(screen.getByLabelText('回测名称'), '参数组回放');
    await user.click(screen.getByRole('button', { name: '预估成本' }));

    expect(onEstimate).toHaveBeenCalledTimes(1);
    const request = onEstimate.mock.calls[0][0];
    expect(request.decisionLeadMinutes).toBe(105);
    expect(request.parameterSets).toEqual([{
      groupKey: 'baseline',
      highConfidenceMargin: 0.1,
      mediumConfidenceMargin: 0.16,
      highDirectionSignal: 0.075,
      mediumDirectionSignal: 0.025,
    }]);
    // 预估阶段绝不自作主张确认预算：确认是人的动作。
    expect(request.confirmBudget).toBe(false);
  });
});
