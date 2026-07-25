package com.jingcaicompass.match.dto;

/**
 * 单场标准化失败摘要。
 *
 * @param matchId 内部比赛 ID
 * @param lotteryMatchNo 体彩场次编号
 * @param message 已截断的异常摘要
 */
public record NormalizationFailureDto(
        Long matchId,
        String lotteryMatchNo,
        String message
) {
}
