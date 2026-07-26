package com.jingcaicompass.settlement.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;

/** 预测市场的结算展示状态。 */
@Getter
public enum SettlementStatusEnum {
    PENDING("PENDING", "待结算"),
    HIT("HIT", "命中"),
    MISS("MISS", "未中"),
    VOID("VOID", "作废");

    public static final String DESC =
            "结算状态: PENDING-待结算, HIT-命中, MISS-未中, VOID-作废";

    private static final Map<String, SettlementStatusEnum> CODE_MAP = Stream.of(values())
            .collect(Collectors.toMap(SettlementStatusEnum::getCode, Function.identity()));

    /** 持久化与对外编码。 */
    @EnumValue
    @JsonValue
    private final String code;

    /** 可读说明。 */
    private final String desc;

    SettlementStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /** 按编码解析枚举。 */
    public static SettlementStatusEnum fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
