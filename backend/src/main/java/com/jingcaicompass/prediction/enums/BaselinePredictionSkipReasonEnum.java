package com.jingcaicompass.prediction.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;

/** 首版预测基线排除比赛的稳定原因。 */
@Getter
public enum BaselinePredictionSkipReasonEnum {
    NOT_SCHEDULED("NOT_SCHEDULED", "比赛不处于可生成的未开赛状态"),
    KICKOFF_PASSED("KICKOFF_PASSED", "比赛已开赛或缺少开赛时间"),
    MISSING_SPORTTERY_MARKET("MISSING_SPORTTERY_MARKET", "缺少完整体彩让球与 SP 快照"),
    INVALID_SPORTTERY_MARKET("INVALID_SPORTTERY_MARKET", "体彩 SP 必须为正数"),
    MISSING_CONFIRMED_ASIAN_MARKET("MISSING_CONFIRMED_ASIAN_MARKET", "缺少已确认映射的完整亚盘和大小球快照"),
    INVALID_ASIAN_MARKET("INVALID_ASIAN_MARKET", "亚盘和大小球水位必须为正数");

    public static final String DESC =
            "预测基线跳过原因：NOT_SCHEDULED-非未开赛，KICKOFF_PASSED-已开赛，"
                    + "MISSING_SPORTTERY_MARKET-缺体彩盘口，INVALID_SPORTTERY_MARKET-体彩盘口无效，"
                    + "MISSING_CONFIRMED_ASIAN_MARKET-缺已确认亚盘，INVALID_ASIAN_MARKET-亚盘无效";

    private static final Map<String, BaselinePredictionSkipReasonEnum> CODE_MAP = Stream.of(values())
            .collect(Collectors.toMap(BaselinePredictionSkipReasonEnum::getCode, Function.identity()));

    /** 持久化与对外编码。 */
    @EnumValue
    @JsonValue
    private final String code;

    /** 可读说明。 */
    private final String desc;

    BaselinePredictionSkipReasonEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /** 按编码读取枚举。 */
    public static BaselinePredictionSkipReasonEnum fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
