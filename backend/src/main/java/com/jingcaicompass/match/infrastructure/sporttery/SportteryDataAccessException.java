package com.jingcaicompass.match.infrastructure.sporttery;

public class SportteryDataAccessException extends RuntimeException {

    public SportteryDataAccessException(String message) {
        super(message);
    }

    public SportteryDataAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
