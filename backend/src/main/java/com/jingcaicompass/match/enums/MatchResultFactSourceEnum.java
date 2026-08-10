package com.jingcaicompass.match.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;

/** 赛果事实来源，人工补录不能宣称为官方来源。 */
@Getter
public enum MatchResultFactSourceEnum {
    OFFICIAL("OFFICIAL", "官方赛果"),
    MANUAL("MANUAL", "人工补录，非官方源");

    public static final String DESC = "赛果事实来源: OFFICIAL-官方赛果, MANUAL-人工补录，非官方源";

    private static final Map<String, MatchResultFactSourceEnum> CODE_MAP = Stream.of(values())
            .collect(Collectors.toMap(MatchResultFactSourceEnum::getCode, Function.identity()));

    @EnumValue
    @JsonValue
    private final String code;

    private final String desc;

    MatchResultFactSourceEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static MatchResultFactSourceEnum fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
