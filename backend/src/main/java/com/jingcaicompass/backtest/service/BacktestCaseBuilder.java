package com.jingcaicompass.backtest.service;

import com.jingcaicompass.backtest.dto.BacktestCandidateDto;
import com.jingcaicompass.backtest.entity.BacktestCaseEntity;
import com.jingcaicompass.backtest.enums.BacktestDecisionStageEnum;
import com.jingcaicompass.backtest.enums.BacktestResultStatusEnum;
import com.jingcaicompass.odds.entity.AsianOddsSnapshot;
import com.jingcaicompass.odds.service.AsianMarketConsensusAggregator;
import com.jingcaicompass.odds.service.AsianPublishGate;
import com.jingcaicompass.odds.service.ImpliedPoissonModel;
import com.jingcaicompass.prediction.service.AsianDecision;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * 把一次判定结果落成一行回测样本。
 *
 * <p>纯函数、无依赖：这是回测里最容易被悄悄改错的一环（诊断列写错不会报错，只会让
 * 「为什么没发」的结论偏移），所以它不碰数据库、不碰时钟，便于逐列断言。</p>
 *
 * <h2>三条落库纪律</h2>
 *
 * <p><b>① 概率一律取首边口径</b>：{@code modelCoverProb} / {@code marketCoverProb} 指
 * 「主队赢下冻结让球线」，{@code modelOverProb} / {@code marketOverProb} 指「总进球高于冻结线」。
 * 引擎返回的 {@code coverProbability} / {@code overProbability} 已经是首边（内部用
 * {@code Side.FIRST} 投影），而市场侧取共识的 {@code firstProbability}。**不要把方向换算写在这里**：
 * 落库口径必须是首边，方向换算由消费方按 pick 做 {@code 1 − p}（与线上 V24 十列同一条约定）。</p>
 *
 * <p><b>② {@code pick == null} 时置信度也必须是 null</b>：DB 有
 * {@code ck_backtest_cases_pick} 做同生同灭约束，但真到库里才发现就太晚了——
 * 那一天整批 case 会全部写入失败，而 {@code inserted=0} 不会告诉你是哪一列。</p>
 *
 * <p><b>③ {@code decisionAt} 由调用方给，不在这里取 {@code now()}</b>：
 * 它要进唯一键 {@code (run_id, external_match_id, group_key, decision_at)}，
 * 一旦随时间变化，同一个 run 重跑就会产生重复样本。</p>
 */
public final class BacktestCaseBuilder {

    /** 概率与 σ 的落库精度，与 {@code backtest_cases} 的 numeric(9,6) 一致。 */
    private static final int PROBABILITY_SCALE = 6;

    private BacktestCaseBuilder() {
    }

    /**
     * 组装一行样本。
     *
     * @param runId      所属运行
     * @param groupKey   参数组标识
     * @param candidate  候选场次身份
     * @param decisionAt 决策截面时刻（= 开赛 − leadMinutes）
     * @param archiveId  归档行 id；调用方保证只对归档成功的场次建样本
     * @param decision   引擎结论
     */
    public static BacktestCaseEntity build(
            Long runId,
            String groupKey,
            BacktestCandidateDto candidate,
            Instant decisionAt,
            Long archiveId,
            AsianDecision decision
    ) {
        BacktestCaseEntity entity = new BacktestCaseEntity();
        entity.setRunId(runId);
        entity.setGroupKey(groupKey);
        entity.setExternalMatchId(candidate.externalMatchId());
        entity.setMatchId(candidate.matchId());
        entity.setArchiveId(archiveId);
        entity.setLotteryMatchNo(candidate.lotteryMatchNo());
        entity.setLeagueName(candidate.leagueName());
        entity.setDecisionAt(decisionAt);
        entity.setKickoffTime(candidate.kickoffTime());
        entity.setDecisionStage(BacktestDecisionStageEnum.of(decision));
        entity.setSkipReason(decision.skipReason());
        entity.setResultStatus(BacktestResultStatusEnum.PENDING);

        // 冻结行：判定链在选行之前就失败时为空，整组列随之全空（ck_..._decided 要求）。
        AsianOddsSnapshot frozen = decision.frozen();
        if (frozen != null) {
            entity.setFrozenBookmakerCode(frozen.getBookmakerCode());
            entity.setFrozenHandicapLine(frozen.getHandicapLine());
            entity.setFrozenHomeOdds(frozen.getHomeOdds());
            entity.setFrozenAwayOdds(frozen.getAwayOdds());
            entity.setFrozenTotalLine(frozen.getTotalLine());
            entity.setFrozenOverOdds(frozen.getOverOdds());
            entity.setFrozenUnderOdds(frozen.getUnderOdds());
            entity.setFrozenCapturedAt(frozen.getCapturedAt());
        }

        entity.setHandicapPick(decision.handicap() == null ? null : decision.handicap().pick());
        entity.setHandicapConfidence(decision.handicap() == null || decision.handicap().pick() == null
                ? null
                : decision.handicap().confidence());
        entity.setTotalsPick(decision.totals() == null ? null : decision.totals().pick());
        entity.setTotalsConfidence(decision.totals() == null || decision.totals().pick() == null
                ? null
                : decision.totals().confidence());

        applyDiagnostics(entity, decision);
        return entity;
    }

