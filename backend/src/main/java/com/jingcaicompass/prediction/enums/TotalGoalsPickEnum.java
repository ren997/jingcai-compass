package com.jingcaicompass.prediction.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;

/** 亚盘大小球按主盘总进球线计算的方向枚举。 */
@Getter
public enum TotalGoalsPickEnum {
    OVER("OVER", "大球"),
    UNDER("UNDER", "小球");

    public static final String DESC = "亚盘大小球方向: OVER-大球, UNDER-小球";

    private static final Map<String, TotalGoalsPickEnum> CODE_MAP = Stream.of(values())
            .collect(Collectors.toMap(TotalGoalsPickEnum::getCode, Function.identity()));

    /** 持久化与对外编码。 */
    @EnumValue
    @JsonValue
    private final String code;

    /** 可读说明。 */
    private final String desc;

    TotalGoalsPickEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /** 按编码解析枚举。 */
    public static TotalGoalsPickEnum fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
