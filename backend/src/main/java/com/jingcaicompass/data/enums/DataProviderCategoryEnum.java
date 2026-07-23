package com.jingcaicompass.data.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;

/** Provider 分类枚举 */
@Getter
public enum DataProviderCategoryEnum {
    SPORTTERY("SPORTTERY", "体彩数据源"),
    ASIAN_ODDS("ASIAN_ODDS", "亚盘数据源"),
    OTHER("OTHER", "其他数据源");

    public static final String DESC =
            "Provider 分类: SPORTTERY-体彩数据源, ASIAN_ODDS-亚盘数据源, OTHER-其他数据源";

    private static final Map<String, DataProviderCategoryEnum> CODE_MAP = Stream.of(values())
            .collect(Collectors.toMap(DataProviderCategoryEnum::getCode, Function.identity()));

    @EnumValue
    @JsonValue
    private final String code;

    private final String desc;

    DataProviderCategoryEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static DataProviderCategoryEnum fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
