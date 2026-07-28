import { describe, expect, it } from 'vitest';
import { parseMappingSearch, parseSyncRunSearch, toMappingQuery, toSyncRunQuery } from './adminSearch';

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
      mappingStatus: 'PENDING', pageNo: 1,
    });
    expect(toMappingQuery(parseMappingSearch(new URLSearchParams('providerCode=THE_ODDS_API&status=REJECTED&page=2'))))
      .toEqual({ providerCode: 'THE_ODDS_API', mappingStatus: 'REJECTED', pageNo: 2, pageSize: 20 });
  });
});
