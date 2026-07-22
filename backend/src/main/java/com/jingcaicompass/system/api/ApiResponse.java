package com.jingcaicompass.system.api;

import com.jingcaicompass.system.exception.ErrorCode;
import com.jingcaicompass.system.infrastructure.TraceIdContext;

import java.util.Objects;

public record ApiResponse<T>(
        /** 稳定的机器可读结果码。 */
        String code,
        /** 面向调用方的结果说明。 */
        String message,
        /** 业务响应数据，失败时通常为空。 */
        T data,
        /** 用于关联响应与服务端日志的请求追踪标识。 */
        String traceId
) {

    public ApiResponse {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(traceId, "traceId must not be null");
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(
                ErrorCode.SUCCESS.code(),
                ErrorCode.SUCCESS.defaultMessage(),
                data,
                TraceIdContext.currentOrCreate()
        );
    }

    public static <T> ApiResponse<T> failure(ErrorCode errorCode) {
        return failure(errorCode, errorCode.defaultMessage());
    }

    public static <T> ApiResponse<T> failure(ErrorCode errorCode, String message) {
        Objects.requireNonNull(errorCode, "errorCode must not be null");
        String responseMessage = message == null || message.isBlank()
                ? errorCode.defaultMessage()
                : message;
        return new ApiResponse<>(
                errorCode.code(),
                responseMessage,
                null,
                TraceIdContext.currentOrCreate()
        );
    }
}
