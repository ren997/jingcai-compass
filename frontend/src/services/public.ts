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

export type PredictionStatus = 'PUBLISHED' | 'LOCKED';

export type HandicapPick = 'HOME_WIN' | 'DRAW' | 'AWAY_WIN';

export type AsianHandicapPick = 'HOME_COVER' | 'AWAY_COVER';

export type TotalGoalsPick = 'OVER' | 'UNDER';

export type ConfidenceLevel = 'LOW' | 'MEDIUM' | 'HIGH';

export type PublicSnapshotAvailability = 'AVAILABLE' | 'UNAVAILABLE';

/** 预测产品线。两类预测的结算市场、命中口径与 Brier 定义都不同，不能混算。 */
export const PREDICTION_TYPES = ['SPORTTERY', 'ASIAN'] as const;

export type PredictionType = (typeof PREDICTION_TYPES)[number];

/** 每类预测各自结算的市场；顺序与后端 MarketTypeEnum.marketsFor 一致。 */
export const SETTLEMENT_MARKETS = [
  'HAD',
  'HHAD',
  'ASIAN_HANDICAP',
  'ASIAN_TOTAL_GOALS',
] as const;

export type SettlementMarket = (typeof SETTLEMENT_MARKETS)[number];

export const ASIAN_SETTLEMENT_MARKETS = ['ASIAN_HANDICAP', 'ASIAN_TOTAL_GOALS'] as const;

export function settlementMarketsFor(predictionType: PredictionType): readonly SettlementMarket[] {
  return predictionType === 'ASIAN' ? ASIAN_SETTLEMENT_MARKETS : (['HAD', 'HHAD'] as const);
}

/** 亚盘市场存在走盘与半赢半输，竞彩只有胜/负/走。 */
export const SETTLEMENT_STATUSES = [
  'PENDING',
  'HIT',
  'MISS',
  'PUSH',
  'HALF_WIN',
  'HALF_LOSS',
  'VOID',
] as const;

export type SettlementStatus = (typeof SETTLEMENT_STATUSES)[number];

/**
 * 竞彩只有胜/负/走三种结局，不存在走盘与半赢半输。
 *
 * <p>状态筛选也按产品线裁剪：在竞彩下勾"走盘"必然查出空页，而空页无法与"这段时间没有预测"
 * 区分——这正是需要提前消掉的歧义。
 */
export const SPORTTERY_SETTLEMENT_STATUSES = ['PENDING', 'HIT', 'MISS', 'VOID'] as const;

export function settlementStatusesFor(predictionType: PredictionType): readonly SettlementStatus[] {
  return predictionType === 'ASIAN' ? SETTLEMENT_STATUSES : SPORTTERY_SETTLEMENT_STATUSES;
}

/** 亚盘模型概率十列；概率一律为首边口径（主队赢盘 / 大球），看另一边需取 1 − p。 */
export type PredictionAsianModelVo = {
  lambdaHome: number | null;
  lambdaAway: number | null;
  modelCoverProb: number | null;
  marketCoverProb: number | null;
  edgeCover: number | null;
  modelOverProb: number | null;
  marketOverProb: number | null;
  edgeOver: number | null;
  handicapDispersion: number | null;
  totalsDispersion: number | null;
};

export type MatchResultFactStatus = 'PENDING' | 'FINAL' | 'VOID';

export type MatchResultFactSource = 'OFFICIAL' | 'MANUAL';

export type ProbabilityMetricUnavailableReason =
  | 'NO_FINAL_SAMPLE'
  | 'NO_SETTLED_ASIAN_SAMPLE'
  | 'MISSING_ASIAN_MODEL_PROBABILITY';

/** 三分类用 LogLoss/Brier 的原始定义，二分类用方向口径，两者数值不可比。 */
export type ProbabilityScoringMode = 'THREE_WAY' | 'ASIAN_DIRECTION';

export type RoiUnavailableReason =
  | 'MISSING_FIXED_BETTING_RULE'
  | 'MISSING_LOCKED_BETTING_MARKET'
  | 'MISSING_LOCKED_ODDS_INPUT'
  | 'NO_SETTLED_SETTLEMENT'
  | 'MISSING_SETTLEMENT_PROFIT';

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
  hasPublishedPrediction?: boolean;
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
  sportteryMarket: SportteryMarketVo;
  asianMainMarket: AsianOddsMarketVo | null;
  publishedPredictions: MatchPredictionSummaryVo[];
};

