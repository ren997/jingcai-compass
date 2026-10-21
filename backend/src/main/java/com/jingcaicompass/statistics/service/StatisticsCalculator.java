package com.jingcaicompass.statistics.service;

import com.jingcaicompass.history.vo.HistoryListItemVo;
import com.jingcaicompass.history.vo.MarketSettlementHistoryVo;
import com.jingcaicompass.history.vo.MatchResultFactHistoryVo;
import com.jingcaicompass.history.vo.SettlementVersionVo;
import com.jingcaicompass.match.enums.MatchResultFactStatusEnum;
import com.jingcaicompass.prediction.enums.AsianHandicapPickEnum;
import com.jingcaicompass.prediction.enums.HandicapPickEnum;
import com.jingcaicompass.prediction.enums.PredictionTypeEnum;
import com.jingcaicompass.prediction.enums.TotalGoalsPickEnum;
import com.jingcaicompass.prediction.vo.PredictionAsianModelVo;
import com.jingcaicompass.settlement.enums.MarketTypeEnum;
import com.jingcaicompass.settlement.enums.SettlementStatusEnum;
import com.jingcaicompass.statistics.enums.ProbabilityMetricUnavailableReasonEnum;
import com.jingcaicompass.statistics.enums.ProbabilityScoringModeEnum;
import com.jingcaicompass.statistics.enums.RoiUnavailableReasonEnum;
import com.jingcaicompass.statistics.vo.MarketHitRateVo;
import com.jingcaicompass.statistics.vo.ProbabilityMetricsVo;
import com.jingcaicompass.statistics.vo.RoiMetricsVo;
import com.jingcaicompass.statistics.vo.StatisticsMetricsVo;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 不访问数据库的公开统计口径计算器。
 *
 * <p>体彩与亚盘是两套不可混算的口径：前者以「一条预测」为概率样本、对胜平负三路求和；
 * 后者以「一条预测的一个有方向市场」为样本、只评该方向的二分类概率，且存在走盘与半赢半输。
 * 因此调用方必须显式指定 {@code predictionType}，一次只算一条产品线。
 */
public final class StatisticsCalculator {

    private static final double LOG_LOSS_EPSILON = 1e-15d;
    private static final int METRIC_SCALE = 6;

    /** 基于 LOCKED 预测和当前事实/结算计算统一统计指标。 */
    public StatisticsMetricsVo calculate(List<HistoryListItemVo> records, PredictionTypeEnum predictionType) {
        PredictionTypeEnum scoringType = predictionType == null ? PredictionTypeEnum.SPORTTERY : predictionType;
        Accumulator accumulator = new Accumulator(scoringType);
        for (HistoryListItemVo record : records) {
            // 防御性隔离：即便上游漏了 SQL 过滤，也不会把另一条产品线的记录算进分母。
            if (record.predictionType() != scoringType) {
                continue;
            }
            accumulator.record(record);
        }
        return accumulator.toVo();
    }

    private static final class Accumulator {
        private final PredictionTypeEnum predictionType;
        private final List<MarketAccumulator> markets;
        private long lockedPredictionCount;
        private long finalFactCount;
        private long pendingFactCount;
        private long voidFactCount;

        /** 三分类（体彩）累加。 */
        private double brierSum;
        private double logLossSum;

        /** 二分类（亚盘）累加。模型与市场共用同一批样本，分母必须相同。 */
        private double modelBrierSum;
        private double marketBrierSum;
        private long probabilitySampleSize;
        private boolean missingAsianProbability;

        /** 亚盘收益累加。每注本金固定 1 单位，与结算侧 netProfit 计价单位一致。 */
        private BigDecimal profitSum = BigDecimal.ZERO;
        private long stakeCount;
        private long effectiveStakeCount;
        private boolean missingSettlementProfit;

        private Accumulator(PredictionTypeEnum predictionType) {
            this.predictionType = predictionType;
            List<MarketAccumulator> created = new ArrayList<>();
            for (MarketTypeEnum marketType : MarketTypeEnum.marketsFor(predictionType)) {
                created.add(new MarketAccumulator(marketType));
            }
            this.markets = List.copyOf(created);
        }

