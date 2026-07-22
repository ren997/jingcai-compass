package com.jingcaicompass.system.api;

import com.jingcaicompass.system.exception.ErrorCode;
import com.jingcaicompass.system.infrastructure.TraceIdContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiResponseAndPageResultTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void buildsSuccessAndFailureResponsesWithCurrentTraceId() {
        MDC.put(TraceIdContext.MDC_KEY, "response-test");

        ApiResponse<String> success = ApiResponse.success("ok");
        ApiResponse<Void> failure = ApiResponse.failure(ErrorCode.ACCESS_DENIED);

        assertThat(success.code()).isEqualTo(ErrorCode.SUCCESS.code());
        assertThat(success.data()).isEqualTo("ok");
        assertThat(success.traceId()).isEqualTo("response-test");
        assertThat(failure.code()).isEqualTo(ErrorCode.ACCESS_DENIED.code());
        assertThat(failure.traceId()).isEqualTo("response-test");
    }

    @Test
    void copiesPageRecordsAndRejectsInvalidMetadata() {
        List<String> source = new ArrayList<>(List.of("first"));
        PageResult<String> page = new PageResult<>(source, 1, 20, 1);
        source.add("second");

        assertThat(page.records()).containsExactly("first");
        assertThatThrownBy(() -> page.records().add("third"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new PageResult<>(List.of(), 0, 20, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pageNo");
        assertThatThrownBy(() -> new PageResult<>(List.of(), 1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pageSize");
    }
}
