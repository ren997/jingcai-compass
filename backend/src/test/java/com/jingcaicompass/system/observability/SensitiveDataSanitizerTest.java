package com.jingcaicompass.system.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SensitiveDataSanitizerTest {

    private final SensitiveDataSanitizer sanitizer = new SensitiveDataSanitizer(new ObjectMapper());

    @Test
    void masksCredentialsAndNeverEchoesMalformedNestedRawPayload() {
        SensitiveDataSanitizer.SanitizedText result = sanitizer.sanitizePayload(Map.of(
                "authorization", "Bearer top-secret",
                "nested", "{\"apiKey\":\"nested-secret\"}",
                "malformed", "{\"token\":\"must-not-leak\""
        ));

        assertThat(result.value()).contains("\"authorization\":\"***\"");
        assertThat(result.value()).contains("\"apiKey\":\"***\"");
        assertThat(result.value()).contains("已省略无法安全解析的嵌套 JSON");
        assertThat(result.value()).doesNotContain("top-secret", "nested-secret", "must-not-leak");
        assertThat(sanitizer.sanitizeText("Cookie: sid=secret https://example.test/a?token=hidden"))
                .doesNotContain("sid=secret", "token=hidden");
    }
}
