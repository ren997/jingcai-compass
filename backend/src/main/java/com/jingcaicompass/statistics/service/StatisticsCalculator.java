package com.jingcaicompass.statistics.service;

import com.jingcaicompass.history.vo.HistoryListItemVo;
import com.jingcaicompass.history.vo.MarketSettlementHistoryVo;
import com.jingcaicompass.history.vo.MatchResultFactHistoryVo;
import com.jingcaicompass.match.enums.MatchResultFactStatusEnum;
import com.jingcaicompass.prediction.enums.HandicapPickEnum;
import com.jingcaicompass.settlement.enums.MarketTypeEnum;
import com.jingcaicompass.settlement.enums.SettlementStatusEnum;
import com.jingcaicompass.statistics.enums.ProbabilityMetricUnavailableReasonEnum;
import com.jingcaicompass.statistics.enums.RoiUnavailableReasonEnum;
import com.jingcaicompass.statistics.vo.MarketHitRateVo;
import com.jingcaicompass.statistics.vo.ProbabilityMetricsVo;
import com.jingcaicompass.statistics.vo.RoiMetricsVo;
import com.jingcaicompass.statistics.vo.StatisticsMetricsVo;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** 不访问数据库的公开统计口径计算器。 */
public final class StatisticsCalculator {

    private static final double LOG_LOSS_EPSILON = 1e-15d;
    private static final int METRIC_SCALE = 6;

    /** 基于 LOCKED 预测和当前事实/结算计算统一统计指标。 */
    public StatisticsMetricsVo calculate(List<HistoryListItemVo> records) {
        Accumulator accumulator = new Accumulator();
        for (HistoryListItemVo record : records) {
            accumulator.record(record);
        }
        return accumulator.toVo();
    }

    private static final class Accumulator {
        private long lockedPredictionCount;
        private long finalFactCount;
        private long pendingFactCount;
        private long voidFactCount;
        private double brierSum;
        private double logLossSum;
        private final MarketAccumulator had = new MarketAccumulator(MarketTypeEnum.HAD);
        private final MarketAccumulator hhad = new MarketAccumulator(MarketTypeEnum.HHAD);

        private void record(HistoryListItemVo record) {
            lockedPredictionCount++;
            MatchResultFactHistoryVo fact = currentFact(record);
            if (fact == null || fact.factStatus() == MatchResultFactStatusEnum.PENDING) {
                pendingFactCount++;
            } else if (fact.factStatus() == MatchResultFactStatusEnum.VOID) {
                voidFactCount++;
            } else if (fact.factStatus() == MatchResultFactStatusEnum.FINAL) {
                finalFactCount++;
                recordProbabilityMetrics(record, fact);
            } else {
                throw new IllegalArgumentException("unknown current fact status for prediction " + record.predictionId());
            }
            recordMarket(record, had);
            recordMarket(record, hhad);
        }

        private void recordProbabilityMetrics(HistoryListItemVo record, MatchResultFactHistoryVo fact) {
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

        private void recordMarket(HistoryListItemVo record, MarketAccumulator market) {
            MarketSettlementHistoryVo settlement = record.settlementMarkets().stream()
                    .filter(item -> item.marketType() == market.marketType)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "missing " + market.marketType + " history for prediction " + record.predictionId()
                    ));
            market.record(settlement.currentStatus());
        }

        private StatisticsMetricsVo toVo() {
            ProbabilityMetricsVo probabilityMetrics = finalFactCount == 0
                    ? new ProbabilityMetricsVo(
                            0,
                            null,
                            null,
                            List.of(ProbabilityMetricUnavailableReasonEnum.NO_FINAL_SAMPLE)
                    )
                    : new ProbabilityMetricsVo(
                            finalFactCount,
                            decimal(brierSum / finalFactCount),
                            decimal(logLossSum / finalFactCount),
                            List.of()
                    );
            return new StatisticsMetricsVo(
                    lockedPredictionCount,
                    finalFactCount,
                    pendingFactCount,
                    voidFactCount,
                    probabilityMetrics,
                    had.toVo(),
                    hhad.toVo(),
                    new RoiMetricsVo(
                            false,
                            null,
                            null,
                            0,
                            List.of(
                                    RoiUnavailableReasonEnum.MISSING_FIXED_BETTING_RULE,
                                    RoiUnavailableReasonEnum.MISSING_LOCKED_BETTING_MARKET,
                                    RoiUnavailableReasonEnum.MISSING_LOCKED_ODDS_INPUT
                            )
                    )
            );
        }
    }

    private static final class MarketAccumulator {
        private final MarketTypeEnum marketType;
        private long hitCount;
        private long missCount;
        private long pendingCount;
        private long voidCount;

        private MarketAccumulator(MarketTypeEnum marketType) {
            this.marketType = marketType;
        }

        private void record(SettlementStatusEnum status) {
            switch (status) {
                case HIT -> hitCount++;
                case MISS -> missCount++;
                case PENDING -> pendingCount++;
                case VOID -> voidCount++;
            }
        }

        private MarketHitRateVo toVo() {
            long settled = hitCount + missCount;
            return new MarketHitRateVo(
                    marketType,
                    settled,
                    hitCount,
                    missCount,
                    pendingCount,
                    voidCount,
                    settled == 0 ? null : BigDecimal.valueOf(hitCount)
                            .divide(BigDecimal.valueOf(settled), METRIC_SCALE, RoundingMode.HALF_UP)
            );
        }
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
