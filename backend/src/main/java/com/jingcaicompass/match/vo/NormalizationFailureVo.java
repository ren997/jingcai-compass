package com.jingcaicompass.match.vo;

import com.jingcaicompass.match.dto.NormalizationFailureDto;

/**
 * 单场标准化失败摘要视图。
 *
 * @param matchId 内部比赛 ID
 * @param lotteryMatchNo 体彩场次编号
 * @param message 已截断的异常摘要
 */
public record NormalizationFailureVo(
        Long matchId,
        String lotteryMatchNo,
        String message
) {

    /** 将内部失败摘要转换为接口视图。 */
    public static NormalizationFailureVo from(NormalizationFailureDto source) {
        return new NormalizationFailureVo(
                source.matchId(),
                source.lotteryMatchNo(),
                source.message()
        );
    }
}
