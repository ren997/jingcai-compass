package com.jingcaicompass.odds.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;

/** 亚盘 Provider 类型枚举 */
@Getter
public enum AsianOddsProviderTypeEnum {
    /** 本地 Stub，不访问外部供应商 */
    STUB("STUB", "Stub 演示数据"),
    /** The Odds API 验证候选源 */
    THE_ODDS_API("THE_ODDS_API", "The Odds API");

    public static final String DESC =
            "亚盘 Provider 类型: STUB-Stub 演示数据, THE_ODDS_API-The Odds API";

    private static final Map<String, AsianOddsProviderTypeEnum> CODE_MAP = Stream.of(values())
            .collect(Collectors.toMap(AsianOddsProviderTypeEnum::getCode, Function.identity()));

    /** 持久化与配置编码 */
    @EnumValue
    @JsonValue
    private final String code;

    /** 可读说明 */
    private final String desc;

    AsianOddsProviderTypeEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /** 按编码解析枚举 */
    public static AsianOddsProviderTypeEnum fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
