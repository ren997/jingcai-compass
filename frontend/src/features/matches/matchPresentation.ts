import type {
  ConfidenceLevel,
  HandicapPick,
  AsianHandicapPick,
  MatchDataAvailability,
  MatchStatus,
  OddsSnapshotType,
  PredictionAsianModelVo,
  PredictionStatus,
  PublicSnapshotAvailability,
  TotalGoalsPick,
} from '../../services/public';

export const statusLabels: Record<MatchStatus, string> = {
  SCHEDULED: '未开赛',
  LOCKED: '已锁定',
  IN_PROGRESS: '进行中',
  FINISHED: '已结束',
  POSTPONED: '已延期',
  CANCELLED: '已取消',
  ABANDONED: '已中止',
};

const availabilityLabels: Record<MatchDataAvailability, string> = {
  AVAILABLE: '数据可用',
  NO_SPORTTERY_SNAPSHOT: '暂无体彩快照',
  NO_ASIAN_ODDS_SNAPSHOT: '暂无亚盘快照',
  NO_SOURCE_MAPPING: '暂无来源映射',
  MAPPING_UNCONFIRMED: '映射待确认',
};

const snapshotTypeLabels: Record<OddsSnapshotType, string> = {
  FIRST_SEEN: '首次可见',
  PRE_KICKOFF: '封盘前',
  OTHER: '其他',
};

const predictionStatusLabels: Record<PredictionStatus, string> = {
  PUBLISHED: '已发布',
  LOCKED: '已锁定',
};

const handicapPickLabels: Record<HandicapPick, string> = {
  HOME_WIN: '让球胜',
  DRAW: '让球平',
  AWAY_WIN: '让球负',
};

const asianHandicapPickLabels: Record<AsianHandicapPick, string> = {
  HOME_COVER: '主队赢盘',
  AWAY_COVER: '客队赢盘',
};

const totalGoalsPickLabels: Record<TotalGoalsPick, string> = {
  OVER: '大球',
  UNDER: '小球',
};

const confidenceLabels: Record<ConfidenceLevel, string> = {
  LOW: '低',
  MEDIUM: '中',
  HIGH: '高',
};

const publicSnapshotAvailabilityLabels: Record<PublicSnapshotAvailability, string> = {
  AVAILABLE: '快照可校验',
  UNAVAILABLE: '暂无可验证快照',
};

export function formatTimestamp(value: string | null | undefined) {
  if (!value) {
    return '暂无时间';
  }
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(new Date(value));
}

export function formatHandicap(value: number | null | undefined) {
  if (value === null || value === undefined) {
    return '让球暂缺';
  }
  return value === 0 ? '让球 0' : `主队 ${value > 0 ? '+' : ''}${value}`;
}

export function formatNumber(value: number | null | undefined) {
  return value === null || value === undefined ? '—' : String(value);
}

export function formatProbability(value: number | null | undefined) {
  if (value === null || value === undefined) {
    return '—';
  }
  return `${(value * 100).toFixed(1)}%`;
}

export function availabilityLabel(value: MatchDataAvailability) {
  return availabilityLabels[value];
}

export function snapshotTypeLabel(value: OddsSnapshotType) {
  return snapshotTypeLabels[value];
}

export function predictionStatusLabel(value: PredictionStatus) {
  return predictionStatusLabels[value];
}

export function handicapPickLabel(value: HandicapPick) {
  return handicapPickLabels[value];
}

/** 体彩让球胜平负必须同时展示官方让球，避免被误读为普通胜平负。 */
export function sportteryHandicapPredictionLabel(
  officialHandicap: number | null | undefined,
  pick: HandicapPick | null | undefined,
) {
  return `竞彩让球胜平负（${formatHandicap(officialHandicap)}）：${pick ? handicapPickLabel(pick) : '暂缺'}`;
}

/** 亚盘方向必须同时展示该方向绑定的亚洲让球线。 */
export function asianHandicapPredictionLabel(
  handicapLine: number | null | undefined,
  pick: AsianHandicapPick | null | undefined,
) {
  const lineLabel = handicapLine === 0 ? '平手' : formatHandicap(handicapLine);
  return `亚盘让球（${lineLabel}）：${asianHandicapPickLabel(pick)}`;
}

