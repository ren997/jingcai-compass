import type {
  AdminPredictionStatusItem,
  AdminResultFact,
  AdminSettlementMarket,
  AdminSettlementMarketType,
  AdminSettlementStatus,
  AdminStatusDiagnostic,
} from '../../services/admin';

export const lockLabels: Record<string, string> = {
  OVERDUE: '锁定滞后', SCHEDULED: '等待锁定', LOCKED: '已锁定',
};

/** 诊断码与市场解耦后的三种码；市场由 AdminStatusDiagnostic.marketType 单独给出。 */
export const settlementLabels: Record<string, string> = {
  AWAITING_RESULT: '等待确认赛果',
  SETTLEMENT_MISSING: '待结算',
  SETTLEMENT_STALE: '需重算',
};

export const settlementMarketLabels: Record<AdminSettlementMarketType, string> = {
  HAD: '胜平负（HAD）',
  HHAD: '让球胜平负（HHAD）',
  ASIAN_HANDICAP: '亚盘让球',
  ASIAN_TOTAL_GOALS: '亚盘大小球',
};

/** 诊断文案里的市场用短标签：诊断行本身已经很长，再拼全称会读不动。 */
const settlementMarketShortLabels: Record<AdminSettlementMarketType, string> = {
  HAD: 'HAD',
  HHAD: 'HHAD',
  ASIAN_HANDICAP: '亚盘让球',
  ASIAN_TOTAL_GOALS: '亚盘大小球',
};

/** 走盘与半赢半输必须译出来：原样输出英文会让运营以为状态字段坏了。 */
export const settlementStatusLabels: Record<AdminSettlementStatus, string> = {
  PENDING: '待结算',
  HIT: '命中',
  MISS: '未中',
  PUSH: '走盘',
  HALF_WIN: '半赢',
  HALF_LOSS: '半输',
  VOID: '作废',
};

export function settlementStatusLabel(status: AdminSettlementStatus) {
  return settlementStatusLabels[status] ?? status;
}

export function settlementMarketLabel(market: AdminSettlementMarketType) {
  return settlementMarketLabels[market] ?? market;
}

/**
 * 诊断文案。
 *
 * <p>诊断码本身不再带市场（SETTLEMENT_MISSING 而不是 SETTLEMENT_MISSING_HAD），
 * 所以有 marketType 时把市场拼在前面，否则运营看到的只是"待结算"，不知道是哪个市场。
 */
export function diagnosticLabel(diagnostic: AdminStatusDiagnostic, labels: Record<string, string>) {
  const label = labels[diagnostic.code] ?? diagnostic.description;
  return diagnostic.marketType ? `${settlementMarketShortLabels[diagnostic.marketType]} ${label}` : label;
}

export function diagnosticsText(diagnostics: AdminStatusDiagnostic[], labels: Record<string, string>) {
  return diagnostics.map((diagnostic) => diagnosticLabel(diagnostic, labels)).join('、') || '当前正常';
}

export function factText(fact: AdminResultFact | null) {
  const source = fact?.resultSource === 'MANUAL' ? '人工补录，非官方源' : '官方赛果';
  if (!fact) return '暂无赛果';
  if (fact.factStatus === 'PENDING') return `${source}待确认`;
  if (fact.factStatus === 'VOID') return `${source}作废`;
  return `${fact.homeScore} : ${fact.awayScore}（FINAL · ${source}）`;
}

export function formatProfit(value: number | null | undefined) {
  if (value === null || value === undefined) {
    return '—';
  }
  return `${value > 0 ? '+' : ''}${value.toFixed(4)}`;
}

/** 单个市场的当前结算状态；未落库时明确标注，不冒充 PENDING 结算行。 */
export function marketText(market: AdminSettlementMarket) {
  if (!market.currentSettlementPersisted) return 'PENDING（未落库）';
  const profit = market.profit === null || market.profit === undefined
    ? ''
    : ` · 收益 ${formatProfit(market.profit)}`;
  return `${settlementStatusLabel(market.currentStatus)}${profit}${market.stale ? '（引用旧赛果）' : ''}`;
}

export function predictionTypeLabel(item: AdminPredictionStatusItem) {
  return item.predictionType === 'ASIAN' ? '亚盘' : '竞彩';
}
