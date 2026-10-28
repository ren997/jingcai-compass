package com.jingcaicompass.backtest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.backtest.entity.BacktestCaseEntity;
import com.jingcaicompass.backtest.entity.BacktestMetricEntity;
import com.jingcaicompass.backtest.enums.BacktestMarketScopeEnum;
import com.jingcaicompass.backtest.mapper.BacktestMetricMapper;
import com.jingcaicompass.backtest.vo.BacktestComparisonVo;
import com.jingcaicompass.backtest.vo.BacktestMetricsVo;
import com.jingcaicompass.history.vo.HistoryListItemVo;
import com.jingcaicompass.history.vo.HistoryMatchVo;
import com.jingcaicompass.history.vo.MarketSettlementHistoryVo;
import com.jingcaicompass.history.vo.MatchResultFactHistoryVo;
import com.jingcaicompass.history.vo.SettlementVersionVo;
import com.jingcaicompass.match.enums.MatchResultFactStatusEnum;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import com.jingcaicompass.prediction.enums.PredictionStatusEnum;
import com.jingcaicompass.prediction.enums.PredictionTypeEnum;
import com.jingcaicompass.prediction.vo.PredictionAsianModelVo;
import com.jingcaicompass.settlement.enums.MarketTypeEnum;
import com.jingcaicompass.settlement.enums.SettlementStatusEnum;
import com.jingcaicompass.statistics.service.StatisticsCalculator;
import com.jingcaicompass.statistics.vo.MarketHitRateVo;
import com.jingcaicompass.statistics.vo.ProbabilityMetricsVo;
import com.jingcaicompass.statistics.vo.RoiMetricsVo;
import com.jingcaicompass.statistics.vo.StatisticsMetricsVo;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

/**
 * 回测指标：把样本重新包装成 {@link HistoryListItemVo} 再喂给线上的
 * {@link StatisticsCalculator}。
 *
 * <p><b>为什么包装而不是自己算</b>：命中率、加权命中率、二分 Brier、ROI/Yield 的口径全部
 * 已经在统计侧定义过一遍（走盘不进命中率分母、连续化 outcome 0.25 刻度、Brier 分母相同）。
 * 自己算第二份的结果是「回测看着比线上好」，而差异来自口径而不是模型。</p>
 *
 * <p><b>三个 scope 各算一次</b>：{@code ALL} 不是「把两个市场相加」。亚盘概率是二分类，
 * 两个市场的样本必须逐腿累加；命中率又必须在同一市场内统计半赢半输。混算会让
 * {@code settled_count} 与 {@code hit_count} 的加总关系被打破，直接撞上
 * {@code ck_backtest_metrics_counts}。</p>
 */
@Service
@ConditionalOnBean(DataSource.class)
public class BacktestMetricsService {

    /** 与 {@code backtest_metrics} 的 numeric(9,6) 对齐。 */
    private static final int METRIC_SCALE = 6;

    private static final int PROFIT_SCALE = 4;

    private final BacktestMetricMapper metricMapper;
    private final ObjectMapper objectMapper;

    /** 纯函数计算器，无状态；直接持有比多一个 bean 更容易单测。 */
    private final StatisticsCalculator calculator = new StatisticsCalculator();

