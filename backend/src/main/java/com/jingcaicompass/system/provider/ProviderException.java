package com.jingcaicompass.system.provider;

import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;

import java.util.Objects;

/**
 * 外部 Provider 调用失败时的统一业务异常。
 */
public class ProviderException extends BusinessException {

    private final String providerCode;
    private final ProviderErrorCategory category;

    public ProviderException(String providerCode, ProviderErrorCategory category, String message) {
        super(toErrorCode(category), message);
        this.providerCode = Objects.requireNonNull(providerCode, "providerCode must not be null");
        this.category = Objects.requireNonNull(category, "category must not be null");
    }

    public ProviderException(
            String providerCode,
            ProviderErrorCategory category,
            String message,
            Throwable cause
    ) {
        super(toErrorCode(category), message, cause);
        this.providerCode = Objects.requireNonNull(providerCode, "providerCode must not be null");
        this.category = Objects.requireNonNull(category, "category must not be null");
    }

    public String providerCode() {
        return providerCode;
    }

    public ProviderErrorCategory category() {
        return category;
    }

    private static ErrorCode toErrorCode(ProviderErrorCategory category) {
        return switch (Objects.requireNonNull(category, "category must not be null")) {
            case INVALID_PARAMETER -> ErrorCode.INVALID_PARAMETER;
            case QUOTA_EXCEEDED -> ErrorCode.DATA_SOURCE_QUOTA_EXCEEDED;
            case UPSTREAM_FAILURE -> ErrorCode.DATA_SOURCE_UNAVAILABLE;
            case PARSE_FAILURE -> ErrorCode.DATA_SOURCE_PARSE_FAILED;
        };
    }
}
