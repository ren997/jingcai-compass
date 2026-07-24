package com.jingcaicompass.odds.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.odds.dto.AsianOddsMatchOddsDto;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 亚盘原始 JSON → 比赛盘口列表。 */
@Component
public class AsianOddsPayloadMapper {

    private final ObjectMapper objectMapper;

    public AsianOddsPayloadMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<AsianOddsMatchOddsDto> parseMatches(String payloadJson) {
        if (!StringUtils.hasText(payloadJson)) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(payloadJson);
            if (root == null || root.isNull()) {
                return List.of();
            }
            if (root.isArray()) {
                return readList(root);
            }
            if (root.isObject() && root.has("matches")) {
                return readList(root.get("matches"));
            }
            // RawDataPayloadService 对非对象 JSON 的兜底包装
            if (root.isObject() && root.has("raw") && root.get("raw").isTextual()) {
                return parseMatches(root.get("raw").asText());
            }
            return List.of();
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.DATA_SOURCE_PARSE_FAILED, "亚盘载荷解析失败", exception);
        }
    }

    private List<AsianOddsMatchOddsDto> readList(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        List<AsianOddsMatchOddsDto> matches = objectMapper.convertValue(node, new TypeReference<>() {
        });
        return matches == null ? List.of() : List.copyOf(matches);
    }
}
