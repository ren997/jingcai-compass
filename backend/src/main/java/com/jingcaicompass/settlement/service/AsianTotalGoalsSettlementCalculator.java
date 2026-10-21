package com.jingcaicompass.settlement.service;

import com.jingcaicompass.prediction.enums.TotalGoalsPickEnum;
import com.jingcaicompass.settlement.dto.MarketSettlementInputDto;
import com.jingcaicompass.settlement.enums.MarketTypeEnum;
import com.jingcaicompass.settlement.enums.SettlementStatusEnum;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/** 亚盘大小球市场的纯函数结算器，支持半球、整数与四分之一盘。 */
@Component
public class AsianTotalGoalsSettlementCalculator implements MarketSettlementCalculator {

    @Override
    public MarketTypeEnum supportedMarket() {
        return MarketTypeEnum.ASIAN_TOTAL_GOALS;
    }

    @Override
    public SettlementStatusEnum calculate(MarketSettlementInputDto input) {
        // 1) 校验市场并优先处理不具备最终赛果资格的事实。
        MarketSettlementInputDto validatedInput =
                SettlementCalculationSupport.requireInputForMarket(input, supportedMarket());
        var nonFinalOutcome = SettlementCalculationSupport.nonFinalOutcome(validatedInput.matchResultFact());
        if (nonFinalOutcome.isPresent()) {
            return nonFinalOutcome.get();
        }

        // 2) 大球方向的净胜刻度 = (主队得分 + 客队得分) − 大小球线；小球方向取反。
        SettlementCalculationSupport.Score score =
                SettlementCalculationSupport.requireFinalScore(validatedInput.matchResultFact());
        TotalGoalsPickEnum pick = requirePick(validatedInput);
        BigDecimal line = SettlementMarginSupport.requireQuarterLine(validatedInput.marketLine(), "marketLine");
        BigDecimal overMargin = BigDecimal.valueOf(score.homeScore())
                .add(BigDecimal.valueOf(score.awayScore()))
                .subtract(line);

        // 3) 按所选方向判定，四分之一盘的拆盘合并由统一的 margin 规则完成。
        BigDecimal signedMargin = pick == TotalGoalsPickEnum.OVER ? overMargin : overMargin.negate();
        return SettlementMarginSupport.settleByMargin(signedMargin);
    }

    private TotalGoalsPickEnum requirePick(MarketSettlementInputDto input) {
        if (input.totalGoalsPick() == null) {
            throw new IllegalArgumentException("totalGoalsPick must not be null for FINAL asian total goals settlement");
        }
        return input.totalGoalsPick();
    }
}
