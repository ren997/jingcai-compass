package com.jingcaicompass.admin.service;

import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 统一管理员用户名规范化和首次引导密码策略。 */
@Component
public class AdminAccountCredentialValidator {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("[a-z0-9._-]{3,64}");

    /** 规范化并校验管理员登录名。 */
    public String normalizeUsername(String username) {
        if (!StringUtils.hasText(username)) {
            throw new IllegalArgumentException("admin username must not be blank");
        }
        String normalized = username.trim().toLowerCase(Locale.ROOT);
        if (!USERNAME_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "admin username must contain only lowercase letters, digits, dot, underscore or hyphen"
            );
        }
        return normalized;
    }

    /** 校验首次引导密码；密码原值不做 trim，避免改变认证语义。 */
    public void validateBootstrapPassword(String password) {
        if (!StringUtils.hasText(password) || password.length() < 12 || password.length() > 128) {
            throw new IllegalArgumentException(
                    "admin bootstrap password length must be between 12 and 128"
            );
        }
    }
}
