package com.jingcaicompass.settlement.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;

/** MVP 支持的体彩结算市场。 */
@Getter
public enum MarketTypeEnum {
    HAD("HAD", "胜平负"),
    HHAD("HHAD", "让球胜平负");

    public static final String DESC = "结算市场: HAD-胜平负, HHAD-让球胜平负";

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
}
