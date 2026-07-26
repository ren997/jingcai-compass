package com.jingcaicompass.audit.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;

/** 审计操作类型 */
@Getter
public enum AuditActionTypeEnum {
    CONFIRM("CONFIRM", "确认"),
    REJECT("REJECT", "拒绝"),
    REOPEN("REOPEN", "重新打开"),
    ADMIN_BOOTSTRAP("ADMIN_BOOTSTRAP", "创建首位管理员"),
    LOGIN_SUCCESS("LOGIN_SUCCESS", "登录成功"),
    LOGIN_FAILED("LOGIN_FAILED", "登录失败"),
    LOGOUT("LOGOUT", "退出登录"),
    ACCESS_DENIED("ACCESS_DENIED", "访问拒绝");

    public static final String DESC =
            "审计操作: CONFIRM-确认, REJECT-拒绝, REOPEN-重新打开, "
                    + "ADMIN_BOOTSTRAP-创建首位管理员, LOGIN_SUCCESS-登录成功, "
                    + "LOGIN_FAILED-登录失败, LOGOUT-退出登录, ACCESS_DENIED-访问拒绝";

    private static final Map<String, AuditActionTypeEnum> CODE_MAP = Stream.of(values())
            .collect(Collectors.toMap(AuditActionTypeEnum::getCode, Function.identity()));

    @EnumValue
    @JsonValue
    private final String code;

    private final String desc;

    AuditActionTypeEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static AuditActionTypeEnum fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
