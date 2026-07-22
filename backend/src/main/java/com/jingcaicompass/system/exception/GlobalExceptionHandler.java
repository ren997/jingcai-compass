package com.jingcaicompass.system.exception;

import com.jingcaicompass.system.api.ApiResponse;
import com.jingcaicompass.system.infrastructure.TraceIdContext;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception
    ) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> "%s: %s".formatted(error.getField(), error.getDefaultMessage()))
                .findFirst()
                .orElse(ErrorCode.INVALID_PARAMETER.defaultMessage());
        return failure(ErrorCode.INVALID_PARAMETER, message);
    }

    @ExceptionHandler(BindException.class)
    ResponseEntity<ApiResponse<Void>> handleBind(BindException exception) {
        String message = exception.getFieldErrors().stream()
                .map(error -> "%s: %s".formatted(error.getField(), error.getDefaultMessage()))
                .findFirst()
                .orElse(ErrorCode.INVALID_PARAMETER.defaultMessage());
        return failure(ErrorCode.INVALID_PARAMETER, message);
    }

    @ExceptionHandler({
            ConstraintViolationException.class,
            HandlerMethodValidationException.class,
            HttpMessageNotReadableException.class
    })
    ResponseEntity<ApiResponse<Void>> handleValidation(Exception exception) {
        return failure(ErrorCode.INVALID_PARAMETER, ErrorCode.INVALID_PARAMETER.defaultMessage());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiResponse<Void>> handleMissingParameter(
            MissingServletRequestParameterException exception
    ) {
        return failure(
                ErrorCode.INVALID_PARAMETER,
                "缺少请求参数：%s".formatted(exception.getParameterName())
        );
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiResponse<Void>> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception
    ) {
        return failure(
                ErrorCode.INVALID_PARAMETER,
                "请求参数格式错误：%s".formatted(exception.getName())
        );
    }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException exception) {
        log.warn(
                "业务请求失败，traceId={}，code={}，message={}",
                TraceIdContext.currentOrCreate(),
                exception.errorCode().code(),
                exception.getMessage()
        );
        return failure(exception.errorCode(), exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> handleUnknown(Exception exception) {
        log.error(
                "未处理的服务异常，traceId={}",
                TraceIdContext.currentOrCreate(),
                exception
        );
        return failure(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage());
    }

    private ResponseEntity<ApiResponse<Void>> failure(ErrorCode errorCode, String message) {
        return ResponseEntity
                .status(errorCode.httpStatus())
                .body(ApiResponse.failure(errorCode, message));
    }
}
