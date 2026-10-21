package com.jingcaicompass.settlement.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.jingcaicompass.prediction.enums.PredictionTypeEnum;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;

/** 支持的结算市场；竞彩市场由体彩预测使用，亚盘市场由亚盘预测使用。 */
@Getter
public enum MarketTypeEnum {
    HAD("HAD", "胜平负"),
    HHAD("HHAD", "让球胜平负"),
    ASIAN_HANDICAP("ASIAN_HANDICAP", "亚盘让球"),
    ASIAN_TOTAL_GOALS("ASIAN_TOTAL_GOALS", "亚盘大小球");

    public static final String DESC = "结算市场: HAD-胜平负, HHAD-让球胜平负, "
            + "ASIAN_HANDICAP-亚盘让球, ASIAN_TOTAL_GOALS-亚盘大小球";

    /** 体彩预测结算的市场，顺序固定。 */
    private static final List<MarketTypeEnum> SPORTTERY_MARKETS = List.of(HAD, HHAD);

    /** 亚盘预测结算的市场，顺序固定。 */
    private static final List<MarketTypeEnum> ASIAN_MARKETS = List.of(ASIAN_HANDICAP, ASIAN_TOTAL_GOALS);

    private static final Map<String, MarketTypeEnum> CODE_MAP = Stream.of(values())
            .collect(Collectors.toMap(MarketTypeEnum::getCode, Function.identity()));

    /** 持久化与对外编码。 */
    @EnumValue
    @JsonValue
    private final String code;

    /** 可读说明。 */
    private final String desc;

    MarketTypeEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /** 按编码解析枚举。 */
    public static MarketTypeEnum fromCode(String code) {
        return CODE_MAP.get(code);
    }

    /**
     * 某类预测应当结算的市场集合。
     *
     * <p>历史数据中 {@code prediction_type} 允许为空，按体彩预测处理，保持原有结算口径不变。
     * 两类预测的市场必须互不交叉，否则任一侧都会因缺少另一侧的输入而中断整条预测的结算。</p>
     *
     * @param predictionType 预测类型，为空时按体彩预测处理
     * @return 该类预测需要检查的不可变市场列表
     */
    public static List<MarketTypeEnum> marketsFor(PredictionTypeEnum predictionType) {
        return predictionType == PredictionTypeEnum.ASIAN ? ASIAN_MARKETS : SPORTTERY_MARKETS;
    }

    /** 是否为亚盘市场。 */
    public boolean isAsian() {
        return this == ASIAN_HANDICAP || this == ASIAN_TOTAL_GOALS;
    }
}
