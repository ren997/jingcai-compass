package com.jingcaicompass.system.observability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 跨接口、后台和日志复用的敏感文本与原始载荷脱敏器。 */
@Component
public class SensitiveDataSanitizer {

    public static final int MAX_FRAGMENT_LENGTH = 8_192;
    public static final int MAX_TEXT_LENGTH = 500;
    private static final int MAX_DEPTH = 8;
    private static final int MAX_ARRAY_ITEMS = 50;
    private static final Set<String> SENSITIVE_KEY_PARTS = Set.of(
            "password", "passwd", "apikey", "token", "authorization", "cookie",
            "secret", "credential", "bearer", "privatekey"
    );
    private static final Pattern URL_QUERY = Pattern.compile("(?i)(https?://[^\\s?#]+)\\?[^\\s,;]+");
    private static final Pattern HEADER_SECRET = Pattern.compile(
            "(?i)(authorization\\s*[:=]\\s*(?:bearer\\s+)?|api[-_ ]?key\\s*[:=]\\s*|token\\s*[:=]\\s*"
                    + "|password\\s*[:=]\\s*|secret\\s*[:=]\\s*|cookie\\s*[:=]\\s*)[^\\s,;]+"
    );

    private final ObjectMapper objectMapper;

    public SensitiveDataSanitizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 递归脱敏并序列化 JSON 片段。 */
    public SanitizedText sanitizePayload(Object payload) {
        try {
            String value = objectMapper.writeValueAsString(redact(objectMapper.valueToTree(payload), 0));
            return truncate(value, MAX_FRAGMENT_LENGTH);
        } catch (JsonProcessingException exception) {
            return new SanitizedText("已省略无法安全解析的原始响应", false);
        }
    }

    /** 脱敏错误、请求键和 URL 等普通文本。 */
    public String sanitizeText(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        String withoutQuery = URL_QUERY.matcher(value).replaceAll("$1");
        String masked = HEADER_SECRET.matcher(withoutQuery).replaceAll("$1***");
        return truncate(masked, MAX_TEXT_LENGTH).value();
    }

    private JsonNode redact(JsonNode node, int depth) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (depth >= MAX_DEPTH) {
            return TextNode.valueOf("已省略过深内容");
        }
        if (node.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            node.fields().forEachRemaining(entry -> result.set(
                    entry.getKey(),
                    isSensitiveKey(entry.getKey()) ? TextNode.valueOf("***") : redact(entry.getValue(), depth + 1)
            ));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            int count = 0;
            for (JsonNode item : node) {
                if (count++ >= MAX_ARRAY_ITEMS) {
                    result.add("已省略其余数组项");
                    break;
                }
                result.add(redact(item, depth + 1));
            }
            return result;
        }
        if (node.isTextual()) {
            String text = node.textValue();
            if (looksLikeJson(text)) {
                JsonNode nested = parseNestedJson(text);
                return nested == null
                        ? TextNode.valueOf("已省略无法安全解析的嵌套 JSON")
                        : redact(nested, depth + 1);
            }
            return TextNode.valueOf(sanitizeText(text));
        }
        return node;
    }

    private JsonNode parseNestedJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private static boolean looksLikeJson(String value) {
        return StringUtils.hasText(value)
                && (value.trim().startsWith("{") || value.trim().startsWith("["));
    }

    private boolean isSensitiveKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return SENSITIVE_KEY_PARTS.stream().anyMatch(normalized::contains);
    }

    private static SanitizedText truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return new SanitizedText(value, false);
        }
        return new SanitizedText(value.substring(0, maxLength) + "…", true);
    }

    /** 脱敏后的可展示文本及是否因长度截断。 */
    public record SanitizedText(String value, boolean truncated) {
    }
}
