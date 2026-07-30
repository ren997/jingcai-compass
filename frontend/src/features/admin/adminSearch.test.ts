import { describe, expect, it } from 'vitest';
import {
  parseMappingSearch,
  parsePredictionLockSearch,
  parseSettlementStatusSearch,
  parseSyncRunSearch,
  toMappingQuery,
  toPredictionLockQuery,
  toSettlementStatusQuery,
  toSyncRunQuery,
} from './adminSearch';

describe('admin URL search state', () => {
  it('falls back to safe sync defaults and drops unknown enum values', () => {
    const parsed = parseSyncRunSearch(new URLSearchParams(
      'providerCode=%20STUB%20&dataType=INVALID&statuses=FAILED,RAW_SQL,PENDING&page=0&date=invalid',
    ));

    expect(parsed.providerCode).toBe('STUB');
    expect(parsed.dataType).toBeUndefined();
    expect(parsed.syncStatuses).toEqual(['FAILED']);
    expect(parsed.pageNo).toBe(1);
    expect(toSyncRunQuery(parsed)).toMatchObject({ pageSize: 20, pageNo: 1 });
  });

  it('defaults mapping review to pending and preserves supported filters', () => {
    expect(parseMappingSearch(new URLSearchParams('status=BAD&page=-1'))).toMatchObject({
      mappingStatus: 'PENDING', reviewScope: 'ACTIVE', pageNo: 1,
    });
    expect(toMappingQuery(parseMappingSearch(new URLSearchParams('providerCode=THE_ODDS_API&status=REJECTED&scope=HISTORY&page=2'))))
      .toEqual({ providerCode: 'THE_ODDS_API', mappingStatus: 'REJECTED', reviewScope: 'HISTORY', pageNo: 2, pageSize: 20 });
  });

  it('restores safe prediction and settlement operation filters from URLs', () => {
    const locks = parsePredictionLockSearch(new URLSearchParams(
      'date=2026-02-30&modelVersion=%20model-v1%20&statuses=DRAFT,LOCKED&diagnostics=OVERDUE,RAW&page=0',
    ));
    expect(locks).toMatchObject({ lotteryDate: undefined, modelVersion: 'model-v1', predictionStatuses: ['LOCKED'], lockDiagnostics: ['OVERDUE'], pageNo: 1 });
    expect(toPredictionLockQuery(locks)).toMatchObject({ pageSize: 20, pageNo: 1 });

    const settlements = parseSettlementStatusSearch(new URLSearchParams('date=2026-07-29&diagnostics=SETTLEMENT_MISSING_HAD,BAD&page=3'));
    expect(settlements).toMatchObject({ lotteryDate: '2026-07-29', diagnostics: ['SETTLEMENT_MISSING_HAD'], pageNo: 3 });
    expect(toSettlementStatusQuery(settlements)).toEqual({
      lotteryDate: '2026-07-29', modelVersion: undefined, diagnostics: ['SETTLEMENT_MISSING_HAD'], pageNo: 3, pageSize: 20,
    });
  });
});
