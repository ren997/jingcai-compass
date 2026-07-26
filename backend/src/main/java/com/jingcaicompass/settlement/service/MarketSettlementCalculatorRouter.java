package com.jingcaicompass.settlement.service;

import com.jingcaicompass.settlement.dto.MarketSettlementInputDto;
import com.jingcaicompass.settlement.enums.MarketTypeEnum;
import com.jingcaicompass.settlement.enums.SettlementStatusEnum;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 按市场路由到唯一纯函数结算器，不读取或写入任何外部状态。 */
@Component
public class MarketSettlementCalculatorRouter {

    private final Map<MarketTypeEnum, MarketSettlementCalculator> calculators;

    public MarketSettlementCalculatorRouter(List<MarketSettlementCalculator> calculators) {
        EnumMap<MarketTypeEnum, MarketSettlementCalculator> calculatorMap = new EnumMap<>(MarketTypeEnum.class);
        for (MarketSettlementCalculator calculator : calculators) {
            if (calculator == null) {
                throw new IllegalArgumentException("market settlement calculator must not be null");
            }
            MarketSettlementCalculator previous = calculatorMap.put(calculator.supportedMarket(), calculator);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate calculator for marketType: " + calculator.supportedMarket());
            }
        }
        this.calculators = Map.copyOf(calculatorMap);
    }

    /** 根据输入市场委派给对应计算器。 */
    public SettlementStatusEnum calculate(MarketSettlementInputDto input) {
        // 1) 校验调用方选择的市场。
        if (input == null) {
            throw new IllegalArgumentException("market settlement input must not be null");
        }
        MarketTypeEnum marketType = input.marketType();
        if (marketType == null) {
            throw new IllegalArgumentException("marketType must not be null");
        }

        // 2) 使用注册的唯一市场规则计算结果。
        MarketSettlementCalculator calculator = calculators.get(marketType);
        if (calculator == null) {
            throw new IllegalArgumentException("unsupported marketType: " + marketType);
        }
        return calculator.calculate(input);
    }
}
