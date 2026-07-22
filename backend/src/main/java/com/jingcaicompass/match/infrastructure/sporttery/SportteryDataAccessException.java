package com.jingcaicompass.match.infrastructure.sporttery;

import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;

public class SportteryDataAccessException extends BusinessException {

    public SportteryDataAccessException(String message) {
        super(ErrorCode.DATA_SOURCE_UNAVAILABLE, message);
    }

    public SportteryDataAccessException(String message, Throwable cause) {
        super(ErrorCode.DATA_SOURCE_UNAVAILABLE, message, cause);
    }
}
