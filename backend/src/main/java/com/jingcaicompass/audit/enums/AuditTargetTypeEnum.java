package com.jingcaicompass.audit.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;

/** 审计目标实体类型 */
@Getter
public enum AuditTargetTypeEnum {
    MATCH_SOURCE_MAPPING("MATCH_SOURCE_MAPPING", "比赛来源映射");

    public static final String DESC = "审计目标: MATCH_SOURCE_MAPPING-比赛来源映射";

    private static final Map<String, AuditTargetTypeEnum> CODE_MAP = Stream.of(values())
            .collect(Collectors.toMap(AuditTargetTypeEnum::getCode, Function.identity()));

    @EnumValue
    @JsonValue
    private final String code;

    private final String desc;

    AuditTargetTypeEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static AuditTargetTypeEnum fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
