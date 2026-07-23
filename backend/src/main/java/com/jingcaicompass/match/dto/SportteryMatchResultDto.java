package com.jingcaicompass.match.dto;

import com.jingcaicompass.match.enums.MatchStatusEnum;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 体彩官方赛果（Stub / 真实适配器共用内部契约）。
 */
public record SportteryMatchResultDto(
        /** 供应商侧比赛 ID。 */
        String matchId,
        /** 竞彩销售日。 */
        LocalDate lotteryDate,
        /** 竞彩场次编号，例如周三001。 */
        String lotteryMatchNo,
        /** 主队比分；延期/取消可为空。 */
        Integer homeScore,
        /** 客队比分；延期/取消可为空。 */
        Integer awayScore,
        /** 赛果对应状态。 */
        MatchStatusEnum matchStatus,
        /** 是否为官方修正后的赛果。 */
        boolean amended,
        /** 供应商侧更新时间。 */
        OffsetDateTime providerUpdatedAt
) {
}
