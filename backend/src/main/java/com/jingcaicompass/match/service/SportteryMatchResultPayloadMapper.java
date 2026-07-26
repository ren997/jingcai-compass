package com.jingcaicompass.match.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.match.dto.SportteryMatchResultDto;
import com.jingcaicompass.match.dto.SportteryMatchResultPayloadDto;
import com.jingcaicompass.match.exception.SportteryDataAccessException;
import com.jingcaicompass.system.provider.ProviderErrorCategory;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 体彩赛果 raw JSON 到内部 Provider DTO 的严格解析器。 */
@Component
public class SportteryMatchResultPayloadMapper {

    private final ObjectMapper objectMapper;

    public SportteryMatchResultPayloadMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 解析规范化根对象中的赛果列表。 */
    public List<SportteryMatchResultDto> parseItems(String rawJson) {
        if (!StringUtils.hasText(rawJson)) {
            throw new SportteryDataAccessException(
                    ProviderErrorCategory.PARSE_FAILURE,
                    "体彩赛果原始响应为空"
            );
        }
        try {
            SportteryMatchResultPayloadDto payload = objectMapper.readValue(
                    rawJson,
                    SportteryMatchResultPayloadDto.class
            );
            if (payload == null || payload.results() == null) {
                throw new SportteryDataAccessException(
                        ProviderErrorCategory.PARSE_FAILURE,
                        "体彩赛果原始响应缺少 results"
                );
            }
            return List.copyOf(payload.results());
        } catch (JsonProcessingException exception) {
            throw new SportteryDataAccessException(
                    ProviderErrorCategory.PARSE_FAILURE,
                    "体彩赛果原始响应解析失败",
                    exception
            );
        }
    }
}