    private static void applyDiagnostics(BacktestCaseEntity entity, AsianDecision decision) {
        AsianMarketConsensusAggregator.LineConsensus handicapConsensus = decision.handicapConsensus();
        // 大小球的市场概率必须取「冻结行所在线」的共识，不是最平衡线的共识：
        // 两者在冻结行大小球线不等于主盘线时不同，而 case 记的是「冻结那一刻的行情」。
        AsianMarketConsensusAggregator.LineConsensus totalsConsensus =
                decision.totalsAtFrozenLine() != null ? decision.totalsAtFrozenLine() : decision.totalsBest();

        entity.setHandicapDispersion(handicapConsensus == null ? null : handicapConsensus.dispersion());
        entity.setTotalsDispersion(totalsConsensus == null ? null : totalsConsensus.dispersion());

        ImpliedPoissonModel.Solution solution = decision.solution();
        if (solution != null) {
            entity.setLambdaHome(decimal(solution.lambdaHome()));
            entity.setLambdaAway(decimal(solution.lambdaAway()));
            // 残差列在表上是 double precision：它只用于「二分是否收敛」的诊断，不参与金额运算。
            entity.setMaxResidual(solution.maxResidual());
        }

        ImpliedPoissonModel.NeighborCheck neighborCheck = decision.neighborCheck();
        entity.setNeighborMaxDeviation(neighborCheck == null ? null : decimal(neighborCheck.maxDeviation()));

        // 模型侧概率无条件写：低置信只表示不发布方向，不代表模型认为概率为 0。
        // 写 0 会让 Brier 把「没方向」当成「强烈看空」，模型表现被系统性地压低。
        if (decision.handicap() != null && handicapConsensus != null) {
            BigDecimal model = decimal(decision.handicap().coverProbability());
            BigDecimal market = handicapConsensus.firstProbability();
            entity.setModelCoverProb(model);
            entity.setMarketCoverProb(market);
            entity.setEdgeCover(model == null ? null : model.subtract(market));
        }
        if (decision.totals() != null && totalsConsensus != null) {
            BigDecimal model = decimal(decision.totals().overProbability());
            BigDecimal market = totalsConsensus.firstProbability();
            entity.setModelOverProb(model);
            entity.setMarketOverProb(market);
            entity.setEdgeOver(model == null ? null : model.subtract(market));
        }

        entity.setHandicapGatePass(gatePass(decision.handicap() == null ? null : decision.handicap().gate()));
        entity.setTotalsGatePass(gatePass(decision.totals() == null ? null : decision.totals().gate()));
    }

    private static Boolean gatePass(AsianPublishGate.Verdict verdict) {
        return verdict == null ? null : verdict.wouldPublish();
    }

    private static BigDecimal decimal(double value) {
        if (!Double.isFinite(value)) {
            return null;
        }
        return BigDecimal.valueOf(value).setScale(PROBABILITY_SCALE, RoundingMode.HALF_UP);
    }
}
