package com.jingcaicompass.backtest.vo;

import com.jingcaicompass.backtest.enums.BacktestDecisionStageEnum;
import com.jingcaicompass.backtest.enums.BacktestResultStatusEnum;
import com.jingcaicompass.prediction.enums.AsianHandicapPickEnum;
import com.jingcaicompass.prediction.enums.BaselinePredictionSkipReasonEnum;
import com.jingcaicompass.prediction.enums.ConfidenceLevelEnum;
import com.jingcaicompass.prediction.enums.TotalGoalsPickEnum;
import com.jingcaicompass.settlement.enums.SettlementStatusEnum;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 一条回测样本：当时的判定、冻结行、诊断量、结算结论。
 *
 * <p>前端要能从「没发」直接看出卡在哪一步，因此 {@code decisionStage} 与 {@code skipReason}
 * 都返回：前者是内部阶段（11 个出口），后者是生产枚举（11 个值），二者不是一一对应——
 * {@code INCONSISTENT_ASIAN_MARKETS} 就对应两个阶段。合并展示会丢掉「残差超限」与「无解」的区别。</p>
 *
 * @param frozenBookmakerCode 决策当时实际被冻结的那家；展示线就是这一行报出的线
 * @param edgeCover           {@code modelCoverProb - marketCoverProb}，首边口径。
 *                            让球方向的这个值恒为 0（λ 由该线概率反解再投影回来），
 *                            属于恒等式、零信息，前端应折叠而不是当成「模型没优势」
 */
public record BacktestCaseVo(
        Long id,
        Long runId,
        String groupKey,
        String externalMatchId,
        Long matchId,
        String lotteryMatchNo,
        String leagueName,
        Instant decisionAt,
        Instant kickoffTime,
        BacktestDecisionStageEnum decisionStage,
        BaselinePredictionSkipReasonEnum skipReason,
        String frozenBookmakerCode,
        BigDecimal frozenHandicapLine,
        BigDecimal frozenHomeOdds,
        BigDecimal frozenAwayOdds,
        BigDecimal frozenTotalLine,
        BigDecimal frozenOverOdds,
        BigDecimal frozenUnderOdds,
        Instant frozenCapturedAt,
        AsianHandicapPickEnum handicapPick,
        ConfidenceLevelEnum handicapConfidence,
        TotalGoalsPickEnum totalsPick,
        ConfidenceLevelEnum totalsConfidence,
        BigDecimal handicapDispersion,
        BigDecimal totalsDispersion,
        BigDecimal lambdaHome,
        BigDecimal lambdaAway,
        BigDecimal modelCoverProb,
        BigDecimal marketCoverProb,
        BigDecimal edgeCover,
        BigDecimal modelOverProb,
        BigDecimal marketOverProb,
        BigDecimal edgeOver,
        BigDecimal neighborMaxDeviation,
        Double maxResidual,
        Boolean handicapGatePass,
        Boolean totalsGatePass,
        BacktestResultStatusEnum resultStatus,
        Integer homeScore,
        Integer awayScore,
        SettlementStatusEnum handicapStatus,
        BigDecimal handicapProfit,
        SettlementStatusEnum totalsStatus,
        BigDecimal totalsProfit,
        Instant settledAt
) {
}
