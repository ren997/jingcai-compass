package com.jingcaicompass.admin.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;

/** 管理员账号生命周期状态枚举 */
@Getter
public enum AdminAccountStatusEnum {
    ACTIVE("ACTIVE", "启用"),
    DISABLED("DISABLED", "停用");

    public static final String DESC = "管理员账号状态: ACTIVE-启用, DISABLED-停用";

    private static final Map<String, AdminAccountStatusEnum> CODE_MAP = Stream.of(values())
            .collect(Collectors.toMap(AdminAccountStatusEnum::getCode, Function.identity()));

    /** 持久化与对外编码 */
    @EnumValue
    @JsonValue
    private final String code;

    /** 可读说明 */
    private final String desc;

    AdminAccountStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /** 按编码解析状态 */
    public static AdminAccountStatusEnum fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
