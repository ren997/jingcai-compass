package com.jingcaicompass.odds.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;

/** 亚盘快照类型枚举 */
@Getter
public enum OddsSnapshotTypeEnum {
    FIRST_SEEN("FIRST_SEEN", "首次可见"),
    PRE_KICKOFF("PRE_KICKOFF", "封盘前"),
    OTHER("OTHER", "其他");

    public static final String DESC =
            "亚盘快照类型: FIRST_SEEN-首次可见, PRE_KICKOFF-封盘前, OTHER-其他";

    private static final Map<String, OddsSnapshotTypeEnum> CODE_MAP = Stream.of(values())
            .collect(Collectors.toMap(OddsSnapshotTypeEnum::getCode, Function.identity()));

    @EnumValue
    @JsonValue
    private final String code;

    private final String desc;

    OddsSnapshotTypeEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static OddsSnapshotTypeEnum fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
