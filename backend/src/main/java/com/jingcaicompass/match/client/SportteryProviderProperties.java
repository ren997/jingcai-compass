package com.jingcaicompass.match.client;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 赛程/赛果侧的数据源选择。
 *
 * <p>单源之后这里只剩两件事：<b>选哪个 provider</b>（决定装配 {@code NovaSportteryProvider}
 * 还是 {@code StubSportteryProvider}），以及<b>额度告警阈值</b>（监控按它判「接近额度上限」）。
 * 连接超时、重试、base-url 已经统一收归 {@code app.nova}——NovaAPI 的赛程、赔率、赛果共用
 * 同一个客户端与同一份额度，再保留一套「按业务线分别配超时」的旋钮只会让人误以为它们生效。</p>
 */
@Validated
@ConfigurationProperties("app.sporttery")
public record SportteryProviderProperties(
        @NotNull SportteryProviderType provider,
        @PositiveOrZero int quotaWarningThreshold
) {
}
