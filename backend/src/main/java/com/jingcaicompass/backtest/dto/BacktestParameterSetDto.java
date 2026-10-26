package com.jingcaicompass.backtest.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 一个阈值参数组。
 *
 * <p>四个阈值就是 {@code AsianModelProperties} 里那四个置信度阈值。刻意<b>不</b>开放
 * {@code neighborTolerance} / {@code dispersionTolerance} / {@code maxGoals}：
 * 前两个是「市场是否自洽」的数据质量门槛，改动它们等于换问题而不是换参数；
 * {@code maxGoals} 是比分矩阵结构，改它会让 λ 本身变化、四个阈值失去可比性。</p>
 *
 * <p>{@code groupKey} 由调用方给：它是结论表里区分参数组的唯一标识，自动生成的话
 * 两次提交同样的阈值会得到两个 key，「对比」页就会把同一组参数当成两组。</p>
 */
public record BacktestParameterSetDto(
        @NotBlank(message = "groupKey must not be blank")
        @Size(max = 16, message = "groupKey must be at most 16 characters")
        String groupKey,
        @NotNull(message = "highConfidenceMargin must not be null")
        @DecimalMin(value = "0.0000") @DecimalMax("1.0000")
        BigDecimal highConfidenceMargin,
        @NotNull(message = "mediumConfidenceMargin must not be null")
        @DecimalMin(value = "0.0000") @DecimalMax("1.0000")
        BigDecimal mediumConfidenceMargin,
        @NotNull(message = "highDirectionSignal must not be null")
        @DecimalMin(value = "0.0000") @DecimalMax("1.0000")
        BigDecimal highDirectionSignal,
        @NotNull(message = "mediumDirectionSignal must not be null")
        @DecimalMin(value = "0.0000") @DecimalMax("1.0000")
        BigDecimal mediumDirectionSignal
) {
}
