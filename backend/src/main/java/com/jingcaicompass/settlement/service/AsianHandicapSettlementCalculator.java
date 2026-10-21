package com.jingcaicompass.settlement.service;

import com.jingcaicompass.prediction.enums.AsianHandicapPickEnum;
import com.jingcaicompass.settlement.dto.MarketSettlementInputDto;
import com.jingcaicompass.settlement.enums.MarketTypeEnum;
import com.jingcaicompass.settlement.enums.SettlementStatusEnum;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/** 亚盘让球市场的纯函数结算器，支持半球、整数与四分之一盘。 */
@Component
public class AsianHandicapSettlementCalculator implements MarketSettlementCalculator {

    @Override
    public MarketTypeEnum supportedMarket() {
        return MarketTypeEnum.ASIAN_HANDICAP;
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

        // 2) 主队方向的净胜刻度 = (主队得分 − 客队得分) + 让球线；客队方向取反。
        SettlementCalculationSupport.Score score =
                SettlementCalculationSupport.requireFinalScore(validatedInput.matchResultFact());
        AsianHandicapPickEnum pick = requirePick(validatedInput);
        BigDecimal line = SettlementMarginSupport.requireQuarterLine(validatedInput.marketLine(), "marketLine");
        BigDecimal homeMargin = BigDecimal.valueOf(score.homeScore())
                .subtract(BigDecimal.valueOf(score.awayScore()))
                .add(line);

        // 3) 按所选方向判定，四分之一盘的拆盘合并由统一的 margin 规则完成。
        BigDecimal signedMargin = pick == AsianHandicapPickEnum.HOME_COVER ? homeMargin : homeMargin.negate();
        return SettlementMarginSupport.settleByMargin(signedMargin);
    }

    private AsianHandicapPickEnum requirePick(MarketSettlementInputDto input) {
        if (input.asianHandicapPick() == null) {
            throw new IllegalArgumentException("asianHandicapPick must not be null for FINAL asian handicap settlement");
        }
        return input.asianHandicapPick();
    }
}
