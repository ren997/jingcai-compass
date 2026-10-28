package com.jingcaicompass.prediction.service;

import com.jingcaicompass.match.enums.MatchStatusEnum;
import com.jingcaicompass.odds.client.AsianModelProperties;
import com.jingcaicompass.odds.client.AsianOddsAggregationProperties;
import com.jingcaicompass.odds.entity.AsianOddsSnapshot;
import com.jingcaicompass.odds.service.AsianFreezeSnapshotSelector;
import com.jingcaicompass.odds.service.AsianMarketConsensusAggregator;
import com.jingcaicompass.odds.service.AsianMarketMath;
import com.jingcaicompass.odds.service.AsianPublishGate;
import com.jingcaicompass.odds.service.ImpliedPoissonModel;
import com.jingcaicompass.prediction.enums.AsianHandicapPickEnum;
import com.jingcaicompass.prediction.enums.BaselinePredictionSkipReasonEnum;
import com.jingcaicompass.prediction.enums.ConfidenceLevelEnum;
import com.jingcaicompass.prediction.enums.TotalGoalsPickEnum;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * 亚盘决策核心：从「一组快照行」走到「两个市场的方向与置信度」。
 *
 * <p>抽出来的唯一目的是**让历史回测与生产共用同一份实现**。原型 {@code HistoryReplay} 自己重排了
 * 这套步骤，漏掉了残差与邻线校验（生产会因此整场跳过），结论系统性偏乐观；它还按 5 键复刻冻结行
 * 排序，而生产是 6 键，第一键「对齐大小球主盘」正是 2026-10-22「13 场全小球」事故的根因。</p>
 *
 * <p>因此这个类和 {@code BaselinePredictionGenerationServiceImpl} 的关系是**搬移**而非重写：
 * 13 个出口的顺序、指标记录的插入位置、每个阈值取自哪个字段，都与搬移前逐行对应。任何一行
 * 顺序变动都会改变「先报哪个跳过原因」，进而改变管理端归因。</p>
 *
 * <p>零 Spring 依赖：{@code MeterRegistry} 是 Micrometer 的接口，可空。生产传真实实例，回测传
 * {@code null}（此时所有指标调用被跳过）。不能用 {@code JobMetrics.noop()} 替代——那些 record
 * 方法原先直连 registry 且没有 null 判断。</p>
 */
public final class AsianDecisionEngine {

    /** 判定逻辑的版本号；回测每次 run 都要记录，否则换了解法无法归因。 */
    public static final String ENGINE_VERSION = "asian-decision-v1";

    static final String CONSENSUS_METRIC = "jingcai.prediction.asian.consensus";
    static final String MODEL_METRIC = "jingcai.prediction.asian.model";
    static final String DISPERSION_METRIC = "jingcai.prediction.asian.dispersion";
    static final String GATE_METRIC = "jingcai.prediction.asian.gate";

    private final AsianMarketConsensusAggregator consensusAggregator;
    private final AsianFreezeSnapshotSelector freezeSnapshotSelector;
    private final ImpliedPoissonModel poissonModel;
    private final AsianPublishGate publishGate;
    private final AsianConfidenceClassifier confidenceClassifier;
    private final BigDecimal neighborTolerance;
    private final BigDecimal residualTolerance;
    private final MeterRegistry meterRegistry;

    public AsianDecisionEngine(
            AsianOddsAggregationProperties aggregationProperties,
            AsianModelProperties modelProperties,
            MeterRegistry meterRegistry
    ) {
        AsianOddsAggregationProperties effectiveAggregation =
                aggregationProperties == null ? AsianOddsAggregationProperties.defaults() : aggregationProperties;
        AsianModelProperties effectiveModel =
                modelProperties == null ? AsianModelProperties.defaults() : modelProperties;
        this.consensusAggregator = new AsianMarketConsensusAggregator(effectiveAggregation);
        this.freezeSnapshotSelector = new AsianFreezeSnapshotSelector(effectiveAggregation);
        this.poissonModel = new ImpliedPoissonModel(effectiveModel);
        this.publishGate = new AsianPublishGate(effectiveModel);
        this.confidenceClassifier = new AsianConfidenceClassifier(effectiveModel);
        this.neighborTolerance = effectiveModel.neighborTolerance();
        this.residualTolerance = effectiveModel.residualTolerance();
        this.meterRegistry = meterRegistry;
    }

