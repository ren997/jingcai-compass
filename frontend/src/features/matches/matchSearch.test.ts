import { describe, expect, it } from 'vitest';
import { parseMatchListSearch, toMatchListQuery, toMatchListSearchParams } from './matchSearch';

describe('match list URL search', () => {
  it('falls back to safe defaults for invalid URL values', () => {
    const search = parseMatchListSearch(
      new URLSearchParams('date=2026-02-30&leagueId=-7&statuses=UNKNOWN&sort=RAW_SQL&page=0'),
      '2026-07-28',
    );

    expect(search).toEqual({
      lotteryDate: '2026-07-28',
      leagueId: undefined,
      matchStatuses: [],
      sort: 'KICKOFF_ASC',
      pageNo: 1,
    });
  });

  it('round-trips supported filters and adds the fixed public page size', () => {
    const search = parseMatchListSearch(
      new URLSearchParams('date=2026-07-22&leagueId=7&statuses=FINISHED,SCHEDULED&sort=KICKOFF_DESC&page=3'),
      '2026-07-28',
    );

    expect(search.matchStatuses).toEqual(['SCHEDULED', 'FINISHED']);
    expect(toMatchListSearchParams(search).toString()).toBe(
      'date=2026-07-22&sort=KICKOFF_DESC&page=3&leagueId=7&statuses=SCHEDULED%2CFINISHED',
    );
    expect(toMatchListQuery(search)).toEqual({ ...search, pageSize: 20 });
  });
});