        private void record(HistoryListItemVo record) {
            lockedPredictionCount++;
            MatchResultFactHistoryVo fact = currentFact(record);
            if (fact == null || fact.factStatus() == MatchResultFactStatusEnum.PENDING) {
                pendingFactCount++;
            } else if (fact.factStatus() == MatchResultFactStatusEnum.VOID) {
                voidFactCount++;
            } else if (fact.factStatus() == MatchResultFactStatusEnum.FINAL) {
                finalFactCount++;
                if (predictionType == PredictionTypeEnum.SPORTTERY) {
                    recordThreeWayMetrics(record, fact);
                }
            } else {
                throw new IllegalArgumentException("unknown current fact status for prediction " + record.predictionId());
            }
            recordMarkets(record);
            if (predictionType == PredictionTypeEnum.ASIAN) {
                recordAsianSamples(record);
            }
        }

        private void recordMarkets(HistoryListItemVo record) {
            for (MarketAccumulator market : markets) {
                MarketSettlementHistoryVo settlement = findMarket(record, market.marketType);
                if (settlement == null) {
                    // 体彩缺市场属数据契约破坏；亚盘允许只给出单侧方向，缺失市场就是「本就没有这个方向」。
                    if (predictionType == PredictionTypeEnum.SPORTTERY) {
                        throw new IllegalArgumentException(
                                "missing " + market.marketType + " history for prediction " + record.predictionId()
                        );
                    }
                    continue;
                }
                market.record(settlement.currentStatus());
            }
        }

        private void recordThreeWayMetrics(HistoryListItemVo record, MatchResultFactHistoryVo fact) {
            if (fact.homeScore() == null || fact.awayScore() == null || fact.homeScore() < 0 || fact.awayScore() < 0) {
                throw new IllegalArgumentException("FINAL fact has invalid score for prediction " + record.predictionId());
            }
            HandicapPickEnum actual = outcome(fact.homeScore(), fact.awayScore());
            double home = requireProbability(record.homeWinProb(), "homeWinProb", record.predictionId());
            double draw = requireProbability(record.drawProb(), "drawProb", record.predictionId());
            double away = requireProbability(record.awayWinProb(), "awayWinProb", record.predictionId());
            double expectedHome = actual == HandicapPickEnum.HOME_WIN ? 1d : 0d;
            double expectedDraw = actual == HandicapPickEnum.DRAW ? 1d : 0d;
            double expectedAway = actual == HandicapPickEnum.AWAY_WIN ? 1d : 0d;
            brierSum += square(home - expectedHome) + square(draw - expectedDraw) + square(away - expectedAway);
            double actualProbability = switch (actual) {
                case HOME_WIN -> home;
                case DRAW -> draw;
                case AWAY_WIN -> away;
            };
            logLossSum += -Math.log(Math.max(actualProbability, LOG_LOSS_EPSILON));
        }

        /**
         * 逐市场累加亚盘概率样本与收益。
         *
         * <p>落库的四个概率是首边口径（主队赢盘 / 大球），所选方向在另一边时取 {@code 1 − p}。
         * 这一步不能省：直接把首边值当方向概率会让「押客队」的场次按「押主队」的概率记分，
         * 模型胜率会凭空涨到 60% 以上。
         */
        private void recordAsianSamples(HistoryListItemVo record) {
            PredictionAsianModelVo model = record.asianModel();
            if (model == null) {
                return;
            }
            if (record.asianHandicapPick() != null) {
                MarketSettlementHistoryVo market = findMarket(record, MarketTypeEnum.ASIAN_HANDICAP);
                if (market != null) {
                    boolean secondSide = record.asianHandicapPick() == AsianHandicapPickEnum.AWAY_COVER;
                    recordAsianSample(market, model.modelCoverProb(), model.marketCoverProb(), secondSide);
                }
            }
            if (record.totalGoalsPick() != null) {
                MarketSettlementHistoryVo market = findMarket(record, MarketTypeEnum.ASIAN_TOTAL_GOALS);
                if (market != null) {
                    boolean secondSide = record.totalGoalsPick() == TotalGoalsPickEnum.UNDER;
                    recordAsianSample(market, model.modelOverProb(), model.marketOverProb(), secondSide);
                }
            }
        }

        private void recordAsianSample(
                MarketSettlementHistoryVo market,
                BigDecimal modelFirst,
                BigDecimal marketFirst,
                boolean secondSide
        ) {
            BigDecimal outcome = continuousOutcome(market.currentStatus());
            if (outcome == null) {
                return;
            }
            if (modelFirst == null || marketFirst == null) {
                missingAsianProbability = true;
                return;
            }
            double modelPick = secondSide
                    ? BigDecimal.ONE.subtract(modelFirst).doubleValue()
                    : modelFirst.doubleValue();
            double marketPick = secondSide
                    ? BigDecimal.ONE.subtract(marketFirst).doubleValue()
                    : marketFirst.doubleValue();
            double actual = outcome.doubleValue();
            modelBrierSum += square(modelPick - actual);
            marketBrierSum += square(marketPick - actual);
            probabilitySampleSize++;

            BigDecimal profit = currentProfit(market);
            if (profit == null) {
                missingSettlementProfit = true;
                return;
            }
            profitSum = profitSum.add(profit);
            stakeCount++;
            if (market.currentStatus() != SettlementStatusEnum.PUSH) {
                effectiveStakeCount++;
            }
        }

