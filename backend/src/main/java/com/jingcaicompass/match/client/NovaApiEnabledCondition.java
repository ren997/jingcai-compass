package com.jingcaicompass.match.client;

import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ConfigurationCondition.ConfigurationPhase;

/**
 * 「本进程要访问 NovaAPI」的装配条件。
 *
 * <p>NovaAPI 是单源方案里赛程、赔率、赛果共用的同一个接口契约（同一个 {@code matchId}、
 * 同一套 Ed25519 签名），所以签名客户端与 RestClient 必须按「任一业务线用到就装配」来判断，
 * 不能像阶段 1 那样只绑在 {@code app.sporttery.provider} 上。</p>
 *
 * <p>写成 OR 而不是给两个属性各配一个 bean：客户端持有的是<b>同一份</b>凭据与限速桶，
 * 装配两份会让每分钟 30 次的额度被两个实例各算一遍。也不能只判断其中一个属性——
 * 单源改造期间赛程与赔率是分阶段切换的，中间必然存在「赛程已 nova、赔率还是 the-odds」的状态。</p>
 */
public class NovaApiEnabledCondition extends AnyNestedCondition {

    public NovaApiEnabledCondition() {
        super(ConfigurationPhase.PARSE_CONFIGURATION);
    }

    /** 赛程/赛果侧使用 NovaAPI。 */
    @ConditionalOnProperty(name = "app.sporttery.provider", havingValue = "nova")
    static class ScheduleUsesNova {
    }

    /** 亚盘赔率侧使用 NovaAPI。 */
    @ConditionalOnProperty(name = "app.asian-odds.provider", havingValue = "NOVA_API")
    static class OddsUseNova {
    }
}
