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
        /**
         * 供应商侧赛果所属日期。
         *
         * <p>竞彩（{@code CHINA_SPORTTERY}）返回的是<b>销售日</b>；NovaAPI 返回的是
         * <b>开赛自然日</b>——跨零点场次会比销售日晚一天。因此它<b>不能</b>当作
         * {@code matches.lottery_date} 使用，赛果定位一律靠 {@link #matchId()}。</p>
         */
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
        /** 是否有供应商明确给出的官方最终作废结论。 */
        boolean officialVoid,
        /** 供应商侧更新时间。 */
        OffsetDateTime providerUpdatedAt
) {
}
