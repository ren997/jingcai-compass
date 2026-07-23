package com.jingcaicompass.system.provider;

import com.jingcaicompass.system.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderExceptionTest {

    @Test
    void mapsCategoriesToErrorCodes() {
        assertThat(new ProviderException("P", ProviderErrorCategory.INVALID_PARAMETER, "bad").errorCode())
                .isEqualTo(ErrorCode.INVALID_PARAMETER);
        assertThat(new ProviderException("P", ProviderErrorCategory.QUOTA_EXCEEDED, "quota").errorCode())
                .isEqualTo(ErrorCode.DATA_SOURCE_QUOTA_EXCEEDED);
        assertThat(ErrorCode.DATA_SOURCE_QUOTA_EXCEEDED.httpStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(new ProviderException("P", ProviderErrorCategory.UPSTREAM_FAILURE, "down").errorCode())
                .isEqualTo(ErrorCode.DATA_SOURCE_UNAVAILABLE);
        assertThat(new ProviderException("P", ProviderErrorCategory.PARSE_FAILURE, "parse").errorCode())
                .isEqualTo(ErrorCode.DATA_SOURCE_PARSE_FAILED);
    }
}
