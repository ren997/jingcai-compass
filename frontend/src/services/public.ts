import { requestApi, resolveApiUrl } from './http';

export const MATCH_STATUSES = [
  'SCHEDULED',
  'LOCKED',
  'IN_PROGRESS',
  'FINISHED',
  'POSTPONED',
  'CANCELLED',
  'ABANDONED',
] as const;

export type MatchStatus = (typeof MATCH_STATUSES)[number];

export const MATCH_LIST_SORTS = [
  'KICKOFF_ASC',
  'KICKOFF_DESC',
  'LOTTERY_MATCH_NO_ASC',
  'LOTTERY_MATCH_NO_DESC',
] as const;

export type MatchListSort = (typeof MATCH_LIST_SORTS)[number];

export type MatchDataAvailability =
  | 'AVAILABLE'
  | 'NO_SPORTTERY_SNAPSHOT'
  | 'NO_ASIAN_ODDS_SNAPSHOT'
  | 'NO_SOURCE_MAPPING'
  | 'MAPPING_UNCONFIRMED';

export type OddsSnapshotType = 'FIRST_SEEN' | 'PRE_KICKOFF' | 'OTHER';

export type MappingStatus = 'PENDING' | 'AUTO_CONFIRMED' | 'MANUAL_CONFIRMED' | 'REJECTED';

export type PredictionStatus = 'PUBLISHED' | 'LOCKED';

export type HandicapPick = 'HOME_WIN' | 'DRAW' | 'AWAY_WIN';

export type ConfidenceLevel = 'LOW' | 'MEDIUM' | 'HIGH';

export type PublicSnapshotAvailability = 'AVAILABLE' | 'UNAVAILABLE';

export const SETTLEMENT_MARKETS = ['HAD', 'HHAD'] as const;

export type SettlementMarket = (typeof SETTLEMENT_MARKETS)[number];

export const SETTLEMENT_STATUSES = ['PENDING', 'HIT', 'MISS', 'VOID'] as const;

export type SettlementStatus = (typeof SETTLEMENT_STATUSES)[number];

export type MatchResultFactStatus = 'PENDING' | 'FINAL' | 'VOID';

export type ProbabilityMetricUnavailableReason = 'NO_FINAL_SAMPLE';

export type RoiUnavailableReason =
  | 'MISSING_FIXED_BETTING_RULE'
  | 'MISSING_LOCKED_BETTING_MARKET'
  | 'MISSING_LOCKED_ODDS_INPUT';

export type PageResult<T> = {
  records: T[];
  pageNo: number;
  pageSize: number;
  total: number;
};

export type MatchListQuery = {
  lotteryDate: string;
  leagueId?: number;
  matchStatuses?: MatchStatus[];
  sort: MatchListSort;
  pageNo: number;
  pageSize: number;
};

/** T501 分页公开比赛列表项。 */
export type MatchListItemVo = {
  matchId: number;
  lotteryDate: string;
  lotteryMatchNo: string;
  leagueId: number | null;
  leagueName: string;
  homeTeamName: string;
  awayTeamName: string;
  kickoffTime: string;
  matchStatus: MatchStatus;
  officialHandicap: number | null;
  sportteryAvailability: MatchDataAvailability;
  sportteryDataSource: string | null;
  sportteryCapturedAt: string | null;
  sportteryProviderUpdatedAt: string | null;
};

export type SportteryMarketVo = {
  availability: MatchDataAvailability;
  dataSource: string | null;
  capturedAt: string | null;
  providerUpdatedAt: string | null;
  officialHandicap: number | null;
  hadHomeSp: number | null;
  hadDrawSp: number | null;
  hadAwaySp: number | null;
  hhadHomeSp: number | null;
  hhadDrawSp: number | null;
  hhadAwaySp: number | null;
  sellStatus: string | null;
};

export type AsianOddsMarketVo = {
  providerCode: string;
  bookmakerCode: string;
  handicapLine: number | null;
  homeOdds: number | null;
  awayOdds: number | null;
  totalLine: number | null;
  overOdds: number | null;
  underOdds: number | null;
  snapshotType: OddsSnapshotType;
  capturedAt: string | null;
  providerUpdatedAt: string | null;
};

export type MatchSourceMappingVo = {
  providerCode: string;
  externalMatchId: string;
  mappingStatus: MappingStatus;
  mappingConfidence: number | null;
  mappingMethod: string | null;
  mappingExplanation: string | null;
  mappingUpdatedAt: string | null;
};

