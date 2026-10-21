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
      // 缺省落在亚盘：页面的默认视图必须是当前主力产品线。
      predictionType: 'ASIAN',
      settlementMarket: 'ASIAN_HANDICAP',
      settlementStatuses: ['PENDING', 'HIT'],
      pageNo: 1,
    });
    expect(toHistoryListQuery(search)).toEqual({
      lockedOnly: false,
      predictionType: 'ASIAN',
      settlementMarket: 'ASIAN_HANDICAP',
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
      predictionType: 'SPORTTERY',
      settlementMarket: 'HHAD',
      settlementStatuses: ['HIT', 'MISS'],
      pageNo: 2,
    });

    expect(params.toString()).toBe('type=SPORTTERY&market=HHAD&page=2&startDate=2026-07-01&endDate=2026-07-28&leagueId=7&modelVersion=model-v1&locked=1&statuses=HIT%2CMISS');
  });

  it('keeps market and statuses self-consistent with the product line', () => {
    // 手工改过的 URL 可能给出「亚盘 + HAD」，后端会 400；这里必须回退到亚盘的默认市场。
    const asian = parseHistorySearch(new URLSearchParams('type=ASIAN&market=HAD&statuses=HIT,PUSH,HALF_WIN'));
    expect(asian.predictionType).toBe('ASIAN');
    expect(asian.settlementMarket).toBe('ASIAN_HANDICAP');
    expect(asian.settlementStatuses).toEqual(['HIT', 'PUSH', 'HALF_WIN']);

    // 竞彩不可能出现走盘与半赢半输，这些状态必须被裁掉，否则结果集必然为空。
    const sporttery = parseHistorySearch(new URLSearchParams('type=SPORTTERY&statuses=PUSH,HALF_WIN,HIT'));
    expect(sporttery.settlementMarket).toBe('HAD');
    expect(sporttery.settlementStatuses).toEqual(['HIT']);
  });

  it('keeps empty statistics filters empty for the server default near-30-day window', () => {
    expect(parseStatisticsSearch(new URLSearchParams('startDate=2026-07-29&endDate=2026-07-28&leagueId=bad'))).toEqual({
      startDate: undefined,
      endDate: undefined,
      leagueId: undefined,
      modelVersion: undefined,
      predictionType: 'ASIAN',
    });
    expect(toStatisticsSearchParams({ modelVersion: 'model-v1', predictionType: 'SPORTTERY' }).toString()).toBe('type=SPORTTERY&modelVersion=model-v1');
    expect(toStatisticsSearchParams({ predictionType: 'ASIAN' }).toString()).toBe('type=ASIAN');
  });

  it('infers the product line from a legacy market-only link', () => {
    // 老链接只带 market：不能因为缺 type 就把「亚盘让球」读成竞彩再回退到 HAD。
    const legacy = parseHistorySearch(new URLSearchParams('market=ASIAN_TOTAL_GOALS'));
    expect(legacy.predictionType).toBe('ASIAN');
    expect(legacy.settlementMarket).toBe('ASIAN_TOTAL_GOALS');
  });
});
