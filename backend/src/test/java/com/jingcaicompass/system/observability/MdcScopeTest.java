package com.jingcaicompass.system.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class MdcScopeTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void addsBusinessIdOnlyForScopedStructuredLogAndRestoresPriorValue() {
        MDC.put("predictionId", "previous");

        try (MdcScope ignored = MdcScope.prediction(101L)) {
            assertThat(MDC.get("predictionId")).isEqualTo("101");
        }
        assertThat(MDC.get("predictionId")).isEqualTo("previous");

        try (MdcScope ignored = MdcScope.snapshot(501L)) {
            assertThat(MDC.get("snapshotId")).isEqualTo("501");
        }
        assertThat(MDC.get("snapshotId")).isNull();
    }
}