/** T501 公开比赛基础详情。 */
export type MatchDetailVo = {
  matchId: number;
  lotteryDate: string;
  lotteryMatchNo: string;
  leagueId: number | null;
  leagueName: string;
  homeTeamName: string;
  awayTeamName: string;
  kickoffTime: string;
  matchStatus: MatchStatus;
  homeScore: number | null;
  awayScore: number | null;
  sportteryMarket: SportteryMarketVo;
  asianOddsAvailability: MatchDataAvailability;
  asianOddsMarkets: AsianOddsMarketVo[];
  mappingAvailability: MatchDataAvailability;
  sourceMappings: MatchSourceMappingVo[];
};

/** 可公开校验和下载的预测快照元数据，不包含内部存储地址。 */
export type PredictionSnapshotVo = {
  snapshotId: number;
  snapshotDate: string;
  snapshotVersion: number;
  snapshotHash: string;
  contentType: string;
  contentLength: number;
  publishedAt: string;
};

export type PredictionSnapshotVerificationVo = {
  snapshotId: number;
  snapshotHash: string;
  contentLength: number;
  verified: boolean;
};

/** 单个已公开模型预测版本及其透明字段。 */
export type PredictionVersionVo = {
  predictionId: number;
  predictionVersion: number;
  replacesPredictionId: number | null;
  predictionStatus: PredictionStatus;
  featureVersion: string;
  homeWinProb: number;
  drawProb: number;
  awayWinProb: number;
  handicapPick: HandicapPick;
  expectedTotalGoals: number;
  confidenceLevel: ConfidenceLevel;
  analysisSummary: string;
  generatedAt: string;
  publishTime: string;
  lockTime: string;
  predictionHash: string;
  snapshotAvailability: PublicSnapshotAvailability;
  snapshot: PredictionSnapshotVo | null;
};

export type PredictionModelDetailVo = {
  modelVersion: string;
  currentPrediction: PredictionVersionVo;
  historicalPredictions: PredictionVersionVo[];
};

/** 单场比赛的全部当前公开模型预测和版本替代链。 */
export type PredictionDetailVo = {
  matchId: number;
  modelPredictions: PredictionModelDetailVo[];
};

/** T507 公开历史分页筛选。 */
export type HistoryListQuery = {
  startDate?: string;
  endDate?: string;
  leagueId?: number;
  modelVersion?: string;
  lockedOnly: boolean;
  settlementMarket: SettlementMarket;
  settlementStatuses?: SettlementStatus[];
  pageNo: number;
  pageSize: number;
};

export type HistoryMatchVo = {
  matchId: number;
  lotteryDate: string;
  lotteryMatchNo: string;
  leagueId: number | null;
  leagueName: string;
  homeTeamName: string;
  awayTeamName: string;
  kickoffTime: string;
};

export type MatchResultFactHistoryVo = {
  factId: number;
  factVersion: number;
  supersedesFactVersion: number | null;
  factStatus: MatchResultFactStatus;
  matchStatus: MatchStatus;
  homeScore: number | null;
  awayScore: number | null;
  providerUpdatedAt: string | null;
  current: boolean;
  createdAt: string;
};

export type SettlementVersionVo = {
  settlementId: number;
  settlementVersion: number;
  supersedesSettlementVersion: number | null;
  settlementStatus: SettlementStatus;
  matchFactId: number | null;
  ruleVersion: string;
  current: boolean;
  createdAt: string;
};

export type MarketSettlementHistoryVo = {
  marketType: SettlementMarket;
  currentStatus: SettlementStatus;
  currentSettlementPersisted: boolean;
  recalculatedAfterFactCorrection: boolean;
  versions: SettlementVersionVo[];
};

/** 一条公开预测及其完整赛果、结算版本链。 */
export type HistoryListItemVo = {
  predictionId: number;
  predictionVersion: number;
  modelVersion: string;
  featureVersion: string;
  predictionStatus: PredictionStatus;
  homeWinProb: number;
  drawProb: number;
  awayWinProb: number;
  handicapPick: HandicapPick;
  expectedTotalGoals: number;
  confidenceLevel: ConfidenceLevel;
  analysisSummary: string;
  predictionHash: string;
  generatedAt: string;
  publishTime: string | null;
  lockTime: string | null;
  match: HistoryMatchVo;
  resultFacts: MatchResultFactHistoryVo[];
  settlementMarkets: MarketSettlementHistoryVo[];
  recalculatedAfterFactCorrection: boolean;
};

/** T507 统计筛选；省略日期时由服务端使用近 30 天默认口径。 */
export type StatisticsSummaryQuery = {
  startDate?: string;
  endDate?: string;
  leagueId?: number;
  modelVersion?: string;
};

export type ProbabilityMetricsVo = {
  sampleSize: number;
  brierScore: number | null;
  logLoss: number | null;
  unavailableReasons: ProbabilityMetricUnavailableReason[];
};

