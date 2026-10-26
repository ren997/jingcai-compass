import { describe, expect, it } from 'vitest';
import type { BacktestCaseVo, BacktestRunVo } from '../../services/backtest';
import {
  backtestCaseVerdict,
  backtestProgressText,
  backtestRunStatusTone,
  brierDeltaVerdict,
  formatBrierDelta,
  formatProfit,
  formatRate,
  formatScore,
  scopeMetrics,
} from './backtestPresentation';

const baseCase: BacktestCaseVo = {
  id: 1,
  runId: 9,
  groupKey: 'baseline',
  externalMatchId: '2042155',
  matchId: 11,
  lotteryMatchNo: '周三001',
  leagueName: '英超',
  decisionAt: '2026-06-20T10:00:00Z',
  kickoffTime: '2026-06-20T11:45:00Z',
  decisionStage: 'DECIDED',
  skipReason: null,
  frozenBookmakerCode: 'pinnacle',
  frozenHandicapLine: -0.5,
  frozenHomeOdds: 1.95,
  frozenAwayOdds: 1.95,
  frozenTotalLine: 2.5,
  frozenOverOdds: 1.9,
  frozenUnderOdds: 2.0,
  frozenCapturedAt: '2026-06-20T09:55:00Z',
  handicapPick: 'HOME_COVER',
  handicapConfidence: 'MEDIUM',
  totalsPick: null,
  totalsConfidence: null,
  handicapDispersion: 0.01,
  totalsDispersion: 0.02,
  lambdaHome: 1.4,
  lambdaAway: 1.1,
  modelCoverProb: 0.52,
  marketCoverProb: 0.51,
  edgeCover: 0,
  modelOverProb: 0.48,
  marketOverProb: 0.5,
  edgeOver: 0,
  neighborMaxDeviation: 0.02,
  maxResidual: 1e-16,
  handicapGatePass: true,
  totalsGatePass: false,
  resultStatus: 'SETTLED',
  homeScore: 2,
  awayScore: 1,
  handicapStatus: 'HIT',
  handicapProfit: 0.95,
  totalsStatus: null,
  totalsProfit: null,
  settledAt: '2026-06-20T13:00:00Z',
};

describe('backtest presentation', () => {
  it('renders Brier as a plain score instead of a percentage', () => {
    // Brier 是无量纲平方误差，乘 100 会和命中率混成同一个视觉量纲。
    expect(formatScore(0.2025)).toBe('0.2025');
    expect(formatRate(0.2025)).toBe('20.25%');
    expect(formatScore(null)).toBe('—');
  });

  it('keeps a negative brier delta readable as "worse than market"', () => {
    // delta = market − model：负值表示模型不如市场，不取绝对值也不显示成持平。
    expect(brierDeltaVerdict(-0.015)).toBe('不如市场');
    expect(brierDeltaVerdict(0.015)).toBe('优于市场');
    expect(brierDeltaVerdict(0)).toBe('持平');
    expect(brierDeltaVerdict(null)).toBe('暂无样本');
    expect(formatBrierDelta(-0.015)).toBe('-0.0150');
  });

  it('does not turn a missing sample into 0%', () => {
    // 没下注和下了不中不是一回事，null 必须保持「—」。
    expect(formatRate(null)).toBe('—');
    expect(formatRate(0)).toBe('0.00%');
    expect(formatProfit(null)).toBe('—');
    expect(formatProfit(0)).toBe('+0.000');
    expect(formatProfit(-1.25)).toBe('-1.250');
  });

  it('maps partial runs to a warning tone instead of success', () => {
    // PARTIAL 表示样本可用但补采有失败，画成绿色会让「结论基于残缺样本」消失。
    expect(backtestRunStatusTone('SUCCEEDED')).toBe('success');
    expect(backtestRunStatusTone('PARTIAL')).toBe('partial');
    expect(backtestRunStatusTone('INTERRUPTED')).toBe('warning');
    expect(backtestRunStatusTone('CANCELLED')).toBe('muted');
    expect(backtestRunStatusTone('EVALUATING')).toBe('running');
  });

  it('writes a level line as 平手 rather than 主 0', () => {
    const levelCase: BacktestCaseVo = {
      ...baseCase,
      frozenHandicapLine: 0,
      frozenTotalLine: 3,
      totalsPick: 'UNDER',
      handicapPick: 'AWAY_COVER',
    };
    expect(backtestCaseVerdict(levelCase)).toBe('客 平手 / 小 +3');
  });

  it('flips the handicap line for away picks and falls back to the decision stage', () => {
    expect(backtestCaseVerdict(baseCase)).toBe('主 -0.5');
    const skipped: BacktestCaseVo = {
      ...baseCase,
      handicapPick: null,
      totalsPick: null,
      handicapConfidence: null,
      decisionStage: 'INSUFFICIENT_HANDICAP',
      skipReason: 'INSUFFICIENT_ASIAN_BOOKMAKERS',
    };
    expect(backtestCaseVerdict(skipped)).toBe('让球庄家不足');
  });

  it('reports queued runs and unknown totals without dividing by zero', () => {
    const run = {
      runStatus: 'QUEUED',
      runStage: 'NONE',
      processedMatchCount: 0,
      totalMatchCount: null,
      progressRatio: 0,
    } as Pick<BacktestRunVo, 'runStatus' | 'runStage' | 'processedMatchCount' | 'totalMatchCount' | 'progressRatio'>;
    expect(backtestProgressText(run)).toBe('排队中，等待 worker 抢单');
    // 没有总场次时不编造百分比：0/0 算出来的 100% 会让「还在跑」看起来像「已跑完」。
    expect(backtestProgressText({ ...run, runStatus: 'EVALUATING', runStage: 'EVALUATING' }))
      .toBe('逐步判定 · 逐步判定');
  });

  it('does not fabricate metrics for a group or scope that has none', () => {
    const metrics = [{
      runId: 9, groupKey: 'baseline', marketScope: 'ALL', caseCount: 3, decidedCount: 2,
      skippedCount: 1, pickCount: 2, settledCount: 2, pushCount: 0, halfWinCount: 0,
      halfLossCount: 0, hitCount: 1, missCount: 1, hitRate: 0.5, weightedHitRate: 0.5,
      profitSum: 0, roi: 0, yieldRate: 0, brierModel: 0.2, brierMarket: 0.19,
      brierDelta: -0.01, brierSampleSize: 2, skipReasonCounts: {}, metricsJson: null,
      computedAt: null,
    }] as never;
    expect(scopeMetrics(metrics, 'baseline', 'ALL')).toBeDefined();
    expect(scopeMetrics(metrics, 'baseline', 'ASIAN_TOTAL_GOALS')).toBeUndefined();
    expect(scopeMetrics(metrics, 'missing', 'ALL')).toBeUndefined();
  });
});