    public AsianDecision decide(AsianDecisionInput input) {
        // 1) 状态与时间闸门。已开赛的场次必须走 KICKOFF_PASSED 计数，不能抛异常。
        if (input.matchStatus() != MatchStatusEnum.SCHEDULED) {
            return skipped(input, BaselinePredictionSkipReasonEnum.NOT_SCHEDULED);
        }
        if (input.kickoffTime() == null || !input.kickoffTime().isAfter(input.decisionAt())) {
            return skipped(input, BaselinePredictionSkipReasonEnum.KICKOFF_PASSED);
        }

        // 2) 让球行必须存在且至少一行可用。capturedAt 也参与判定：没有时刻就无法确定它在赛前。
        List<AsianOddsSnapshot> rows = input.handicapRows();
        if (rows.isEmpty()) {
            return skipped(input, BaselinePredictionSkipReasonEnum.MISSING_CONFIRMED_ASIAN_MARKET);
        }
        boolean anyUsablePrice = rows.stream().anyMatch(row ->
                AsianMarketMath.isUsablePrice(row.getHomeOdds())
                        && AsianMarketMath.isUsablePrice(row.getAwayOdds())
                        && row.getCapturedAt() != null);
        if (!anyUsablePrice) {
            return skipped(input, BaselinePredictionSkipReasonEnum.INVALID_ASIAN_MARKET);
        }

        // 3) 让球共识主盘。
        List<AsianMarketConsensusAggregator.Quote> handicapQuotes = rows.stream()
                .map(AsianDecisionEngine::handicapQuote)
                .toList();
        AsianMarketConsensusAggregator.MarketAggregate handicapAggregate =
                consensusAggregator.aggregate(handicapQuotes);
        if (handicapAggregate.isEmpty()) {
            recordConsensus("handicap", null);
            return skipped(input, BaselinePredictionSkipReasonEnum.INSUFFICIENT_ASIAN_BOOKMAKERS);
        }
        AsianMarketConsensusAggregator.LineConsensus handicapConsensus =
                handicapAggregate.best().orElseThrow();
        recordConsensus("handicap", handicapConsensus);

        // 4) 大小球主盘是第二个约束。缺了就是「一个方程两个未知数」，必须早于冻结行选择报出，
        //    否则排障会误以为是「共识线上没人报大小球」。
        AsianMarketConsensusAggregator.MarketAggregate totalsAggregate =
                consensusAggregator.aggregate(input.totalsQuotes());
        AsianMarketConsensusAggregator.LineConsensus totalsBest = totalsAggregate.best().orElse(null);
        recordConsensus("totals", totalsBest);
        if (totalsBest == null) {
            recordModel("no-constraint", null, null);
            return skipped(input, BaselinePredictionSkipReasonEnum.INSUFFICIENT_ASIAN_CONSTRAINTS);
        }

        // 5) 反解 λ。
        ImpliedPoissonModel.Solution solution = poissonModel.solve(
                handicapConsensus.line(),
                handicapConsensus.firstProbability().doubleValue(),
                totalsBest.line(),
                totalsBest.firstProbability().doubleValue());
        if (!solution.feasible()) {
            recordModel("infeasible", solution, null);
            // 带上 solution 才能让下游区分「搜索域太窄」与「市场打架」。
            return skippedWithDiagnostics(
                    input, BaselinePredictionSkipReasonEnum.INCONSISTENT_ASIAN_MARKETS, solution, null);
        }

        // 6) 邻线回投校验。主盘两方程是恰定的，残差只反映收敛精度；判断两个市场是否打架
        //    只能靠邻线复现能力。这两个出口共用同一个枚举值，但指标 outcome 不同，不能合并。
        ImpliedPoissonModel.NeighborCheck neighborCheck = poissonModel.check(
                solution.lambdaHome(),
                solution.lambdaAway(),
                handicapAggregate.ranked(),
                handicapConsensus.line(),
                totalsAggregate.ranked(),
                totalsBest.line());
        if (BigDecimal.valueOf(solution.maxResidual()).compareTo(residualTolerance) > 0
                || !neighborCheck.within(neighborTolerance)) {
            recordModel("inconsistent", solution, neighborCheck);
            // 同样带出去：这一支是「反解可行但邻线复现不了」，与上一支的修法相反
            // （放宽搜索域 vs 收紧邻线容差），不能在下游被合并成一个「无解」。
            return skippedWithDiagnostics(
                    input, BaselinePredictionSkipReasonEnum.INCONSISTENT_ASIAN_MARKETS, solution, neighborCheck);
        }
        // lineCount == 0 时单独打标签：没有邻线不等于校验通过，不能让它伪装成「完美自洽」。
        recordModel(neighborCheck.validated() ? "solved" : "no-neighbor", solution, neighborCheck);

        // 7) 冻结行。必须是真实报出共识线的那一行——公平价无法成交，也无法结算。
        Optional<AsianOddsSnapshot> frozenRow = freezeSnapshotSelector.select(
                input.freezeRows(), handicapConsensus, Optional.of(totalsBest));
        if (frozenRow.isEmpty()) {
            return skipped(input, BaselinePredictionSkipReasonEnum.NO_ASIAN_SNAPSHOT_ON_CONSENSUS_LINE);
        }
        AsianOddsSnapshot frozen = frozenRow.get();
        // 判定线必须就是大小球主盘线：λ 由主盘去水概率反解，两条线不同一时模型概率不再复现市场，
        // edge 会凭空出现（那是形状偏差不是 alpha），结算还会按被抬高的线判定。
        if (frozen.getTotalLine() == null
                || AsianMarketMath.normalizeLine(frozen.getTotalLine())
                        .compareTo(AsianMarketMath.normalizeLine(totalsBest.line())) != 0) {
            return skipped(input, BaselinePredictionSkipReasonEnum.NO_ASIAN_SNAPSHOT_ON_TOTALS_CONSENSUS_LINE);
        }
        AsianMarketConsensusAggregator.LineConsensus totalsAtFrozenLine =
                totalsAggregate.atLine(frozen.getTotalLine()).orElse(null);
        recordConsensus("totals-at-frozen-line", totalsAtFrozenLine);

        // 8) 两个市场分别定方向。概率必须无条件投影：低置信只意味着不发布方向，不代表模型认为
        //    概率为 0；落 0 会让 Brier 把「没方向」当成「强烈看空」。
        double coverProbability = poissonModel.project(
                solution.lambdaHome(), solution.lambdaAway(), frozen.getHandicapLine(),
                ImpliedPoissonModel.Market.HANDICAP, ImpliedPoissonModel.Side.FIRST);
        ConfidenceLevelEnum handicapConfidence =
                confidenceClassifier.classify(frozen.getHomeOdds(), frozen.getAwayOdds());
        AsianHandicapPickEnum handicapPick = handicapConfidence == ConfidenceLevelEnum.LOW
                ? null
                : (favorsFirstSide(coverProbability, frozen.getHomeOdds(), frozen.getAwayOdds())
                        ? AsianHandicapPickEnum.HOME_COVER
                        : AsianHandicapPickEnum.AWAY_COVER);
        boolean handicapDispersionExceeded = publishGate.exceedsDispersion(handicapConsensus.dispersion());
        recordDispersion("handicap", handicapConsensus, handicapDispersionExceeded);
        if (handicapDispersionExceeded) {
            // 方向与置信度必须成对置空：导入校验要求「无方向 ⇒ 无置信度」，只清方向会让整批回滚。
            handicapPick = null;
            handicapConfidence = null;
        }

        double overProbability = poissonModel.project(
                solution.lambdaHome(), solution.lambdaAway(), frozen.getTotalLine(),
                ImpliedPoissonModel.Market.TOTALS, ImpliedPoissonModel.Side.FIRST);
        ConfidenceLevelEnum totalsConfidence =
                confidenceClassifier.classify(frozen.getOverOdds(), frozen.getUnderOdds());
        TotalGoalsPickEnum totalsPick = totalsConfidence == ConfidenceLevelEnum.LOW
                ? null
                : (favorsFirstSide(overProbability, frozen.getOverOdds(), frozen.getUnderOdds())
                        ? TotalGoalsPickEnum.OVER
                        : TotalGoalsPickEnum.UNDER);
        boolean totalsDispersionExceeded =
                publishGate.exceedsDispersion(totalsAtFrozenLine.dispersion());
        recordDispersion("totals", totalsAtFrozenLine, totalsDispersionExceeded);
        if (totalsDispersionExceeded) {
            totalsPick = null;
            totalsConfidence = null;
        }

        // 9) 影子门槛：只对仍存在的方向求值，只记录不拦截（让球方向 edge 恒为 0，见 AsianPublishGate）。
        AsianPublishGate.Verdict handicapGate = null;
        if (handicapPick != null) {
            handicapGate = publishGate.evaluate(
                    ImpliedPoissonModel.Market.HANDICAP,
                    handicapPick == AsianHandicapPickEnum.HOME_COVER
                            ? ImpliedPoissonModel.Side.FIRST
                            : ImpliedPoissonModel.Side.SECOND,
                    coverProbability,
                    handicapConsensus.firstProbability(),
                    frozen.getHomeOdds(),
                    frozen.getAwayOdds());
            recordGate("handicap", handicapGate);
        }
        AsianPublishGate.Verdict totalsGate = null;
        if (totalsPick != null) {
            totalsGate = publishGate.evaluate(
                    ImpliedPoissonModel.Market.TOTALS,
                    totalsPick == TotalGoalsPickEnum.OVER
                            ? ImpliedPoissonModel.Side.FIRST
                            : ImpliedPoissonModel.Side.SECOND,
                    overProbability,
                    totalsAtFrozenLine.firstProbability(),
                    frozen.getOverOdds(),
                    frozen.getUnderOdds());
            recordGate("totals", totalsGate);
        }

        AsianDecision.HandicapLeg handicapLeg = new AsianDecision.HandicapLeg(
                handicapPick, handicapConfidence, handicapDispersionExceeded, handicapGate, coverProbability);
        AsianDecision.TotalsLeg totalsLeg = new AsianDecision.TotalsLeg(
                totalsPick, totalsConfidence, totalsDispersionExceeded, totalsGate, overProbability);

        BaselinePredictionSkipReasonEnum skipReason = null;
        if (handicapPick == null && totalsPick == null) {
            // 只有两个市场都输在分歧上才归因于 σ；混合情形一边低置信、一边分歧超限，仍记低置信，
            // 否则低置信场次会被误算成阈值问题，调参失去依据。
            boolean allRejectedByDispersion = handicapDispersionExceeded && totalsDispersionExceeded;
            skipReason = allRejectedByDispersion
                    ? BaselinePredictionSkipReasonEnum.MARKET_DISPERSION_TOO_HIGH
                    : BaselinePredictionSkipReasonEnum.LOW_CONFIDENCE_ASIAN_MARKETS;
        }

        return new AsianDecision(
                input.matchId(),
                frozen.getCapturedAt().truncatedTo(ChronoUnit.MICROS),
                skipReason,
                frozen,
                handicapConsensus,
                totalsBest,
                totalsAtFrozenLine,
                solution,
                neighborCheck,
                handicapLeg,
                totalsLeg);
    }

