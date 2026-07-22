package com.jingcaicompass.system.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    SUCCESS("SUCCESS", HttpStatus.OK, "操作成功"),
    INVALID_PARAMETER("COMMON_INVALID_PARAMETER", HttpStatus.BAD_REQUEST, "请求参数错误"),
    BUSINESS_ERROR("COMMON_BUSINESS_ERROR", HttpStatus.CONFLICT, "业务处理失败"),
    DATA_SOURCE_UNAVAILABLE("DATA_SOURCE_UNAVAILABLE", HttpStatus.BAD_GATEWAY, "外部数据源暂时不可用"),
    ACCESS_DENIED("COMMON_ACCESS_DENIED", HttpStatus.FORBIDDEN, "无权访问该资源"),
    INTERNAL_ERROR("COMMON_INTERNAL_ERROR", HttpStatus.INTERNAL_SERVER_ERROR, "服务暂时不可用");

    private final String code;
    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(String code, HttpStatus httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public String code() {
        return code;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
