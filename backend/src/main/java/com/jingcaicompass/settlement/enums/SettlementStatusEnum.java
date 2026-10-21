package com.jingcaicompass.settlement.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;

/**
 * 预测市场的结算展示状态。
 *
 * <p>竞彩市场只产出 PENDING/HIT/MISS/VOID；亚盘市场额外使用 PUSH（走盘，退还本金）与
 * HALF_WIN/HALF_LOSS（四分之一盘拆盘后的半赢半输）。PUSH 与 VOID 的净收益都为零，
 * 但语义不同：PUSH 是盘口打平后的正常退本，VOID 是比赛被取消或中止导致整场作废。</p>
 */
@Getter
public enum SettlementStatusEnum {
    PENDING("PENDING", "待结算"),
    HIT("HIT", "命中"),
    MISS("MISS", "未中"),
    VOID("VOID", "作废"),
    PUSH("PUSH", "走盘"),
    HALF_WIN("HALF_WIN", "半赢"),
    HALF_LOSS("HALF_LOSS", "半输");

    public static final String DESC =
            "结算状态: PENDING-待结算, HIT-命中, MISS-未中, VOID-作废, "
                    + "PUSH-走盘, HALF_WIN-半赢, HALF_LOSS-半输";

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
