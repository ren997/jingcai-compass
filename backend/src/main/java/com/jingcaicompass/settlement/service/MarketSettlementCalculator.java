package com.jingcaicompass.settlement.service;

import com.jingcaicompass.settlement.dto.MarketSettlementInputDto;
import com.jingcaicompass.settlement.enums.MarketTypeEnum;
import com.jingcaicompass.settlement.enums.SettlementStatusEnum;

/** 一个体彩市场的无状态、无持久化结算规则。 */
public interface MarketSettlementCalculator {

    /** 此计算器唯一支持的市场。 */
    MarketTypeEnum supportedMarket();

    /**
     * 根据权威赛果事实和调用方选项计算结算状态。
     *
     * @param input 包含市场、选项、赛果及（HHAD 时）官方让球的输入
     * @return 待结算、命中、未中或作废状态
     */
    SettlementStatusEnum calculate(MarketSettlementInputDto input);
}
