package com.jingcaicompass.admin.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;

/** 管理员登录失败内部审计原因枚举 */
@Getter
public enum AdminLoginFailureReasonEnum {
    UNKNOWN_USERNAME("UNKNOWN_USERNAME", "账号不存在", false),
    INVALID_PASSWORD("INVALID_PASSWORD", "密码错误", true),
    ACCOUNT_DISABLED("ACCOUNT_DISABLED", "账号已停用", false),
    ACCOUNT_LOCKED("ACCOUNT_LOCKED", "账号临时锁定", false);

    public static final String DESC =
            "登录失败原因: UNKNOWN_USERNAME-账号不存在, INVALID_PASSWORD-密码错误, "
                    + "ACCOUNT_DISABLED-账号已停用, ACCOUNT_LOCKED-账号临时锁定";

    private static final Map<String, AdminLoginFailureReasonEnum> CODE_MAP = Stream.of(values())
            .collect(Collectors.toMap(AdminLoginFailureReasonEnum::getCode, Function.identity()));

    /** 内部审计编码 */
    @EnumValue
    @JsonValue
    private final String code;

    /** 可读说明 */
    private final String desc;

    /** 是否计入连续失败锁定阈值 */
    private final boolean countTowardLock;

    AdminLoginFailureReasonEnum(String code, String desc, boolean countTowardLock) {
        this.code = code;
        this.desc = desc;
        this.countTowardLock = countTowardLock;
    }

    /** 按编码解析失败原因 */
    public static AdminLoginFailureReasonEnum fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
