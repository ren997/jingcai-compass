package com.jingcaicompass.match.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;

/** 联赛/球队标准化解析结果类型 */
@Getter
public enum EntityNormalizeOutcomeEnum {
    RESOLVED("RESOLVED", "已解析到标准实体"),
    CANDIDATE_CREATED("CANDIDATE_CREATED", "已创建候选实体");

    public static final String DESC =
            "标准化结果: RESOLVED-已解析到标准实体, CANDIDATE_CREATED-已创建候选实体";

    private static final Map<String, EntityNormalizeOutcomeEnum> CODE_MAP = Stream.of(values())
            .collect(Collectors.toMap(EntityNormalizeOutcomeEnum::getCode, Function.identity()));

    @EnumValue
    @JsonValue
    private final String code;

    private final String desc;

    EntityNormalizeOutcomeEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static EntityNormalizeOutcomeEnum fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