    private static AsianMarketConsensusAggregator.Quote handicapQuote(AsianOddsSnapshot row) {
        return new AsianMarketConsensusAggregator.Quote(
                row.getProviderCode(),
                row.getBookmakerCode(),
                row.getHandicapLine(),
                row.getHomeOdds(),
                row.getAwayOdds(),
                row.getCapturedAt(),
                row.getId()
        );
    }

    /**
     * 方向取自模型概率在冻结行上的投影；恰好 50/50 时回退到冻结行自身的水位高低。
     *
     * <p>不能用冻结行水位直接定方向：那是单家观点，会把「押低水方」的老逻辑带回来。</p>
     */
    private static boolean favorsFirstSide(double modelProbability, BigDecimal firstPrice, BigDecimal secondPrice) {
        double comparison = modelProbability - 0.5d;
        if (comparison > 0) {
            return true;
        }
        if (comparison < 0) {
            return false;
        }
        return firstPrice.compareTo(secondPrice) <= 0;
    }

    private static AsianDecision skipped(AsianDecisionInput input, BaselinePredictionSkipReasonEnum reason) {
        return new AsianDecision(
                input.matchId(), null, reason, null, null, null, null, null, null, null, null);
    }

    /**
     * 带诊断量的跳过。
     *
     * <p>{@code INCONSISTENT_ASIAN_MARKETS} 一个枚举值挂着两个出口：λ 反解无解、
     * 反解可行但残差/邻线超限。区分它们的信息<b>只</b>存在于 {@code solution} 与
     * {@code neighborCheck} 里——早期版本在跳过时把它们一起置空，于是回测拿到的
     * {@code AsianDecision} 看起来永远是「无解」：{@code decision_stage} 只能判成
     * INFEASIBLE，四种不可行原因也无从落库。</p>
     *
     * <p>注意不可行时 {@code solution.maxResidual()} 是无穷大、λ 是 0 占位，
     * 消费方必须先用 {@code feasible()} 判断再取值。</p>
     */
    private static AsianDecision skippedWithDiagnostics(
            AsianDecisionInput input,
            BaselinePredictionSkipReasonEnum reason,
            ImpliedPoissonModel.Solution solution,
            ImpliedPoissonModel.NeighborCheck neighborCheck
    ) {
        return new AsianDecision(
                input.matchId(), null, reason, null, null, null, null, solution, neighborCheck, null, null);
    }

