package com.jingcaicompass.match.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;

/** 比赛业务状态枚举 */
@Getter
public enum MatchStatusEnum {
    /** 未开赛，仍可关注或售卖 */
    SCHEDULED("SCHEDULED", "未开赛"),
    /** 已截止售卖或进入锁定阶段 */
    LOCKED("LOCKED", "已锁定"),
    /** 比赛已结束 */
    FINISHED("FINISHED", "已结束"),
    /** 比赛延期 */
    POSTPONED("POSTPONED", "延期"),
    /** 比赛取消 */
    CANCELLED("CANCELLED", "取消");

    public static final String DESC =
            "比赛状态: SCHEDULED-未开赛, LOCKED-已锁定, FINISHED-已结束, POSTPONED-延期, CANCELLED-取消";

    private static final Map<String, MatchStatusEnum> CODE_MAP = Stream.of(values())
            .collect(Collectors.toMap(MatchStatusEnum::getCode, Function.identity()));

    /** 持久化与对外编码 */
    @EnumValue
    @JsonValue
    private final String code;

    /** 可读说明 */
    private final String desc;

    MatchStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /** 按编码解析枚举 */
    public static MatchStatusEnum fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
