package com.jingcaicompass.admin.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AdminSensitiveDataSanitizerTest {

    private final AdminSensitiveDataSanitizer sanitizer = new AdminSensitiveDataSanitizer(new ObjectMapper());

    @Test
    void recursivelyMasksCredentialsNestedJsonAndUrlQueries() {
        AdminSensitiveDataSanitizer.SanitizedText result = sanitizer.sanitizePayload(Map.of(
                "apiKey", "top-secret",
                "nested", Map.of("Authorization", "Bearer signed-token", "cookie", "sid=secret"),
                "raw", "{\"password\":\"p@ss\",\"endpoint\":\"https://example.test/a?token=x\"}",
                "items", List.of(Map.of("refresh_token", "refresh-secret"))
        ));

        assertThat(result.value()).contains("\"apiKey\":\"***\"");
        assertThat(result.value()).contains("\"password\":\"***\"");
        assertThat(result.value()).contains("https://example.test/a");
        assertThat(result.value()).doesNotContain("top-secret", "signed-token", "sid=secret", "p@ss", "refresh-secret", "?token=");
    }

    @Test
    void masksSecretLikeErrorHeadersAndTruncatesLongText() {
        String result = sanitizer.sanitizeText("Authorization: Bearer secret-token https://example.test/api?apiKey=hidden");

        assertThat(result).contains("Authorization: Bearer ***", "https://example.test/api");
        assertThat(result).doesNotContain("secret-token", "apiKey=hidden");
        assertThat(sanitizer.sanitizeText("x".repeat(600))).hasSize(501);
    }

    @Test
    void omitsMalformedNestedJsonInsteadOfEchoingIt() {
        String malformed = "{\"token\":\"must-not-leak\"";

        AdminSensitiveDataSanitizer.SanitizedText result = sanitizer.sanitizePayload(Map.of("embedded", malformed));

        assertThat(result.value()).contains("已省略无法安全解析的嵌套 JSON");
        assertThat(result.value()).doesNotContain("must-not-leak");
    }
}