        private StatisticsMetricsVo toVo() {
            List<MarketHitRateVo> marketVos = markets.stream().map(MarketAccumulator::toVo).toList();
            return new StatisticsMetricsVo(
                    lockedPredictionCount,
                    finalFactCount,
                    pendingFactCount,
                    voidFactCount,
                    marketVos,
                    probabilityMetrics(),
                    roi()
            );
        }

        private ProbabilityMetricsVo probabilityMetrics() {
            if (predictionType == PredictionTypeEnum.ASIAN) {
                return asianProbabilityMetrics();
            }
            if (finalFactCount == 0) {
                return new ProbabilityMetricsVo(
                        0,
                        ProbabilityScoringModeEnum.THREE_WAY,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(ProbabilityMetricUnavailableReasonEnum.NO_FINAL_SAMPLE)
                );
            }
            return new ProbabilityMetricsVo(
                    finalFactCount,
                    ProbabilityScoringModeEnum.THREE_WAY,
                    decimal(brierSum / finalFactCount),
                    decimal(logLossSum / finalFactCount),
                    null,
                    null,
                    null,
                    List.of()
            );
        }

        private ProbabilityMetricsVo asianProbabilityMetrics() {
            if (probabilitySampleSize == 0) {
                List<ProbabilityMetricUnavailableReasonEnum> reasons = new ArrayList<>();
                reasons.add(ProbabilityMetricUnavailableReasonEnum.NO_SETTLED_ASIAN_SAMPLE);
                if (missingAsianProbability) {
                    reasons.add(ProbabilityMetricUnavailableReasonEnum.MISSING_ASIAN_MODEL_PROBABILITY);
                }
                return new ProbabilityMetricsVo(
                        0,
                        ProbabilityScoringModeEnum.ASIAN_DIRECTION,
                        null,
                        null,
                        null,
                        null,
                        null,
                        reasons
                );
            }
            // 优劣判定用未舍入的累加值：先各自除同一个分母再相减，等价于比较累加和，
            // 但可以避免 6 位小数舍入在边界上给出相反结论。
            boolean modelOutperforms = modelBrierSum < marketBrierSum;
            BigDecimal modelBrier = decimal(modelBrierSum / probabilitySampleSize);
            BigDecimal marketBrier = decimal(marketBrierSum / probabilitySampleSize);
            return new ProbabilityMetricsVo(
                    probabilitySampleSize,
                    ProbabilityScoringModeEnum.ASIAN_DIRECTION,
                    modelBrier,
                    null,
                    marketBrier,
                    marketBrier.subtract(modelBrier),
                    modelOutperforms,
                    List.of()
            );
        }

        private RoiMetricsVo roi() {
            if (predictionType != PredictionTypeEnum.ASIAN) {
                // 体彩没有冻结水位，收益无法折算，保持既有「不可用」口径而不是伪造一个 0。
                return new RoiMetricsVo(
                        false,
                        null,
                        null,
                        0,
                        List.of(
                                RoiUnavailableReasonEnum.MISSING_FIXED_BETTING_RULE,
                                RoiUnavailableReasonEnum.MISSING_LOCKED_BETTING_MARKET,
                                RoiUnavailableReasonEnum.MISSING_LOCKED_ODDS_INPUT
                        )
                );
            }
            if (stakeCount == 0) {
                List<RoiUnavailableReasonEnum> reasons = new ArrayList<>();
                reasons.add(RoiUnavailableReasonEnum.NO_SETTLED_SETTLEMENT);
                if (missingSettlementProfit) {
                    reasons.add(RoiUnavailableReasonEnum.MISSING_SETTLEMENT_PROFIT);
                }
                return new RoiMetricsVo(false, null, null, 0, reasons);
            }
            BigDecimal roi = profitSum.divide(BigDecimal.valueOf(stakeCount), METRIC_SCALE, RoundingMode.HALF_UP);
            // yield 的分母剔除走盘：退本注不承担风险，计入分母会系统性压低「真正承担风险的回报率」。
            BigDecimal yield = effectiveStakeCount == 0
                    ? null
                    : profitSum.divide(BigDecimal.valueOf(effectiveStakeCount), METRIC_SCALE, RoundingMode.HALF_UP);
            return new RoiMetricsVo(true, roi, yield, stakeCount, List.of());
        }
    }

