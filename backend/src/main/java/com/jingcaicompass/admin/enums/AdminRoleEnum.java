package com.jingcaicompass.admin.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;

/** 管理员授权角色枚举 */
@Getter
public enum AdminRoleEnum {
    ADMIN("ADMIN", "管理员");

    public static final String DESC = "管理员角色: ADMIN-管理员";

    private static final Map<String, AdminRoleEnum> CODE_MAP = Stream.of(values())
            .collect(Collectors.toMap(AdminRoleEnum::getCode, Function.identity()));

    /** 持久化与 JWT 角色编码 */
    @EnumValue
    @JsonValue
    private final String code;

    /** 可读说明 */
    private final String desc;

    AdminRoleEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /** 按编码解析角色 */
    public static AdminRoleEnum fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
