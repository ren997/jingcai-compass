package com.jingcaicompass.backtest.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * 创建一次回测。
 *
 * <p>没有 {@code providerCode} / 版本号字段：那不是用户输入，是服务端在入队时从运行中的
 * 代码与配置里取的。让调用方传版本号，等于允许「用 A 版本的标签跑 B 版本的代码」。</p>
 *
 * @param decisionLeadMinutes 决策截面距开赛的分钟数；空表示用配置默认值（105）
 * @param parameterSets       阈值参数组；空表示只跑基线一组
 * @param confirmBudget       预估调用次数超过阈值时的二次确认；未确认则拒绝入队
 */
public record BacktestRunCreateDto(
        @NotBlank(message = "runName must not be blank")
        @Size(max = 64, message = "runName must be at most 64 characters")
        String runName,
        @NotNull(message = "dateFrom must not be null")
        LocalDate dateFrom,
        @NotNull(message = "dateTo must not be null")
        LocalDate dateTo,
        List<String> leagueNames,
        Integer decisionLeadMinutes,
        @Valid List<BacktestParameterSetDto> parameterSets,
        Boolean confirmBudget
) {

    public BacktestRunCreateDto {
        leagueNames = leagueNames == null ? List.of() : List.copyOf(leagueNames);
        parameterSets = parameterSets == null ? List.of() : List.copyOf(parameterSets);
        confirmBudget = Boolean.TRUE.equals(confirmBudget);
    }
}
