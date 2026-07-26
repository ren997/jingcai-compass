package com.jingcaicompass.settlement.dto;

import com.jingcaicompass.match.entity.MatchResultFact;
import com.jingcaicompass.prediction.enums.HandicapPickEnum;
import com.jingcaicompass.settlement.enums.MarketTypeEnum;
import java.math.BigDecimal;

/** 市场结算纯函数的完整输入。 */
public record MarketSettlementInputDto(
        /** 要结算的体彩市场。 */
        MarketTypeEnum marketType,
        /** 调用方已确定的主胜、平或客胜选项。 */
        HandicapPickEnum selectedOutcome,
        /** 权威且版本化的赛果事实。 */
        MatchResultFact matchResultFact,
        /** 体彩比赛池中的主队官方让球；HAD 可为空。 */
        BigDecimal officialHandicap
) {
}
