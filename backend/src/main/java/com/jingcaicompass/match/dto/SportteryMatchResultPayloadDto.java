package com.jingcaicompass.match.dto;

import java.util.List;

/** 体彩赛果原始响应的内部规范化根对象。 */
public record SportteryMatchResultPayloadDto(
        /** 原始响应中解析出的赛果列表。 */
        List<SportteryMatchResultDto> results
) {
}
