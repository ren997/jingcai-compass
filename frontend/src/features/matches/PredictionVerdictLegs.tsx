import type {
  AsianHandicapPick,
  ConfidenceLevel,
  PredictionAsianMarketVo,
  TotalGoalsPick,
} from '../../services/public';
import {
  asianHandicapVerdictText,
  confidenceLabel,
  formatNumber,
  totalGoalsVerdictText,
} from './matchPresentation';

/**
 * 结论区需要的字段。列表摘要与详情版本用的是两个不同的 VO，但这两组字段同名同义，
 * 用结构类型约束，避免为一个展示块复制两套实现（复制出来的两套迟早在某一页走偏）。
 */
export type VerdictPrediction = {
  asianHandicapPick?: AsianHandicapPick | null;
  totalGoalsPick?: TotalGoalsPick | null;
  asianHandicapConfidenceLevel?: ConfidenceLevel | null;
  totalGoalsConfidenceLevel?: ConfidenceLevel | null;
  asianMarket?: Pick<
    PredictionAsianMarketVo,
    'handicapLine' | 'homeOdds' | 'awayOdds' | 'totalLine' | 'overOdds' | 'underOdds'
  > | null;
};

type PredictionLegProps = {
  market: string;
  verdict: string | null;
  odds: number | null | undefined;
  confidence: ConfidenceLevel | null | undefined;
};

/**
 * 单个市场的结论格。
 *
 * <p>方向与盘口线是这一格里唯一的主角，赔率与置信度降为副行——用户要读的是
 * 「主 -0.5」，不是「亚盘让球：主 -0.5（赔率 1.82）· 置信：中」这样一句话。
 * 没有方向时明确写出来，而不是留空格子：留空会被读成"数据还没同步"。</p>
 */
function PredictionLeg({ market, verdict, odds, confidence }: PredictionLegProps) {
  if (!verdict) {
    return (
      <div className="prediction-leg is-empty">
        <span className="prediction-leg-market">{market}</span>
        <strong className="prediction-leg-verdict">无方向</strong>
        <span className="prediction-leg-meta">未达发布门槛</span>
      </div>
    );
  }
  return (
    <div className="prediction-leg">
      <span className="prediction-leg-market">{market}</span>
      <strong className="prediction-leg-verdict">{verdict}</strong>
      <span className="prediction-leg-meta">
        <span className={`prediction-confidence is-${(confidence ?? 'LOW').toLowerCase()}`}>
          置信{confidenceLabel(confidence)}
        </span>
        <span className="prediction-leg-odds">{formatNumber(odds)}</span>
      </span>
    </div>
  );
}

/** 让球与大小球并排的两格结论，列表页与详情页共用。 */
export function PredictionVerdictLegs({ prediction }: { prediction: VerdictPrediction }) {
  const market = prediction.asianMarket;
  const handicapOdds = prediction.asianHandicapPick === 'HOME_COVER' ? market?.homeOdds : market?.awayOdds;
  const totalsOdds = prediction.totalGoalsPick === 'OVER' ? market?.overOdds : market?.underOdds;
  return (
    <div className="prediction-verdict-legs">
      <PredictionLeg
        market="让球"
        verdict={asianHandicapVerdictText(market?.handicapLine, prediction.asianHandicapPick)}
        odds={handicapOdds}
        confidence={prediction.asianHandicapConfidenceLevel}
      />
      <PredictionLeg
        market="大小球"
        verdict={totalGoalsVerdictText(market?.totalLine, prediction.totalGoalsPick)}
        odds={totalsOdds}
        confidence={prediction.totalGoalsConfidenceLevel}
      />
    </div>
  );
}
