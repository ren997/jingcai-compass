package com.jingcaicompass.settlement.service;

import com.jingcaicompass.prediction.enums.HandicapPickEnum;
import com.jingcaicompass.settlement.dto.MarketSettlementInputDto;
import com.jingcaicompass.settlement.enums.MarketTypeEnum;
import com.jingcaicompass.settlement.enums.SettlementStatusEnum;
import org.springframework.stereotype.Component;

/** HAD 胜平负市场的纯函数结算器。 */
@Component
public class WinDrawLossSettlementCalculator implements MarketSettlementCalculator {

    @Override
    public MarketTypeEnum supportedMarket() {
        return MarketTypeEnum.HAD;
    }

    @Override
    public SettlementStatusEnum calculate(MarketSettlementInputDto input) {
        // 1) 校验市场并优先处理不具备最终赛果资格的事实。
        MarketSettlementInputDto validatedInput = SettlementCalculationSupport.requireInputForMarket(input, supportedMarket());
        var nonFinalOutcome = SettlementCalculationSupport.nonFinalOutcome(validatedInput.matchResultFact());
        if (nonFinalOutcome.isPresent()) {
            return nonFinalOutcome.get();
        }

        // 2) 以原始比分确定三态结果并与调用方显式选项比较。
        SettlementCalculationSupport.Score score = SettlementCalculationSupport.requireFinalScore(
                validatedInput.matchResultFact()
        );
        HandicapPickEnum actualOutcome = SettlementCalculationSupport.outcomeFor(score.homeScore(), score.awayScore());
        return actualOutcome == SettlementCalculationSupport.requireSelectedOutcome(validatedInput)
                ? SettlementStatusEnum.HIT
                : SettlementStatusEnum.MISS;
    }
}
