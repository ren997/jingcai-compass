import type {
  HistoryListItemVo,
  MarketHitRateVo,
  MatchResultFactHistoryVo,
  MatchResultFactStatus,
  ProbabilityMetricUnavailableReason,
  RoiUnavailableReason,
  SettlementMarket,
  SettlementStatus,
  StatisticsMetricsVo,
} from '../../services/public';

export const settlementMarketLabels: Record<SettlementMarket, string> = {
  HAD: '胜平负（HAD）',
  HHAD: '让球胜平负（HHAD）',
};

export const settlementStatusLabels: Record<SettlementStatus, string> = {
  PENDING: '待结算',
  HIT: '命中',
  MISS: '未中',
  VOID: '作废',
};

const factStatusLabels: Record<MatchResultFactStatus, string> = {
  PENDING: '待确认',
  FINAL: '最终赛果',
  VOID: '官方作废',
};

const probabilityReasonLabels: Record<ProbabilityMetricUnavailableReason, string> = {
  NO_FINAL_SAMPLE: '没有当前最终赛果样本',
};

const roiReasonLabels: Record<RoiUnavailableReason, string> = {
  MISSING_FIXED_BETTING_RULE: '缺少冻结的固定下注规则',
  MISSING_LOCKED_BETTING_MARKET: '缺少冻结的下注市场选择',
  MISSING_LOCKED_ODDS_INPUT: '缺少锁定时点赔率输入',
};

export function settlementStatusLabel(status: SettlementStatus) {
  return settlementStatusLabels[status];
}

export function settlementMarketLabel(market: SettlementMarket) {
  return settlementMarketLabels[market];
}

export function factStatusLabel(status: MatchResultFactStatus) {
  return factStatusLabels[status];
}

export function probabilityUnavailableReasonLabel(reason: ProbabilityMetricUnavailableReason) {
  return probabilityReasonLabels[reason];
}

export function roiUnavailableReasonLabel(reason: RoiUnavailableReason) {
  return roiReasonLabels[reason];
}

export function formatPercent(value: number | null | undefined) {
  return value === null || value === undefined ? '—' : `${(value * 100).toFixed(1)}%`;
}

export function formatDecimal(value: number | null | undefined) {
  return value === null || value === undefined ? '—' : value.toFixed(4);
}

export function currentFact(record: HistoryListItemVo) {
  return record.resultFacts.find((fact) => fact.current);
}

export function factScore(fact: MatchResultFactHistoryVo | undefined) {
  if (!fact) {
    return '待赛果';
  }
  if (fact.homeScore === null || fact.awayScore === null) {
    return factStatusLabel(fact.factStatus);
  }
  return `${fact.homeScore} : ${fact.awayScore}`;
}

export function marketMetrics(metrics: StatisticsMetricsVo, market: SettlementMarket): MarketHitRateVo {
  return market === 'HAD' ? metrics.had : metrics.hhad;
}
