import type {
  AsianHandicapPick,
  HistoryListItemVo,
  MarketHitRateVo,
  MatchResultFactHistoryVo,
  MatchResultFactSource,
  MatchResultFactStatus,
  PredictionType,
  ProbabilityMetricUnavailableReason,
  RoiUnavailableReason,
  SettlementMarket,
  SettlementStatus,
  StatisticsMetricsVo,
  TotalGoalsPick,
} from '../../services/public';

export const settlementMarketLabels: Record<SettlementMarket, string> = {
  HAD: '胜平负（HAD）',
  HHAD: '让球胜平负（HHAD）',
  ASIAN_HANDICAP: '亚盘让球',
  ASIAN_TOTAL_GOALS: '亚盘大小球',
};

export const settlementStatusLabels: Record<SettlementStatus, string> = {
  PENDING: '待结算',
  HIT: '命中',
  MISS: '未中',
  PUSH: '走盘',
  HALF_WIN: '半赢',
  HALF_LOSS: '半输',
  VOID: '作废',
};

export const predictionTypeLabels: Record<PredictionType, string> = {
  SPORTTERY: '竞彩',
  ASIAN: '亚盘',
};

export const asianHandicapPickLabels: Record<AsianHandicapPick, string> = {
  HOME_COVER: '主队让球',
  AWAY_COVER: '客队受让',
};

export const totalGoalsPickLabels: Record<TotalGoalsPick, string> = {
  OVER: '大球',
  UNDER: '小球',
};

const factStatusLabels: Record<MatchResultFactStatus, string> = {
  PENDING: '待确认',
  FINAL: '最终赛果',
  VOID: '赛果作废',
};

const factSourceLabels: Record<MatchResultFactSource, string> = {
  OFFICIAL: '官方赛果',
  MANUAL: '人工补录，非官方源',
};

const probabilityReasonLabels: Record<ProbabilityMetricUnavailableReason, string> = {
  NO_FINAL_SAMPLE: '没有当前最终赛果样本',
  NO_SETTLED_ASIAN_SAMPLE: '没有已结算的亚盘方向样本',
  MISSING_ASIAN_MODEL_PROBABILITY: '亚盘预测缺少模型/市场概率',
};

const roiReasonLabels: Record<RoiUnavailableReason, string> = {
  MISSING_FIXED_BETTING_RULE: '缺少冻结的固定下注规则',
  MISSING_LOCKED_BETTING_MARKET: '缺少冻结的下注市场选择',
  MISSING_LOCKED_ODDS_INPUT: '缺少锁定时点赔率输入',
  NO_SETTLED_SETTLEMENT: '没有已结算的亚盘方向',
  MISSING_SETTLEMENT_PROFIT: '已结算亚盘行缺少收益列',
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

export function factSourceLabel(source: MatchResultFactSource | undefined) {
  return factSourceLabels[source ?? 'OFFICIAL'];
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

/**
 * 取指定市场的命中指标；该产品线不结算这个市场时返回 null 而不是抛错。
 *
 * <p>返回 null 让页面可以整块不渲染这一行。用回退到别的市场来"保证有值"会让亚盘页面
 * 显示竞彩的命中率，比缺一行更糟。
 */
export function marketMetrics(
  metrics: StatisticsMetricsVo,
  market: SettlementMarket,
): MarketHitRateVo | null {
  return metrics.markets.find((item) => item.marketType === market) ?? null;
}

/** 该产品线的首个市场，用于市场下拉框的默认值。 */
export function defaultMarketFor(predictionType: PredictionType): SettlementMarket {
  return predictionType === 'ASIAN' ? 'ASIAN_HANDICAP' : 'HAD';
}

/** 亚盘方向标签；体彩记录没有方向，返回占位符。 */
export function asianPickSummary(record: HistoryListItemVo) {
  const handicap = record.asianHandicapPick
    ? asianHandicapPickLabels[record.asianHandicapPick]
    : null;
  const totals = record.totalGoalsPick ? totalGoalsPickLabels[record.totalGoalsPick] : null;
  if (!handicap && !totals) {
    return '—';
  }
  return [handicap, totals].filter(Boolean).join(' / ');
}

/** 一句话说清"市场当时怎么定价"，用于历史台账的透明性展示。 */
export function asianMarketReference(record: HistoryListItemVo) {
  const model = record.asianModel;
  if (!model) {
    return '—';
  }
  const cover = formatPercent(model.marketCoverProb);
  const over = formatPercent(model.marketOverProb);
  if (cover === '—' && over === '—') {
    return '—';
  }
  return `让球主队 ${cover} / 大球 ${over}`;
}

export function formatSigned(value: number | null | undefined, digits = 4) {
  if (value === null || value === undefined) {
    return '—';
  }
  return `${value > 0 ? '+' : ''}${value.toFixed(digits)}`;
}