    private static final class MarketAccumulator {
        private final MarketTypeEnum marketType;
        private long hitCount;
        private long missCount;
        private long pushCount;
        private long halfWinCount;
        private long halfLossCount;
        private long pendingCount;
        private long voidCount;

        private MarketAccumulator(MarketTypeEnum marketType) {
            this.marketType = marketType;
        }

        private void record(SettlementStatusEnum status) {
            switch (status) {
                case HIT -> hitCount++;
                case MISS -> missCount++;
                case PUSH -> pushCount++;
                case HALF_WIN -> halfWinCount++;
                case HALF_LOSS -> halfLossCount++;
                case PENDING -> pendingCount++;
                case VOID -> voidCount++;
                // 箭头 switch 语句不要求穷尽，缺省分支会让新状态被静默丢弃、样本凭空消失。
                // 这里显式抛错，把「枚举扩容但统计没跟上」变成启动后立刻可见的失败。
                default -> throw new IllegalStateException(
                        "unhandled settlement status for statistics: " + status);
            }
        }

        private MarketHitRateVo toVo() {
            long settled = hitCount + missCount + halfWinCount + halfLossCount;
            return new MarketHitRateVo(
                    marketType,
                    settled,
                    hitCount,
                    missCount,
                    pushCount,
                    halfWinCount,
                    halfLossCount,
                    pendingCount,
                    voidCount,
                    settled == 0 ? null : ratio(hitCount + halfWinCount, settled),
                    settled == 0 ? null : weightedRatio(hitCount, halfWinCount, settled)
            );
        }

        private static BigDecimal ratio(long numerator, long denominator) {
            return BigDecimal.valueOf(numerator)
                    .divide(BigDecimal.valueOf(denominator), METRIC_SCALE, RoundingMode.HALF_UP);
        }

        /** 半盘按半注折算：(HIT + 0.5 × HALF_WIN) / settled。 */
        private static BigDecimal weightedRatio(long hitCount, long halfWinCount, long settled) {
            return BigDecimal.valueOf(2L * hitCount + halfWinCount)
                    .divide(BigDecimal.valueOf(2L * settled), METRIC_SCALE, RoundingMode.HALF_UP);
        }
    }

    private static MarketSettlementHistoryVo findMarket(HistoryListItemVo record, MarketTypeEnum marketType) {
        return record.settlementMarkets().stream()
                .filter(item -> item.marketType() == marketType)
                .findFirst()
                .orElse(null);
    }

    /** 当前生效结算版本的收益；无 current 版本时返回 null。 */
    private static BigDecimal currentProfit(MarketSettlementHistoryVo market) {
        return market.versions().stream()
                .filter(SettlementVersionVo::current)
                .map(SettlementVersionVo::profit)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /**
     * 结算状态 → 连续化 outcome。返回 {@code null} 表示该状态不进入概率样本。
     *
     * <p>0.25 刻度与结算侧 {@code SettlementMarginSupport} 的 margin 判定同源，
     * 走盘记 0.5 表示「既没赢也没输」，而不是剔除。
     */
    private static BigDecimal continuousOutcome(SettlementStatusEnum status) {
        return switch (status) {
            case HIT -> BigDecimal.ONE;
            case HALF_WIN -> new BigDecimal("0.75");
            case PUSH -> new BigDecimal("0.5");
            case HALF_LOSS -> new BigDecimal("0.25");
            case MISS -> BigDecimal.ZERO;
            case PENDING, VOID -> null;
        };
    }

    private static MatchResultFactHistoryVo currentFact(HistoryListItemVo record) {
        return record.resultFacts().stream().filter(MatchResultFactHistoryVo::current).findFirst().orElse(null);
    }

    private static HandicapPickEnum outcome(int homeScore, int awayScore) {
        if (homeScore > awayScore) {
            return HandicapPickEnum.HOME_WIN;
        }
        if (homeScore < awayScore) {
            return HandicapPickEnum.AWAY_WIN;
        }
        return HandicapPickEnum.DRAW;
    }

    private static double requireProbability(BigDecimal value, String field, Long predictionId) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(field + " is invalid for prediction " + predictionId);
        }
        return value.doubleValue();
    }

    private static double square(double value) {
        return value * value;
    }

    private static BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(METRIC_SCALE, RoundingMode.HALF_UP);
    }
}
