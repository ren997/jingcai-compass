package com.jingcaicompass.history.dto;

import com.jingcaicompass.prediction.enums.PredictionTypeEnum;
import com.jingcaicompass.settlement.enums.MarketTypeEnum;
import com.jingcaicompass.settlement.enums.SettlementStatusEnum;
import java.time.LocalDate;
import java.util.Set;

/**
 * 公开历史列表筛选；日期按竞彩业务日解释。
 *
 * <p>{@code predictionType} 与 {@code settlementMarket} 是一对：市场决定"按哪个市场看结算状态"，
 * 类型决定"看哪条产品线的预测"。两者不一致的请求会被拒绝而不是静默返回空页——体彩预测永远没有
 * 亚盘结算，反过来也一样，按错误组合过滤的结果是"没有数据"，而用户会把它读成"这段时间没预测"。
 *
 * <p>缺省时类型由市场反推（亚盘市场 → 亚盘预测，其余 → 体彩），市场由类型取第一个市场，
 * 保持旧调用方不传类型时的行为不变。
 */
public record HistoryListQueryDto(
        LocalDate startDate,
        LocalDate endDate,
        Long leagueId,
        String modelVersion,
        Boolean lockedOnly,
        MarketTypeEnum settlementMarket,
        Set<SettlementStatusEnum> settlementStatuses,
        Integer pageNo,
        Integer pageSize,
        PredictionTypeEnum predictionType
) {

    /** 未指定产品线时的兼容构造器。 */
    public HistoryListQueryDto(
            LocalDate startDate,
            LocalDate endDate,
            Long leagueId,
            String modelVersion,
            Boolean lockedOnly,
            MarketTypeEnum settlementMarket,
            Set<SettlementStatusEnum> settlementStatuses,
            Integer pageNo,
            Integer pageSize
    ) {
        this(
                startDate,
                endDate,
                leagueId,
                modelVersion,
                lockedOnly,
                settlementMarket,
                settlementStatuses,
                pageNo,
                pageSize,
                null
        );
    }
}
