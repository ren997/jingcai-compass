package com.jingcaicompass.system.provider;

import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * 解析 Retry-After 与常见供应商额度响应头。
 */
public final class ProviderQuotaHeaders {

    public static final String HEADER_REQUESTS_REMAINING = "x-requests-remaining";
    public static final String HEADER_REQUESTS_USED = "x-requests-used";

    private ProviderQuotaHeaders() {
    }

    /**
     * 解析 Retry-After：支持秒数或 HTTP-date；无法解析时返回 empty。
     */
    public static Optional<Duration> parseRetryAfter(HttpHeaders headers) {
        if (headers == null) {
            return Optional.empty();
        }
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (!StringUtils.hasText(value)) {
            return Optional.empty();
        }
        String trimmed = value.trim();
        try {
            long seconds = Long.parseLong(trimmed);
            if (seconds < 0) {
                return Optional.empty();
            }
            return Optional.of(Duration.ofSeconds(seconds));
        } catch (NumberFormatException ignored) {
            // fall through to HTTP-date
        }
        try {
            ZonedDateTime retryAt = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME);
            Duration wait = Duration.between(ZonedDateTime.now(retryAt.getZone()), retryAt);
            return Optional.of(wait.isNegative() ? Duration.ZERO : wait);
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    public static Optional<Integer> parseRemaining(HttpHeaders headers) {
        return parseIntHeader(headers, HEADER_REQUESTS_REMAINING);
    }

    public static Optional<Integer> parseUsed(HttpHeaders headers) {
        return parseIntHeader(headers, HEADER_REQUESTS_USED);
    }

    private static Optional<Integer> parseIntHeader(HttpHeaders headers, String name) {
        if (headers == null) {
            return Optional.empty();
        }
        String value = headers.getFirst(name);
        if (!StringUtils.hasText(value)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Integer.parseInt(value.trim()));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }
}
