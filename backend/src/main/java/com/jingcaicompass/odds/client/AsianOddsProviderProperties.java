package com.jingcaicompass.odds.client;

import com.jingcaicompass.odds.enums.AsianOddsProviderTypeEnum;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 亚盘赔率侧的数据源选择。
 *
 * <p>与 {@code SportteryProviderProperties} 同理：单源之后只剩「选哪个 provider」与
 * 「额度告警阈值」。原先的 base-url / api-key / 超时 / 重试 / The Odds sport key 与累计预算，
 * 都随 {@code TheOddsApiProvider} 一并删除——NovaAPI 的凭据、限速与重试在 {@code app.nova} 下，
 * 两个源共用一个额度桶，分开配只会让「额度被谁用掉」算不清。</p>
 */
@Validated
@ConfigurationProperties("app.asian-odds")
public record AsianOddsProviderProperties(
        @NotNull AsianOddsProviderTypeEnum provider,
        @PositiveOrZero int quotaWarningThreshold
) {
}