/** 公开比赛列表中一个模型的当前公开预测摘要。 */
export type MatchPredictionSummaryVo = {
  modelVersion: string;
  predictionStatus: PredictionStatus;
  predictionType?: 'SPORTTERY' | 'ASIAN';
  homeWinProb: number | null;
  drawProb: number | null;
  awayWinProb: number | null;
  handicapPick: HandicapPick | null;
  expectedTotalGoals: number | null;
  asianHandicapPick?: AsianHandicapPick | null;
  totalGoalsPick?: TotalGoalsPick | null;
  asianMarket?: PredictionAsianMarketVo | null;
  asianHandicapConfidenceLevel?: ConfidenceLevel | null;
  totalGoalsConfidenceLevel?: ConfidenceLevel | null;
  confidenceLevel: ConfidenceLevel | null;
  /** 亚盘模型概率；为 null 表示这一版没有模型概率，不要按 0 渲染。 */
  asianModel?: PredictionAsianModelVo | null;
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

/** 模型生成时绑定的已确认完整亚盘快照；与列表中的“当前亚盘主盘”可能不同。 */
export type PredictionAsianMarketVo = {
  asianOddsSnapshotId: number;
  providerCode: string;
  bookmakerCode: string;
  handicapLine: number;
  homeOdds: number;
  awayOdds: number;
  totalLine: number;
  overOdds: number;
  underOdds: number;
  snapshotType: OddsSnapshotType;
  capturedAt: string;
  providerUpdatedAt: string | null;
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
  predictionType?: 'SPORTTERY' | 'ASIAN';
  homeWinProb: number | null;
  drawProb: number | null;
  awayWinProb: number | null;
  handicapPick: HandicapPick | null;
  expectedTotalGoals: number | null;
  asianHandicapPick?: AsianHandicapPick | null;
  totalGoalsPick?: TotalGoalsPick | null;
  asianMarket?: PredictionAsianMarketVo | null;
  asianHandicapConfidenceLevel?: ConfidenceLevel | null;
  totalGoalsConfidenceLevel?: ConfidenceLevel | null;
  confidenceLevel: ConfidenceLevel | null;
  analysisSummary: string;
  generatedAt: string;
  publishTime: string;
  lockTime: string;
  predictionHash: string;
  snapshotAvailability: PublicSnapshotAvailability;
  snapshot: PredictionSnapshotVo | null;
  asianModel?: PredictionAsianModelVo | null;
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
  /** 产品线决定可选市场集合；与 settlementMarket 不自洽时后端直接拒绝。 */
  predictionType: PredictionType;
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
  resultSource?: MatchResultFactSource;
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
  /** 该版本按 1 单位本金计算的收益；结算侧尚未回填时为 null。 */
  profit: number | null;
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
  predictionType: PredictionType;
  homeWinProb: number | null;
  drawProb: number | null;
  awayWinProb: number | null;
  handicapPick: HandicapPick | null;
  expectedTotalGoals: number | null;
  asianHandicapPick: AsianHandicapPick | null;
  totalGoalsPick: TotalGoalsPick | null;
  confidenceLevel: ConfidenceLevel | null;
  analysisSummary: string;
  predictionHash: string;
  generatedAt: string;
  publishTime: string | null;
  lockTime: string | null;
  /** 亚盘模型概率；体彩记录与 V24 之前的存量记录为 null。 */
  asianModel: PredictionAsianModelVo | null;
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
  predictionType: PredictionType;
};

export type ProbabilityMetricsVo = {
  sampleSize: number;
  scoringMode: ProbabilityScoringMode;
  brierScore: number | null;
  logLoss: number | null;
  marketBrierScore: number | null;
  brierAdvantage: number | null;
  /** 模型 Brier 低于市场同批样本时为 true；无样本时为 null 而不是 false。 */
  modelOutperformsMarket: boolean | null;
  unavailableReasons: ProbabilityMetricUnavailableReason[];
};

export type MarketHitRateVo = {
  marketType: SettlementMarket;
  /** 已结算样本，含走盘（走盘计 0.5，见 weightedHitRate）。 */
  settledSampleSize: number;
  hitCount: number;
  missCount: number;
  pushCount: number;
  halfWinCount: number;
  halfLossCount: number;
  pendingCount: number;
  voidCount: number;
  /** 命中率，分母不含走盘；半赢半输按命中计。 */
  hitRate: number | null;
  /** 按 1 / 0.75 / 0.5 / 0.25 / 0 连续化后的命中率。 */
  weightedHitRate: number | null;
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
  /** 该类预测实际结算的市场，顺序固定；不再用具名的 had/hhad 字段。 */
  markets: MarketHitRateVo[];
  probabilityMetrics: ProbabilityMetricsVo;
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