    public BacktestMetricsService(BacktestMetricMapper metricMapper, ObjectMapper objectMapper) {
        this.metricMapper = metricMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 重算并落库一个 run 的全部指标。
     *
     * @param runId 运行 id
     * @param cases 该 run 的全部样本
     * @param modelVersion 写进 {@code metrics_json} 的版本锚点，与 run 行一致
     * @return 写出的行数
     */
    public int recompute(Long runId, List<BacktestCaseEntity> cases, String modelVersion) {
        metricMapper.deleteByRunId(runId);
        if (cases.isEmpty()) {
            return 0;
        }
        Map<String, List<BacktestCaseEntity>> byGroup = new LinkedHashMap<>();
        for (BacktestCaseEntity entity : cases) {
            byGroup.computeIfAbsent(entity.getGroupKey(), key -> new ArrayList<>()).add(entity);
        }

        int written = 0;
        for (Map.Entry<String, List<BacktestCaseEntity>> entry : byGroup.entrySet()) {
            for (BacktestMarketScopeEnum scope : BacktestMarketScopeEnum.values()) {
                BacktestMetricEntity metric = compute(runId, entry.getKey(), scope, entry.getValue());
                metricMapper.insert(metric);
                written++;
            }
        }
        return written;
    }

    /** 分参数组 + 口径的指标对比。 */
    public BacktestComparisonVo compare(Long runId) {
        List<BacktestMetricEntity> rows = metricMapper.selectByRunId(runId);
        List<BacktestMetricsVo> metrics = rows.stream().map(BacktestMetricsService::toVo).toList();
        List<String> groupKeys = rows.stream()
                .map(BacktestMetricEntity::getGroupKey)
                .distinct()
                .toList();
        if (groupKeys.size() <= 1) {
            // 只有一个组时「最优」没有意义，返回 null 而不是把唯一一组说成最优。
            return new BacktestComparisonVo(runId, groupKeys, null, "仅一组参数，无需比较", metrics);
        }
        BacktestMetricsVo best = rows.stream()
                .filter(row -> BacktestMetricEntity.SCOPE_ALL.equals(row.getMarketScope()))
                .min(bestComparator())
                .map(BacktestMetricsService::toVo)
                .orElse(null);
        return new BacktestComparisonVo(
                runId,
                groupKeys,
                best == null ? null : best.groupKey(),
                best == null ? "缺少 ALL 口径指标" : describeBest(best),
                metrics
        );
    }

    /**
     * 最优组排序：ROI 高者优先 → Brier 低者 → 已结算样本多者。
     *
     * <p>ROI 放在第一位是因为回测的最终用途是决策收益；Brier 用于在收益接近时区分
     * 「赢在运气」与「赢在概率」。样本量只作为最后一道区分，避免一个只结算了 3 场的组
     * 因为 ROI 极高而排到第一位。</p>
     */
    private static Comparator<BacktestMetricEntity> bestComparator() {
        return Comparator
                .comparing((BacktestMetricEntity row) -> nullToMin(row.getRoi()))
                .reversed()
                .thenComparing(row -> nullToMax(row.getBrierModel()))
                .thenComparing(row -> -nullToZero(row.getSettledCount()));
    }

    private static String describeBest(BacktestMetricsVo best) {
        return "ROI " + plain(best.roi()) + " / Brier " + plain(best.brierModel())
                + " / 已结算 " + nullToZero(best.settledCount()) + " 腿";
    }

    private BacktestMetricEntity compute(
            Long runId,
            String groupKey,
            BacktestMarketScopeEnum scope,
            List<BacktestCaseEntity> cases
    ) {
        List<HistoryListItemVo> records = new ArrayList<>(cases.size());
        Map<String, Integer> skipCounts = new TreeMap<>();
        BigDecimal profitSum = BigDecimal.ZERO;
        int decided = 0;
        int pickCount = 0;

        for (BacktestCaseEntity entity : cases) {
            if (entity.getSkipReason() != null) {
                skipCounts.merge(skipBucket(entity), 1, Integer::sum);
            } else {
                decided++;
            }
            if (scope.includesHandicap() && entity.getHandicapPick() != null) {
                pickCount++;
            }
            if (scope.includesTotals() && entity.getTotalsPick() != null) {
                pickCount++;
            }
            // 收益按 scope 只累加对应市场，否则 ALL 口径的 profitSum 会被算两遍。
            profitSum = profitSum
                    .add(scope.includesHandicap() ? zeroIfNull(entity.getHandicapProfit()) : BigDecimal.ZERO)
                    .add(scope.includesTotals() ? zeroIfNull(entity.getTotalsProfit()) : BigDecimal.ZERO);
            records.add(toRecord(entity, scope));
        }

        StatisticsMetricsVo metrics = calculator.calculate(records, PredictionTypeEnum.ASIAN);
        MarketAggregate aggregate = aggregateMarkets(metrics.markets(), scope);
        ProbabilityMetricsVo probability = metrics.probabilityMetrics();
        RoiMetricsVo roi = metrics.roi();

        BacktestMetricEntity metric = new BacktestMetricEntity();
        metric.setRunId(runId);
        metric.setGroupKey(groupKey);
        metric.setMarketScope(scope.name());
        metric.setCaseCount(cases.size());
        metric.setDecidedCount(decided);
        metric.setSkippedCount(cases.size() - decided);
        metric.setPickCount(pickCount);
        metric.setSettledCount((int) aggregate.settled());
        metric.setPushCount((int) aggregate.push());
        metric.setHalfWinCount((int) aggregate.halfWin());
        metric.setHalfLossCount((int) aggregate.halfLoss());
        metric.setHitCount((int) aggregate.hit());
        metric.setMissCount((int) aggregate.miss());
        metric.setHitRate(aggregate.hitRate());
        metric.setWeightedHitRate(aggregate.weightedHitRate());
        metric.setProfitSum(profitSum.setScale(PROFIT_SCALE, RoundingMode.HALF_UP));
        metric.setRoi(roi.available() ? roi.roi() : null);
        metric.setYieldRate(roi.available() ? roi.yield() : null);
        metric.setBrierModel(probability.brierScore());
        metric.setBrierMarket(probability.marketBrierScore());
        metric.setBrierDelta(probability.brierAdvantage());
        metric.setBrierSampleSize((int) probability.sampleSize());
        metric.setSkipReasonCounts(new LinkedHashMap<>(skipCounts));
        metric.setMetricsJson(toJsonMap(metrics));
        metric.setComputedAt(Instant.now());
        return metric;
    }

    /**
     * 把一条样本包成统计侧认识的历史记录。
     *
     * <p>三个"借位"取值需要说明：{@code predictionId} 用样本 id（统计只用它做错误信息里的标识）；
     * {@code factId} 用样本 id（统计只读 {@code factStatus} 与比分）；
     * {@code settlementId} 同理。**不要**在这里造一个假的分页实体——一旦有人真的按 id 回查，
     * 借用 id 会指向一张不存在的表，反而更早暴露问题。</p>
     */
    private static HistoryListItemVo toRecord(BacktestCaseEntity entity, BacktestMarketScopeEnum scope) {
        List<MarketSettlementHistoryVo> markets = new ArrayList<>(2);
        if (scope.includesHandicap()) {
            addMarket(markets, MarketTypeEnum.ASIAN_HANDICAP, entity.getHandicapStatus(),
                    entity.getHandicapProfit(), entity);
        }
        if (scope.includesTotals()) {
            addMarket(markets, MarketTypeEnum.ASIAN_TOTAL_GOALS, entity.getTotalsStatus(),
                    entity.getTotalsProfit(), entity);
        }

        MatchResultFactStatusEnum factStatus = switch (entity.getResultStatus()) {
            case FINAL -> MatchResultFactStatusEnum.FINAL;
            case VOID -> MatchResultFactStatusEnum.VOID;
            case PENDING -> MatchResultFactStatusEnum.PENDING;
        };
        MatchResultFactHistoryVo fact = new MatchResultFactHistoryVo(
                entity.getId(),
                1,
                null,
                factStatus,
                MatchStatusEnum.FINISHED,
                entity.getHomeScore(),
                entity.getAwayScore(),
                entity.getKickoffTime(),
                true,
                entity.getCreatedAt()
        );

        PredictedLegs legs = new PredictedLegs(
                scope.includesHandicap() ? entity.getHandicapPick() : null,
                scope.includesTotals() ? entity.getTotalsPick() : null
        );

        return new HistoryListItemVo(
                entity.getId(),
                1,
                null,
                null,
                PredictionStatusEnum.LOCKED,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                entity.getDecisionAt(),
                entity.getDecisionAt(),
                entity.getKickoffTime(),
                new HistoryMatchVo(
                        entity.getMatchId(),
                        null,
                        entity.getLotteryMatchNo(),
                        null,
                        entity.getLeagueName(),
                        null,
                        null,
                        entity.getKickoffTime()
                ),
                List.of(fact),
                markets,
                false,
                PredictionTypeEnum.ASIAN,
                legs.handicapPick(),
                legs.totalsPick(),
                new PredictionAsianModelVo(
                        entity.getLambdaHome(),
                        entity.getLambdaAway(),
                        entity.getModelCoverProb(),
                        entity.getMarketCoverProb(),
                        entity.getEdgeCover(),
                        entity.getModelOverProb(),
                        entity.getMarketOverProb(),
                        entity.getEdgeOver(),
                        entity.getHandicapDispersion(),
                        entity.getTotalsDispersion()
                )
        );
    }

    private static void addMarket(
            List<MarketSettlementHistoryVo> markets,
            MarketTypeEnum marketType,
            SettlementStatusEnum status,
            BigDecimal profit,
            BacktestCaseEntity entity
    ) {
        if (status == null) {
            // 没方向 = 本市场不参与。这里必须「不放这个市场」而不是放一个 PENDING：
            // 统计对亚盘缺失市场是 continue（允许单侧方向），而 PENDING 会让它进 pendingCount。
            return;
        }
        markets.add(new MarketSettlementHistoryVo(
                marketType,
                status,
                true,
                false,
                List.of(new SettlementVersionVo(
                        entity.getId(),
                        1,
                        null,
                        status,
                        null,
                        "backtest",
                        true,
                        entity.getSettledAt() == null ? entity.getDecisionAt() : entity.getSettledAt(),
                        profit
                ))
        ));
    }

    /** 合并各市场的命中统计；{@code ALL} 口径要跨市场累加。 */
    private static MarketAggregate aggregateMarkets(List<MarketHitRateVo> markets, BacktestMarketScopeEnum scope) {
        long settled = 0;
        long hit = 0;
        long miss = 0;
        long push = 0;
        long halfWin = 0;
        long halfLoss = 0;
        for (MarketHitRateVo market : markets) {
            boolean counted = switch (market.marketType()) {
                case ASIAN_HANDICAP -> scope.includesHandicap();
                case ASIAN_TOTAL_GOALS -> scope.includesTotals();
                default -> false;
            };
            if (!counted) {
                continue;
            }
            settled += market.settledSampleSize();
            hit += market.hitCount();
            miss += market.missCount();
            push += market.pushCount();
            halfWin += market.halfWinCount();
            halfLoss += market.halfLossCount();
        }
        // settled_count 在 DB 里定义为「hit+miss+push+half_win+half_loss」，
        // 而 settledSampleSize 不含 push；两者相加才是约束要的值。
        long totalSettled = settled + push;
        return new MarketAggregate(
                totalSettled,
                push,
                halfWin,
                halfLoss,
                hit,
                miss,
                settled == 0 ? null : ratio(hit + halfWin, settled),
                settled == 0 ? null : weightedRatio(hit, halfWin, settled)
        );
    }

    private static BigDecimal ratio(long numerator, long denominator) {
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), METRIC_SCALE, RoundingMode.HALF_UP);
    }

    /** 半盘按半注折算，与统计侧 {@code weightedRatio} 同一公式、同一分母（不含走盘）。 */
    private static BigDecimal weightedRatio(long hit, long halfWin, long settled) {
        return BigDecimal.valueOf(2L * hit + halfWin)
                .divide(BigDecimal.valueOf(2L * settled), METRIC_SCALE, RoundingMode.HALF_UP);
    }

    private Map<String, Object> toJsonMap(StatisticsMetricsVo metrics) {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = objectMapper.convertValue(metrics, Map.class);
        return map;
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * 跳过计数用的桶名。{@code INCONSISTENT_ASIAN_MARKETS} 背后挂着五个出口，
     * 其中「λ 反解无解」还有四种原因，而四种原因的处置方向完全不同
     * （搜索域太窄 / 模型形状冲突 / 数据缺失）。
     *
     * <p>不细分时 8 月 117 场、9 月 165 场会显示成同一个数字，看分布会误以为
     * 「市场在打架」——事实上残差与邻线校验一场都没超限。用 {@code 原因/细分}
     * 写进同一个计数表，消费方不必改结构就能看到拆分。</p>
     */
    private static String skipBucket(BacktestCaseEntity entity) {
        String code = entity.getSkipReason().getCode();
        String detail = entity.getInfeasibilityReason();
        return detail == null ? code : code + "/" + detail;
    }

    private static BigDecimal nullToMin(BigDecimal value) {
        return value == null ? new BigDecimal("-999") : value;
    }

    private static BigDecimal nullToMax(BigDecimal value) {
        return value == null ? new BigDecimal("999") : value;
    }

    private static long nullToZero(Integer value) {
        return value == null ? 0L : value;
    }

    private static String plain(BigDecimal value) {
        return value == null ? "n/a" : value.toPlainString();
    }

    static BacktestMetricsVo toVo(BacktestMetricEntity entity) {
        return new BacktestMetricsVo(
                entity.getRunId(),
                entity.getGroupKey(),
                entity.getMarketScope(),
                entity.getCaseCount(),
                entity.getDecidedCount(),
                entity.getSkippedCount(),
                entity.getPickCount(),
                entity.getSettledCount(),
                entity.getPushCount(),
                entity.getHalfWinCount(),
                entity.getHalfLossCount(),
                entity.getHitCount(),
                entity.getMissCount(),
                entity.getHitRate(),
                entity.getWeightedHitRate(),
                entity.getProfitSum(),
                entity.getRoi(),
                entity.getYieldRate(),
                entity.getBrierModel(),
                entity.getBrierMarket(),
                entity.getBrierDelta(),
                entity.getBrierSampleSize(),
                entity.getSkipReasonCounts(),
                entity.getMetricsJson(),
                entity.getComputedAt()
        );
    }

    /** scope 过滤后的一对方向。 */
    private record PredictedLegs(
            com.jingcaicompass.prediction.enums.AsianHandicapPickEnum handicapPick,
            com.jingcaicompass.prediction.enums.TotalGoalsPickEnum totalsPick
    ) {
    }

    /** 跨市场合并后的命中统计。 */
    private record MarketAggregate(
            long settled,
            long push,
            long halfWin,
            long halfLoss,
            long hit,
            long miss,
            BigDecimal hitRate,
            BigDecimal weightedHitRate
    ) {
    }
}
