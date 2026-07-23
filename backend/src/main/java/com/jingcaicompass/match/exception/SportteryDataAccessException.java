package com.jingcaicompass.match.exception;

import com.jingcaicompass.system.provider.ProviderErrorCategory;
import com.jingcaicompass.system.provider.ProviderException;

public class SportteryDataAccessException extends ProviderException {

    private static final String PROVIDER_CODE = "CHINA_SPORTTERY";

    public SportteryDataAccessException(String message) {
        super(PROVIDER_CODE, ProviderErrorCategory.UPSTREAM_FAILURE, message);
    }

    public SportteryDataAccessException(String message, Throwable cause) {
        super(PROVIDER_CODE, ProviderErrorCategory.UPSTREAM_FAILURE, message, cause);
    }

    public SportteryDataAccessException(ProviderErrorCategory category, String message) {
        super(PROVIDER_CODE, category, message);
    }

    public SportteryDataAccessException(ProviderErrorCategory category, String message, Throwable cause) {
        super(PROVIDER_CODE, category, message, cause);
    }
}
