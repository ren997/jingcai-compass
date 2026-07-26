package com.jingcaicompass.match.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;

/** 赛果事实的结算资格状态。 */
@Getter
public enum MatchResultFactStatusEnum {
    PENDING("PENDING", "待确认"),
    FINAL("FINAL", "最终赛果"),
    VOID("VOID", "官方作废");

    public static final String DESC =
            "赛果事实状态: PENDING-待确认, FINAL-最终赛果, VOID-官方作废";

    private static final Map<String, MatchResultFactStatusEnum> CODE_MAP = Stream.of(values())
            .collect(Collectors.toMap(MatchResultFactStatusEnum::getCode, Function.identity()));

    /** 持久化与对外编码。 */
    @EnumValue
    @JsonValue
    private final String code;

    /** 可读说明。 */
    private final String desc;

    MatchResultFactStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /** 按编码解析枚举。 */
    public static MatchResultFactStatusEnum fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