    private void recordConsensus(String market, AsianMarketConsensusAggregator.LineConsensus consensus) {
        if (meterRegistry == null) {
            return;
        }
        String source = consensus == null
                ? "none"
                : (consensus.whitelistApplied() ? "whitelist" : "fallback");
        String outcome = consensus == null ? "insufficient" : "ok";
        Counter.builder(CONSENSUS_METRIC)
                .tags("market", market, "source", source, "outcome", outcome)
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录反解结果的分布。
     *
     * <p>{@code outcome} 把「没有邻线可校验」与「校验通过」分开：前者不代表自洽，若混为一谈，
     * 邻线校验静默失效时监控上看不出任何异常。</p>
     */
    private void recordModel(
            String outcome,
            ImpliedPoissonModel.Solution solution,
            ImpliedPoissonModel.NeighborCheck neighborCheck
    ) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder(MODEL_METRIC)
                .tags("outcome", outcome).register(meterRegistry).increment();
        if (solution != null && solution.feasible()) {
            recordLambda("home", solution.lambdaHome());
            recordLambda("away", solution.lambdaAway());
        }
        if (neighborCheck != null && neighborCheck.validated()) {
            DistributionSummary.builder(MODEL_METRIC + ".neighbor-deviation")
                    .register(meterRegistry).record(neighborCheck.maxDeviation());
        }
    }

