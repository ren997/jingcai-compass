import type { AdminResultFact, AdminSettlementMarket, AdminStatusDiagnostic } from '../../services/admin';

export const lockLabels: Record<string, string> = {
  OVERDUE: '锁定滞后', SCHEDULED: '等待锁定', LOCKED: '已锁定',
};

export const settlementLabels: Record<string, string> = {
  AWAITING_RESULT: '等待官方赛果',
  SETTLEMENT_MISSING_HAD: 'HAD 待结算',
  SETTLEMENT_MISSING_HHAD: 'HHAD 待结算',
  SETTLEMENT_STALE_HAD: 'HAD 需重算',
  SETTLEMENT_STALE_HHAD: 'HHAD 需重算',
};

export function diagnosticsText(diagnostics: AdminStatusDiagnostic[], labels: Record<string, string>) {
  return diagnostics.map((diagnostic) => labels[diagnostic.code] ?? diagnostic.description).join('、') || '当前正常';
}

export function factText(fact: AdminResultFact | null) {
  if (!fact) return '暂无官方赛果';
  if (fact.factStatus === 'PENDING') return '官方赛果待确认';
  if (fact.factStatus === 'VOID') return '官方赛果作废';
  return `${fact.homeScore} : ${fact.awayScore}（FINAL）`;
}

export function marketText(market: AdminSettlementMarket) {
  if (!market.currentSettlementPersisted) return 'PENDING（未落库）';
  return `${market.currentStatus}${market.stale ? '（引用旧赛果）' : ''}`;
}
