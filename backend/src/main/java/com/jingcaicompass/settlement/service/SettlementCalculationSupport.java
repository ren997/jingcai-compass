package com.jingcaicompass.settlement.service;

import com.jingcaicompass.match.entity.MatchResultFact;
import com.jingcaicompass.match.enums.MatchResultFactStatusEnum;
import com.jingcaicompass.prediction.enums.HandicapPickEnum;
import com.jingcaicompass.settlement.dto.MarketSettlementInputDto;
import com.jingcaicompass.settlement.enums.MarketTypeEnum;
import com.jingcaicompass.settlement.enums.SettlementStatusEnum;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Optional;

/** 市场计算器共享的输入与赛果资格校验。 */
final class SettlementCalculationSupport {

    private SettlementCalculationSupport() {
    }

    static MarketSettlementInputDto requireInputForMarket(
            MarketSettlementInputDto input,
            MarketTypeEnum expectedMarket
    ) {
        if (input == null) {
            throw new IllegalArgumentException("market settlement input must not be null");
        }
        if (input.marketType() == null) {
            throw new IllegalArgumentException("marketType must not be null");
        }
        if (input.marketType() != expectedMarket) {
            throw new IllegalArgumentException("calculator does not support marketType: " + input.marketType());
        }
        return input;
    }

    static Optional<SettlementStatusEnum> nonFinalOutcome(MatchResultFact fact) {
        requireFact(fact);
        return switch (fact.getFactStatus()) {
            case PENDING -> Optional.of(SettlementStatusEnum.PENDING);
            case VOID -> Optional.of(SettlementStatusEnum.VOID);
            case FINAL -> Optional.empty();
        };
    }

    static Score requireFinalScore(MatchResultFact fact) {
        requireFact(fact);
        if (fact.getFactStatus() != MatchResultFactStatusEnum.FINAL) {
            throw new IllegalArgumentException("final score requires a FINAL match result fact");
        }
        Integer homeScore = fact.getHomeScore();
        Integer awayScore = fact.getAwayScore();
        if (homeScore == null || awayScore == null) {
            throw new IllegalArgumentException("FINAL match result fact requires both scores");
        }
        if (homeScore < 0 || awayScore < 0) {
            throw new IllegalArgumentException("FINAL match result fact scores must not be negative");
        }
        return new Score(homeScore, awayScore);
    }

    static HandicapPickEnum requireSelectedOutcome(MarketSettlementInputDto input) {
        if (input.selectedOutcome() == null) {
            throw new IllegalArgumentException("selectedOutcome must not be null for FINAL settlement");
        }
        return input.selectedOutcome();
    }

    static BigInteger requireIntegerHandicap(MarketSettlementInputDto input) {
        BigDecimal officialHandicap = input.officialHandicap();
        if (officialHandicap == null) {
            throw new IllegalArgumentException("officialHandicap must not be null for HHAD settlement");
        }
        try {
            return officialHandicap.toBigIntegerExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("officialHandicap must be an integer for HHAD settlement", exception);
        }
    }

    static HandicapPickEnum outcomeFor(int homeScore, int awayScore) {
        int comparison = Integer.compare(homeScore, awayScore);
        if (comparison > 0) {
            return HandicapPickEnum.HOME_WIN;
        }
        if (comparison < 0) {
            return HandicapPickEnum.AWAY_WIN;
        }
        return HandicapPickEnum.DRAW;
    }

    private static void requireFact(MatchResultFact fact) {
        if (fact == null) {
            throw new IllegalArgumentException("matchResultFact must not be null");
        }
        if (fact.getFactStatus() == null) {
            throw new IllegalArgumentException("matchResultFact.factStatus must not be null");
        }
    }

    record Score(int homeScore, int awayScore) {
    }
}