    private void recordLambda(String side, double lambda) {
        DistributionSummary.builder(MODEL_METRIC + ".lambda")
                .tag("side", side).register(meterRegistry).record(lambda);
    }

    /**
     * 记录跨庄分歧与它的判定结果。
     *
     * <p>{@code source} 必须带上：白名单子集的 σ 与全量回退的 σ 量纲不同源（样本常从十几家掉到
     * 两三家，σ 会系统性偏小），合并统计会把阈值调到两边都不合适。</p>
     */
    private void recordDispersion(
            String market,
            AsianMarketConsensusAggregator.LineConsensus consensus,
            boolean exceeded
    ) {
        if (meterRegistry == null || consensus == null || consensus.dispersion() == null) {
            return;
        }
        String source = consensus.whitelistApplied() ? "whitelist" : "fallback";
        Counter.builder(DISPERSION_METRIC)
                .tags("market", market, "outcome", exceeded ? "exceeded" : "within", "source", source)
                .register(meterRegistry)
                .increment();
        DistributionSummary.builder(DISPERSION_METRIC + ".sigma")
                .tags("market", market, "source", source)
                .register(meterRegistry)
                .record(consensus.dispersion().doubleValue());
    }

    /**
     * 记录影子门槛的结果。
     *
     * <p>分母只含「仍有方向、当前会真的发布出去」的市场，只有这样才能回答「若把影子门槛切成
     * 真门槛，会砍掉现在发布的多少」。</p>
     */
    private void recordGate(String market, AsianPublishGate.Verdict verdict) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder(GATE_METRIC)
                .tags("market", market, "outcome", verdict.wouldPublish() ? "pass" : "fail")
                .register(meterRegistry)
                .increment();
        DistributionSummary.builder(GATE_METRIC + ".edge")
                .tag("market", market).register(meterRegistry).record(verdict.edge().doubleValue());
        DistributionSummary.builder(GATE_METRIC + ".margin")
                .tag("market", market).register(meterRegistry).record(verdict.margin().doubleValue());
    }
}
