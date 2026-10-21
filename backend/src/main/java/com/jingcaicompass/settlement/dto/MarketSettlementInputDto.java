package com.jingcaicompass.settlement.dto;

import com.jingcaicompass.match.entity.MatchResultFact;
import com.jingcaicompass.prediction.enums.AsianHandicapPickEnum;
import com.jingcaicompass.prediction.enums.HandicapPickEnum;
import com.jingcaicompass.prediction.enums.TotalGoalsPickEnum;
import com.jingcaicompass.settlement.enums.MarketTypeEnum;
import java.math.BigDecimal;

/** 市场结算纯函数的完整输入。 */
public record MarketSettlementInputDto(
        /** 要结算的市场。 */
        MarketTypeEnum marketType,
        /** 调用方已确定的主胜、平或客胜选项；仅竞彩市场使用。 */
        HandicapPickEnum selectedOutcome,
        /** 权威且版本化的赛果事实。 */
        MatchResultFact matchResultFact,
        /** 体彩比赛池中的主队官方让球；HAD 可为空。 */
        BigDecimal officialHandicap,
        /** 亚盘让球赢盘方向；仅 ASIAN_HANDICAP 使用。 */
        AsianHandicapPickEnum asianHandicapPick,
        /** 亚盘大小球方向；仅 ASIAN_TOTAL_GOALS 使用。 */
        TotalGoalsPickEnum totalGoalsPick,
        /** 预测生成时锁定的盘口线：让球线或大小球线，步长为 0.25。 */
        BigDecimal marketLine
) {

    /**
     * 竞彩市场的构造入口：亚盘字段全部为空，保证两类市场的输入不会互相污染。
     *
     * @param marketType        HAD 或 HHAD
     * @param selectedOutcome   三向选项
     * @param matchResultFact   权威赛果事实
     * @param officialHandicap  官方让球，HAD 时为空
     */
    public MarketSettlementInputDto(
            MarketTypeEnum marketType,
            HandicapPickEnum selectedOutcome,
            MatchResultFact matchResultFact,
            BigDecimal officialHandicap
    ) {
        this(marketType, selectedOutcome, matchResultFact, officialHandicap, null, null, null);
    }
}
