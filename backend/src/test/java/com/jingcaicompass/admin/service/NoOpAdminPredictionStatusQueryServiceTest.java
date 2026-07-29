package com.jingcaicompass.admin.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import org.junit.jupiter.api.Test;

class NoOpAdminPredictionStatusQueryServiceTest {

    @Test
    void returnsExplicitDataSourceUnavailableForEveryReadPath() {
        NoOpAdminPredictionStatusQueryService service = new NoOpAdminPredictionStatusQueryService();

        assertThatThrownBy(() -> service.locks(null)).isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DATA_SOURCE_UNAVAILABLE);
        assertThatThrownBy(() -> service.settlements(null)).isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DATA_SOURCE_UNAVAILABLE);
        assertThatThrownBy(() -> service.detail(null)).isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DATA_SOURCE_UNAVAILABLE);
    }
}
