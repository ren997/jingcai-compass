package com.jingcaicompass.admin.dto;

import com.jingcaicompass.match.enums.MatchResultFactStatusEnum;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 管理员受控人工赛果补录请求；不能承载或修改结算结果。 */
public record AdminManualMatchResultDto(
        /** 内部比赛 ID。 */
        @NotNull Long matchId,
        /** 仅允许 FINAL 或 VOID，由服务端校验。 */
        @NotNull MatchResultFactStatusEnum factStatus,
        /** FINAL 必须为 FINISHED；VOID 仅允许 CANCELLED 或 ABANDONED。 */
        @NotNull MatchStatusEnum matchStatus,
        /** FINAL 的主队最终进球数。 */
        @Min(0) @Max(99) Integer homeScore,
        /** FINAL 的客队最终进球数。 */
        @Min(0) @Max(99) Integer awayScore,
        /** 已核实证据的来源说明；明确不是官方数据源声明。 */
        @NotBlank @Size(max = 500) String sourceNote,
        /** 本次补录或修正原因。 */
        @NotBlank @Size(max = 500) String entryReason,
        /** 前端确认弹窗后必须回传 true。 */
        @AssertTrue Boolean confirmed
) {
}
