package com.jingcaicompass.admin.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 后台盘口阶梯查询。
 *
 * <p>用 predictionId 而不是 matchId：运营是从某条草稿点进来的，关心的是「这一条预测当时看到的是
 * 哪条阶梯、冻结在哪一行」。用 matchId 还得再传一次预测 ID 才知道冻结行，等于把定位逻辑放到前端。
 *
 * @param predictionId 目标预测 ID（必须属于亚盘产品线）
 */
public record AdminOddsLadderQueryDto(
        @NotNull
        @Positive
        Long predictionId
) {
}
