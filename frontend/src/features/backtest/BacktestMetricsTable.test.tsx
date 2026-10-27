import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type {
  BacktestComparisonVo,
  BacktestMetricsVo,
  BacktestParameterSet,
} from '../../services/backtest';
import BacktestMetricsTable from './BacktestMetricsTable';

const parameterSets: BacktestParameterSet[] = [
  {
    groupKey: 'baseline',
    highConfidenceMargin: 0.1,
    mediumConfidenceMargin: 0.16,
    highDirectionSignal: 0.075,
    mediumDirectionSignal: 0.025,
  },
  {
    groupKey: 'tight',
    highConfidenceMargin: 0.08,
    mediumConfidenceMargin: 0.12,
    highDirectionSignal: 0.09,
    mediumDirectionSignal: 0.04,
  },
];

function metrics(overrides: Partial<BacktestMetricsVo> = {}): BacktestMetricsVo {
  return {
    runId: 9,
    groupKey: 'baseline',
    marketScope: 'ALL',
    caseCount: 12,
    decidedCount: 8,
    skippedCount: 4,
    pickCount: 8,
    settledCount: 6,
    pushCount: 1,
    halfWinCount: 0,
    halfLossCount: 0,
    hitCount: 3,
    missCount: 2,
    hitRate: 0.6,
    weightedHitRate: 0.5833,
    profitSum: 1.234,
    roi: 0.0206,
    yieldRate: 0.0206,
    brierModel: 0.2025,
    brierMarket: 0.1875,
    brierDelta: -0.015,
    brierSampleSize: 4,
    skipReasonCounts: { INSUFFICIENT_ASIAN_BOOKMAKERS: 2 },
    metricsJson: null,
    computedAt: null,
    ...overrides,
  };
}

const comparison: BacktestComparisonVo = {
  runId: 9,
  groupKeys: ['baseline', 'tight'],
  bestGroupKey: 'tight',
  bestGroupReason: 'ROI 最高（按 ROI 降序 → Brier 升序 → settled 降序）',
  metrics: [
    metrics(),
    metrics({ marketScope: 'ASIAN_HANDICAP', hitRate: 0.5 }),
    metrics({ marketScope: 'ASIAN_TOTAL_GOALS', hitRate: 0.7, brierDelta: 0.004 }),
    metrics({ groupKey: 'tight', roi: 0.041, profitSum: 2.46 }),
  ],
};

describe('BacktestMetricsTable', () => {
  it('labels the sample column with units so 场次 and 腿 are not read as a ratio', () => {
    render(<BacktestMetricsTable comparison={comparison} isReady />);
    // 「样本 80 / 已结算 40」并排会被读成「只算了一半」，而 80 是场次、40 是腿。
    expect(screen.getByText('样本（场）')).toBeInTheDocument();
    expect(screen.getByText('已结算（腿）')).toBeInTheDocument();
    expect(screen.getByText('方向（腿）')).toBeInTheDocument();
    expect(screen.getAllByText('12 场 / 6 腿').length).toBeGreaterThan(0);
  });

  it('spells out what each parameter group means, once per group', () => {
    render(
      <BacktestMetricsTable comparison={comparison} isReady parameterSets={parameterSets} />,
    );
    // groupKey 是创建时起的名字，看名字看不出两组差在哪，所以阈值必须铺出来。
    expect(screen.getByRole('heading', { name: '参数组定义' })).toBeInTheDocument();
    expect(screen.getByText('HIGH 抽水≤0.100 方向≥0.075')).toBeInTheDocument();
    expect(screen.getByText('MED 抽水≤0.160 方向≥0.025')).toBeInTheDocument();
    expect(screen.getByText('HIGH 抽水≤0.080 方向≥0.090')).toBeInTheDocument();
    // 阈值放在表格外的定义区，不随每组三行重复，也不把「参数组」列撑宽。
    expect(screen.getAllByText('HIGH 抽水≤0.100 方向≥0.075')).toHaveLength(1);
  });

  it('omits the thresholds instead of inventing them', () => {
    // 公开端不返回 parameterSets。缺定义时不显示，不编一组默认值。
    render(<BacktestMetricsTable comparison={comparison} isReady />);
    expect(screen.queryByText(/抽水≤/)).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: '参数组定义' })).not.toBeInTheDocument();
  });

  it('explains why a group is the best instead of asserting it', () => {
    render(<BacktestMetricsTable comparison={comparison} isReady />);
    expect(screen.getByText('最优组：tight')).toBeInTheDocument();
    expect(screen.getByText(/ROI 最高/)).toBeInTheDocument();
  });

  it('renders one row per group and scope instead of collapsing to a single total', () => {
    render(<BacktestMetricsTable comparison={comparison} isReady />);
    // 每个参数组固定三行：合计独立计算，不等于让球与大小球相加。
    expect(screen.getByRole('heading', { name: '参数组指标' })).toBeInTheDocument();
    expect(screen.getAllByText('合计').length).toBe(2);
    expect(screen.getAllByText('让球').length).toBe(1);
    expect(screen.getAllByText('大小球').length).toBe(1);
  });

  it('keeps the negative brier delta visible with a plain verdict', () => {
    render(<BacktestMetricsTable comparison={comparison} isReady />);
    expect(screen.getAllByText('-0.0150').length).toBeGreaterThan(0);
    expect(screen.getAllByText('不如市场').length).toBeGreaterThan(0);
    expect(screen.getAllByText('+0.0040').length).toBeGreaterThan(0);
    expect(screen.getAllByText('优于市场').length).toBeGreaterThan(0);
  });

  it('shows an em dash for missing rates and never 0%', () => {
    render(
      <BacktestMetricsTable
        comparison={{ ...comparison, groupKeys: ['empty'], bestGroupKey: null, metrics: [
          metrics({ groupKey: 'empty', hitRate: null, weightedHitRate: null, roi: null, profitSum: null, brierDelta: null, settledCount: 0 }),
        ] }}
        isReady
      />,
    );
    expect(screen.getAllByText('—').length).toBeGreaterThan(2);
    expect(screen.queryByText('0.00%')).not.toBeInTheDocument();
    expect(screen.getByText('暂无样本')).toBeInTheDocument();
  });

  it('waits for the aggregating stage instead of rendering an empty table', () => {
    render(<BacktestMetricsTable comparison={undefined} isReady={false} />);
    expect(screen.getByText(/回测尚未产出结论/)).toBeInTheDocument();
  });

  it('says so when a finished run genuinely has no settled sample', () => {
    render(<BacktestMetricsTable comparison={undefined} isReady />);
    expect(screen.getByText(/没有可结算样本/)).toBeInTheDocument();
  });

  it('prints the skip distribution so an unexplained gap is traceable', () => {
    render(<BacktestMetricsTable comparison={comparison} isReady />);
    expect(screen.getAllByText(/跳过分布/).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/庄家不足两家/).length).toBeGreaterThan(0);
  });
});
