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
