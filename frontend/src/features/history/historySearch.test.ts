import { describe, expect, it } from 'vitest';
import {
  parseHistorySearch,
  parseStatisticsSearch,
  toHistoryListQuery,
  toHistorySearchParams,
  toStatisticsSearchParams,
} from './historySearch';

describe('history URL search state', () => {
  it('uses the T507 history defaults for invalid values and normalizes statuses', () => {
    const search = parseHistorySearch(new URLSearchParams('startDate=bad&endDate=2026-02-30&leagueId=-3&locked=yes&market=OTHER&statuses=HIT,UNKNOWN,PENDING&page=0'));

    expect(search).toEqual({
      startDate: undefined,
      endDate: undefined,
      leagueId: undefined,
      modelVersion: undefined,
      lockedOnly: false,
      settlementMarket: 'HAD',
      settlementStatuses: ['PENDING', 'HIT'],
      pageNo: 1,
    });
    expect(toHistoryListQuery(search)).toEqual({
      lockedOnly: false,
      settlementMarket: 'HAD',
      settlementStatuses: ['PENDING', 'HIT'],
      pageNo: 1,
      pageSize: 20,
    });
  });

  it('rejects reversed date ranges and serializes a recoverable history filter', () => {
    expect(parseHistorySearch(new URLSearchParams('startDate=2026-07-29&endDate=2026-07-28'))).toMatchObject({
      startDate: undefined,
      endDate: undefined,
    });
    const params = toHistorySearchParams({
      startDate: '2026-07-01',
      endDate: '2026-07-28',
      leagueId: 7,
      modelVersion: 'model-v1',
      lockedOnly: true,
      settlementMarket: 'HHAD',
      settlementStatuses: ['HIT', 'MISS'],
      pageNo: 2,
    });

    expect(params.toString()).toBe('market=HHAD&page=2&startDate=2026-07-01&endDate=2026-07-28&leagueId=7&modelVersion=model-v1&locked=1&statuses=HIT%2CMISS');
  });

  it('keeps empty statistics filters empty for the server default near-30-day window', () => {
    expect(parseStatisticsSearch(new URLSearchParams('startDate=2026-07-29&endDate=2026-07-28&leagueId=bad'))).toEqual({
      startDate: undefined,
      endDate: undefined,
      leagueId: undefined,
      modelVersion: undefined,
    });
    expect(toStatisticsSearchParams({ modelVersion: 'model-v1' }).toString()).toBe('modelVersion=model-v1');
  });
});
