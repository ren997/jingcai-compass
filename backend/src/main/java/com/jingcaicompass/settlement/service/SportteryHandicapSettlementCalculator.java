package com.jingcaicompass.settlement.service;

import com.jingcaicompass.prediction.enums.HandicapPickEnum;
import com.jingcaicompass.settlement.dto.MarketSettlementInputDto;
import com.jingcaicompass.settlement.enums.MarketTypeEnum;
import com.jingcaicompass.settlement.enums.SettlementStatusEnum;
import java.math.BigInteger;
import org.springframework.stereotype.Component;

/** HHAD 体彩让球胜平负市场的纯函数结算器。 */
@Component
public class SportteryHandicapSettlementCalculator implements MarketSettlementCalculator {

    @Override
    public MarketTypeEnum supportedMarket() {
        return MarketTypeEnum.HHAD;
    }

    @Override
    public SettlementStatusEnum calculate(MarketSettlementInputDto input) {
        // 1) 校验市场并优先处理不具备最终赛果资格的事实。
        MarketSettlementInputDto validatedInput = SettlementCalculationSupport.requireInputForMarket(input, supportedMarket());
        var nonFinalOutcome = SettlementCalculationSupport.nonFinalOutcome(validatedInput.matchResultFact());
        if (nonFinalOutcome.isPresent()) {
            return nonFinalOutcome.get();
        }

        // 2) 将官方让球直接加到主队比分后，比较让球胜平负结果。
        SettlementCalculationSupport.Score score = SettlementCalculationSupport.requireFinalScore(
                validatedInput.matchResultFact()
        );
        BigInteger adjustedHomeScore = BigInteger.valueOf(score.homeScore()).add(
                SettlementCalculationSupport.requireIntegerHandicap(validatedInput)
        );
        HandicapPickEnum actualOutcome = outcomeFor(adjustedHomeScore, score.awayScore());
        return actualOutcome == SettlementCalculationSupport.requireSelectedOutcome(validatedInput)
                ? SettlementStatusEnum.HIT
                : SettlementStatusEnum.MISS;
    }

    private HandicapPickEnum outcomeFor(BigInteger adjustedHomeScore, int awayScore) {
        int comparison = adjustedHomeScore.compareTo(BigInteger.valueOf(awayScore));
        if (comparison > 0) {
            return HandicapPickEnum.HOME_WIN;
        }
        if (comparison < 0) {
            return HandicapPickEnum.AWAY_WIN;
        }
        return HandicapPickEnum.DRAW;
    }
}
