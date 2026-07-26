package com.jingcaicompass.prediction.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.prediction.dto.PredictionImportBatchDto;
import com.jingcaicompass.prediction.dto.PredictionImportFileDto;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/** 严格解析离线模型 JSON，并基于原始文件字节生成批次哈希。 */
@Component
public class PredictionImportFileParserImpl implements PredictionImportFileParser {

    private static final String PREDICTIONS_FIELD = "predictions";
    private static final String[] NUMERIC_FIELDS = {
            "matchId",
            "homeWinProb",
            "drawProb",
            "awayWinProb",
            "expectedTotalGoals"
    };

    private final ObjectMapper objectMapper;

    public PredictionImportFileParserImpl(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    @Override
    public PredictionImportBatchDto parse(byte[] fileContent) {
        // 1) 空文件和非 UTF-8 内容直接拒绝，固定离线文件编码
        if (fileContent == null || fileContent.length == 0) {
            throw invalid("prediction import file must not be empty");
        }
        requireUtf8(fileContent);

        try {
            // 2) 先检查 JSON 结构和数值节点类型，禁止字符串或百分数字段混入
            JsonNode root = objectMapper.readTree(fileContent);
            requireImportStructure(root);

            // 3) 再按严格 DTO 契约映射，未知字段、非法枚举和尾随内容全部拒绝
            PredictionImportFileDto file = objectMapper.treeToValue(root, PredictionImportFileDto.class);

            // 4) 哈希只基于调用方提交的原始字节，不信任 JSON 内部声明
            return new PredictionImportBatchDto(
                    file.generationBatchId(),
                    sha256Hex(fileContent),
                    file.predictions()
            );
        } catch (IOException exception) {
            throw new BusinessException(
                    ErrorCode.INVALID_PARAMETER,
                    "prediction import file is invalid JSON",
                    exception
            );
        }
    }

    private void requireUtf8(byte[] content) {
        try {
            StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(content));
        } catch (CharacterCodingException exception) {
            throw new BusinessException(
                    ErrorCode.INVALID_PARAMETER,
                    "prediction import file must use UTF-8",
                    exception
            );
        }
    }

    private void requireImportStructure(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw invalid("prediction import root must be an object");
        }
        JsonNode predictions = root.get(PREDICTIONS_FIELD);
        if (predictions == null || !predictions.isArray()) {
            throw invalid("predictions must be an array");
        }
        if (predictions.isEmpty()) {
            throw invalid("predictions must not be empty");
        }
        for (int index = 0; index < predictions.size(); index++) {
            JsonNode item = predictions.get(index);
            if (!item.isObject()) {
                throw invalid("predictions[" + index + "] must be an object");
            }
            for (String field : NUMERIC_FIELDS) {
                JsonNode value = item.get(field);
                if (value != null && !value.isNull() && !value.isNumber()) {
                    throw invalid("predictions[" + index + "]." + field + " must be a JSON number");
                }
            }
            JsonNode matchId = item.get("matchId");
            if (matchId != null && !matchId.isNull() && !matchId.isIntegralNumber()) {
                throw invalid("predictions[" + index + "].matchId must be an integer");
            }
        }
    }

    private String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", exception);
        }
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.INVALID_PARAMETER, message);
    }
}
