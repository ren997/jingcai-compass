package com.jingcaicompass.prediction.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;

/** 亚盘让球按主客队计算的赢盘方向枚举。 */
@Getter
public enum AsianHandicapPickEnum {
    HOME_COVER("HOME_COVER", "主队赢盘"),
    AWAY_COVER("AWAY_COVER", "客队赢盘");

    public static final String DESC = "亚盘让球赢盘方向: HOME_COVER-主队赢盘, AWAY_COVER-客队赢盘";

    private static final Map<String, AsianHandicapPickEnum> CODE_MAP = Stream.of(values())
            .collect(Collectors.toMap(AsianHandicapPickEnum::getCode, Function.identity()));

    /** 持久化与对外编码。 */
    @EnumValue
    @JsonValue
    private final String code;

    /** 可读说明。 */
    private final String desc;

    AsianHandicapPickEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /** 按编码解析枚举。 */
    public static AsianHandicapPickEnum fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