export type MarketHitRateVo = {
  marketType: SettlementMarket;
  settledSampleSize: number;
  hitCount: number;
  missCount: number;
  pendingCount: number;
  voidCount: number;
  hitRate: number | null;
};

export type RoiMetricsVo = {
  available: boolean;
  roi: number | null;
  yield: number | null;
  sampleSize: number;
  unavailableReasons: RoiUnavailableReason[];
};

export type StatisticsMetricsVo = {
  lockedPredictionCount: number;
  finalFactCount: number;
  pendingFactCount: number;
  voidFactCount: number;
  probabilityMetrics: ProbabilityMetricsVo;
  had: MarketHitRateVo;
  hhad: MarketHitRateVo;
  roi: RoiMetricsVo;
};

export type StatisticsWindowVo = {
  startDate: string;
  endDate: string;
  metrics: StatisticsMetricsVo;
};

export type LeagueStatisticsVo = {
  leagueId: number | null;
  leagueName: string | null;
  metrics: StatisticsMetricsVo;
};

export type ModelVersionStatisticsVo = {
  modelVersion: string;
  metrics: StatisticsMetricsVo;
};

export type StatisticsSummaryVo = {
  asOfDate: string;
  appliedFilter: {
    leagueId: number | null;
    modelVersion: string | null;
  };
  requestedWindow: StatisticsWindowVo;
  trailingSevenDays: StatisticsWindowVo;
  trailingThirtyDays: StatisticsWindowVo;
  byLeague: LeagueStatisticsVo[];
  byModelVersion: ModelVersionStatisticsVo[];
};

/** T505 上海当天的公开比赛及预测去重场次数。 */
export type HomeTodayOverviewVo = {
  matchCount: number;
  publishedPredictionMatchCount: number;
};

/** 当天体彩池采集时刻与相对首页汇总时间的数据年龄。 */
export type HomeDataFreshnessVo = {
  sportteryLastCapturedAt: string | null;
  sportteryDataAgeSeconds: number | null;
};

/** 可由持久化事实重建的公开首页汇总。 */
export type HomeSummaryVo = {
  asOfDate: string;
  today: HomeTodayOverviewVo;
  pendingSettlementMatchCount: number;
  historicalPublishedMatchCount: number;
  trailingSevenDays: StatisticsWindowVo;
  trailingThirtyDays: StatisticsWindowVo;
  dataFreshness: HomeDataFreshnessVo;
  latestPublishedSnapshotAt: string | null;
  generatedAt: string;
};

/** 读取公开首页的事实汇总。 */
export function fetchHomeSummary(signal?: AbortSignal) {
  return requestApi<HomeSummaryVo>('/api/public/home/summary', {
    method: 'GET',
    signal,
  });
}

/** 使用 T501 的服务端分页、筛选和排序白名单查询比赛。 */
export function fetchMatchList(query: MatchListQuery, signal?: AbortSignal) {
  return requestApi<PageResult<MatchListItemVo>>('/api/public/matches/list', {
    method: 'POST',
    body: query,
    signal,
  });
}

/** 读取单场比赛的基础资料、体彩和亚盘市场。 */
export function fetchMatchDetail(matchId: number, signal?: AbortSignal) {
  return requestApi<MatchDetailVo>('/api/public/matches/detail', {
    method: 'POST',
    body: { matchId },
    signal,
  });
}

/** 查询单场全部模型的当前公开预测及替代历史。 */
export function fetchPredictionDetail(matchId: number, signal?: AbortSignal) {
  return requestApi<PredictionDetailVo>('/api/public/predictions/detail', {
    method: 'POST',
    body: { matchId },
    signal,
  });
}

/** 分页读取公开预测、赛果和结算版本历史。 */
export function fetchHistoryList(query: HistoryListQuery, signal?: AbortSignal) {
  return requestApi<PageResult<HistoryListItemVo>>('/api/public/history/list', {
    method: 'POST',
    body: query,
    signal,
  });
}

/** 读取指定时间与非时间筛选下的公开表现统计。 */
export function fetchStatisticsSummary(query: StatisticsSummaryQuery, signal?: AbortSignal) {
  return requestApi<StatisticsSummaryVo>('/api/public/statistics/summary', {
    method: 'POST',
    body: query,
    signal,
  });
}

/** 校验已发布预测快照当前对象的哈希和长度。 */
export function verifyPredictionSnapshot(snapshotId: number) {
  return requestApi<PredictionSnapshotVerificationVo>(
    `/api/public/predictions/snapshots/${snapshotId}/verify`,
    { method: 'POST' },
  );
}

/** 返回通过后端受控下载的公开快照地址，不泄露存储实现 URL。 */
export function predictionSnapshotDownloadUrl(snapshotId: number) {
  return resolveApiUrl(`/api/public/predictions/snapshots/${snapshotId}/download`);
}
