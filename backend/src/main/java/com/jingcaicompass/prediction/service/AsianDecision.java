package com.jingcaicompass.prediction.service;

import com.jingcaicompass.odds.entity.AsianOddsSnapshot;
import com.jingcaicompass.odds.service.AsianMarketConsensusAggregator;
import com.jingcaicompass.odds.service.AsianPublishGate;
import com.jingcaicompass.odds.service.ImpliedPoissonModel;
import com.jingcaicompass.prediction.enums.AsianHandicapPickEnum;
import com.jingcaicompass.prediction.enums.BaselinePredictionSkipReasonEnum;
import com.jingcaicompass.prediction.enums.ConfidenceLevelEnum;
import com.jingcaicompass.prediction.enums.TotalGoalsPickEnum;
import java.time.Instant;
import java.util.Objects;

/**
 * 单场比赛的完整判定结果：诊断量（共识、λ、邻线校验）+ 两个市场的方向与置信度。
 *
 * <p>把诊断量一并返回，是为了让回测能记录「为什么没发」而不是只知道「没发」。
 * {@code skipReason != null && legs == null} 表示在选冻结行之前就失败了（此时连 λ 都可能没有）；
 * 两个 leg 存在但 pick 为空表示走到了置信度/分歧阶段才被拒——两种情形在排障上完全不同。</p>
 *
 * <p>不变式：{@code skipReason == null} ⇒ 必有冻结行，且至少一个市场有方向。</p>
 *
 * @param featureAsOf 决策依据的最新报价时刻（冻结行的 capturedAt，截到微秒）；早期失败时为 null
 * @param skipReason  非空表示不生成预测；枚举值就是生产 {@code Evaluation.skipped} 用的那一套
 */
public record AsianDecision(
        Long matchId,
        Instant featureAsOf,
        BaselinePredictionSkipReasonEnum skipReason,
        AsianOddsSnapshot frozen,
        AsianMarketConsensusAggregator.LineConsensus handicapConsensus,
        AsianMarketConsensusAggregator.LineConsensus totalsBest,
        AsianMarketConsensusAggregator.LineConsensus totalsAtFrozenLine,
        ImpliedPoissonModel.Solution solution,
        ImpliedPoissonModel.NeighborCheck neighborCheck,
        HandicapLeg handicap,
        TotalsLeg totals
) {

    public AsianDecision {
        Objects.requireNonNull(matchId, "matchId must not be null");
        if (skipReason == null) {
            Objects.requireNonNull(frozen, "decided input must carry a frozen snapshot row");
            boolean hasPick = (handicap != null && handicap.pick() != null)
                    || (totals != null && totals.pick() != null);
            if (!hasPick) {
                throw new IllegalStateException(
                        "decided input must carry at least one market direction, matchId=" + matchId);
            }
        }
        if (frozen != null) {
            Objects.requireNonNull(featureAsOf, "featureAsOf must accompany a frozen snapshot row");
        }
    }

    public boolean decided() {
        return skipReason == null;
    }

    /**
     * 让球市场的判定。
     *
     * <p>{@code pick} 与 {@code confidence} 不是双向绑定：低置信度时 confidence 为 {@code LOW}
     * 而 pick 为空；跨庄分歧否决时两者同时置空。落库口径仍按生产 DTO 的规则
     * {@code pick == null ? null : confidence}，不要直接把 confidence 写库。</p>
     */
    public record HandicapLeg(
            AsianHandicapPickEnum pick,
            ConfidenceLevelEnum confidence,
            boolean dispersionExceeded,
            AsianPublishGate.Verdict gate,
            double coverProbability
    ) {
    }

    /** 大小球市场的判定；{@code overProbability} 恒为大球口径，与让球 leg 的首边口径一致。 */
    public record TotalsLeg(
            TotalGoalsPickEnum pick,
            ConfidenceLevelEnum confidence,
            boolean dispersionExceeded,
            AsianPublishGate.Verdict gate,
            double overProbability
    ) {
    }
}
