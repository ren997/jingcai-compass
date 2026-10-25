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

/**
 * 亚盘原始 JSON → 比赛盘口列表。
 *
 * <p>单源（NovaAPI）之后只有一种聚合格式：{@code {"provider":"NOVA_API","responses":[...]}}，
 * 解析细节由 {@link NovaApiOddsPayloadMapper} 承担；本类保留的是「按信封结构分派」这一层，
 * 以及同步侧可能直接拿到的比赛数组格式。</p>
 */
@Component
public class AsianOddsPayloadMapper {

    private final ObjectMapper objectMapper;
    private final NovaApiOddsPayloadMapper novaApiOddsPayloadMapper;

    public AsianOddsPayloadMapper(
            ObjectMapper objectMapper,
            NovaApiOddsPayloadMapper novaApiOddsPayloadMapper
    ) {
        this.objectMapper = objectMapper;
        this.novaApiOddsPayloadMapper = novaApiOddsPayloadMapper;
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
            if (root.isObject() && NovaApiOddsPayloadMapper.PROVIDER_CODE.equals(root.path("provider").asText())
                    && root.has("responses")) {
                return novaApiOddsPayloadMapper.parse(root);
            }
            // RawDataPayloadService 对非对象 JSON 的兜底包装
            if (root.isObject() && root.has("raw") && root.get("raw").isTextual()) {
                return parseMatches(root.get("raw").asText());
            }
            // 未知结构必须抛错：这里曾经 return List.of()，结果是「换源后同步成功、零条快照、
            // 覆盖率 0%」，而调用方只会看到一份正常的同步记录——排查方向被彻底带偏。
            throw new BusinessException(
                    ErrorCode.DATA_SOURCE_PARSE_FAILED,
                    "无法识别的亚盘载荷结构（既非比赛数组，也非 NovaAPI 的聚合格式）"
            );
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