/** 将亚盘让球方向还原为主队或客队视角的具体盘口及该方向水位。 */
export function asianHandicapPredictionMarketLabel(
  handicapLine: number | null | undefined,
  pick: AsianHandicapPick | null | undefined,
  homeOdds: number | null | undefined,
  awayOdds: number | null | undefined,
) {
  if (!pick) {
    return '盘口暂缺';
  }
  const homePick = pick === 'HOME_COVER';
  const line = handicapLine === null || handicapLine === undefined
    ? '让球暂缺'
    : formatAsianLine(homePick ? handicapLine : -handicapLine);
  return `${homePick ? '主' : '客'} ${line}（赔率 ${formatNumber(homePick ? homeOdds : awayOdds)}）`;
}

export function asianHandicapPickLabel(value: AsianHandicapPick | null | undefined) {
  return value ? asianHandicapPickLabels[value] : '暂缺';
}

export function totalGoalsPickLabel(value: TotalGoalsPick | null | undefined) {
  return value ? totalGoalsPickLabels[value] : '暂缺';
}

/** 将亚盘大小球方向还原为具体盘口及该方向水位。 */
export function totalGoalsPredictionMarketLabel(
  totalLine: number | null | undefined,
  pick: TotalGoalsPick | null | undefined,
  overOdds: number | null | undefined,
  underOdds: number | null | undefined,
) {
  if (!pick) {
    return '盘口暂缺';
  }
  return `${pick === 'OVER' ? '大' : '小'} ${formatNumber(totalLine)}（赔率 ${formatNumber(
    pick === 'OVER' ? overOdds : underOdds,
  )}）`;
}

export function confidenceLabel(value: ConfidenceLevel | null | undefined) {
  return value ? confidenceLabels[value] : '暂缺';
}

export function publicSnapshotAvailabilityLabel(value: PublicSnapshotAvailability) {
  return publicSnapshotAvailabilityLabels[value];
}

export function dataSourceLabel(value: string | null | undefined) {
  if (!value) {
    return '来源暂缺';
  }
  if (value === 'CHINA_SPORTTERY') {
    return '中国体彩网公开数据';
  }
  if (value === 'STUB') {
    return 'Stub 演示数据';
  }
  return value;
}

/** 亚盘让球线的带符号写法：-0.5 / +1 / 0。 */
export function formatAsianLine(value: number | null | undefined) {
  if (value === null || value === undefined) {
    return '—';
  }
  return `${value > 0 ? '+' : ''}${value}`;
}

/**
 * 让球方向的一句话结论：主 -0.5 / 客 +1.5 / 主 平手。
 *
 * <p>落库的 handicapLine 是主队让球数，客队视角要取反；平手盘不写 0 而写"平手"，
 * 因为"主 0"会被读成主队受让 0 球这种不存在的盘口。</p>
 */
export function asianHandicapVerdictText(
  handicapLine: number | null | undefined,
  pick: AsianHandicapPick | null | undefined,
) {
  if (!pick) {
    return null;
  }
  const side = pick === 'HOME_COVER' ? '主' : '客';
  if (handicapLine === null || handicapLine === undefined) {
    return side;
  }
  const line = pick === 'HOME_COVER' ? handicapLine : -handicapLine;
  return `${side} ${line === 0 ? '平手' : formatAsianLine(line)}`;
}

/** 大小球方向的一句话结论：大 2.5 / 小 3.25。 */
export function totalGoalsVerdictText(
  totalLine: number | null | undefined,
  pick: TotalGoalsPick | null | undefined,
) {
  if (!pick) {
    return null;
  }
  const side = pick === 'OVER' ? '大' : '小';
  return totalLine === null || totalLine === undefined ? side : `${side} ${totalLine}`;
}

/** 亚盘模型十列所在的市场，决定取哪一组概率。 */
export type AsianModelMarket = 'HANDICAP' | 'TOTALS';

