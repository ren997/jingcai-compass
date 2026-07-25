package com.jingcaicompass.match.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;

/** 比赛标准实体待确认原因枚举。 */
@Getter
public enum NormalizationPendingReasonEnum {
    LEAGUE_PENDING("LEAGUE_PENDING", "联赛待确认"),
    HOME_TEAM_PENDING("HOME_TEAM_PENDING", "主队待确认"),
    AWAY_TEAM_PENDING("AWAY_TEAM_PENDING", "客队待确认");

    public static final String DESC =
            "标准化待确认原因: LEAGUE_PENDING-联赛待确认, "
                    + "HOME_TEAM_PENDING-主队待确认, AWAY_TEAM_PENDING-客队待确认";

    private static final Map<String, NormalizationPendingReasonEnum> CODE_MAP = Stream.of(values())
            .collect(Collectors.toMap(NormalizationPendingReasonEnum::getCode, Function.identity()));

    /** 持久化与接口编码。 */
    @EnumValue
    @JsonValue
    private final String code;

    /** 中文描述。 */
    private final String desc;

    NormalizationPendingReasonEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /** 按业务编码解析，未知编码返回 {@code null}。 */
    public static NormalizationPendingReasonEnum fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
