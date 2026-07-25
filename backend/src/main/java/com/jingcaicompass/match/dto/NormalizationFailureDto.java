package com.jingcaicompass.match.dto;

/** 单场标准化失败摘要。 */
public record NormalizationFailureDto(
        Long matchId,
        String lotteryMatchNo,
        String message
) {
}