function firstSideProbability(
  model: PredictionAsianModelVo | null | undefined,
  market: AsianModelMarket,
) {
  if (!model) {
    return null;
  }
  return market === 'HANDICAP' ? model.modelCoverProb : model.modelOverProb;
}

function marketProbability(
  model: PredictionAsianModelVo | null | undefined,
  market: AsianModelMarket,
) {
  if (!model) {
    return null;
  }
  return market === 'HANDICAP' ? model.marketCoverProb : model.marketOverProb;
}

/** 所选方向是否为十列的首边（主队赢盘 / 大球）。 */
function picksFirstSide(
  pick: AsianHandicapPick | TotalGoalsPick | null | undefined,
  market: AsianModelMarket,
) {
  return market === 'HANDICAP' ? pick === 'HOME_COVER' : pick === 'OVER';
}

/**
 * 所选方向的模型概率。
 *
 * <p>后端十列一律是首边口径（主队赢盘 / 大球）。换算成"押这边的概率"只发生在展示层：
 * 它跟这一场押了哪边绑定，落在服务端会让同一份 JSON 在不同页面语义不同。
 */
export function pickedProbability(
  model: PredictionAsianModelVo | null | undefined,
  pick: AsianHandicapPick | TotalGoalsPick | null | undefined,
  market: AsianModelMarket,
) {
  const firstSide = firstSideProbability(model, market);
  if (firstSide === null || !pick) {
    return null;
  }
  return picksFirstSide(pick, market) ? firstSide : 1 - firstSide;
}

/** 模型与市场在所选方向上的概率对照，是"模型凭什么和盘口不一样"的唯一依据。 */
export function modelMarketProbabilityText(
  model: PredictionAsianModelVo | null | undefined,
  pick: AsianHandicapPick | TotalGoalsPick | null | undefined,
  market: AsianModelMarket,
) {
  const modelProb = pickedProbability(model, pick, market);
  const firstSide = marketProbability(model, market);
  const marketProb = firstSide === null || !pick
    ? null
    : picksFirstSide(pick, market)
      ? firstSide
      : 1 - firstSide;
  if (modelProb === null && marketProb === null) {
    return null;
  }
  return `模型 ${formatProbability(modelProb)} · 市场 ${formatProbability(marketProb)}`;
}

/**
 * 所选方向的 edge。
 *
 * <p>落库的 edge 是首边口径差值，换到另一边时两个概率同时取补，差值正好反号。
 * 直接把首边 edge 显示在"客队受让"旁边会让人读成相反结论。
 */
export function edgeText(
  model: PredictionAsianModelVo | null | undefined,
  pick: AsianHandicapPick | TotalGoalsPick | null | undefined,
  market: AsianModelMarket,
) {
  if (!model || !pick) {
    return null;
  }
  const raw = market === 'HANDICAP' ? model.edgeCover : model.edgeOver;
  if (raw === null || raw === undefined) {
    return null;
  }
  const value = picksFirstSide(pick, market) ? raw : -raw;
  return `edge ${value > 0 ? '+' : ''}${(value * 100).toFixed(1)}%`;
}

/** 模型反解出的期望进球（λ 主 / λ 客），是让球与大小球两个投影的共同来源。 */
export function lambdaText(model: PredictionAsianModelVo | null | undefined) {
  if (!model || model.lambdaHome === null || model.lambdaAway === null) {
    return null;
  }
  return `λ 主 ${model.lambdaHome.toFixed(2)} / 客 ${model.lambdaAway.toFixed(2)}`;
}

/** 跨庄去水概率的总体标准差，衡量"市场自己是否自相矛盾"。 */
export function dispersionText(model: PredictionAsianModelVo | null | undefined) {
  if (!model) {
    return null;
  }
  const parts: string[] = [];
  if (model.handicapDispersion !== null) {
    parts.push(`让球 ${model.handicapDispersion.toFixed(4)}`);
  }
  if (model.totalsDispersion !== null) {
    parts.push(`大小球 ${model.totalsDispersion.toFixed(4)}`);
  }
  return parts.length === 0 ? null : `σ ${parts.join(' / ')}`;
}
