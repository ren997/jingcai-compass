package com.jingcaicompass.prediction.job;

import com.jingcaicompass.system.config.PersistenceServicesAutoConfiguration;
import com.jingcaicompass.system.observability.JobMetrics;
import com.jingcaicompass.prediction.service.PredictionAutoPublishOrchestrator;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自动发布任务的装配形态守卫。
 *
 * <p>2026-10-23 起线上再没生成过任何预测，首页长期显示「暂无已发布亚盘预测」，而日志里
 * 一条告警都没有。根因是本类曾被标成 {@code @Component} +
 * {@code @ConditionalOnBean(PredictionAutoPublishOrchestrator.class)}：编排器是配置类里的
 * {@code @Bean}，组件扫描阶段尚未注册，条件恒为 false，任务对象根本没被创建。</p>
 *
 * <p>其余七个任务都同时存在 {@code @Bean} 定义兜底，只有它没有，所以只有它静默失效。
 * 这两个断言分别锁住「不要再加 @Component（会与 @Bean 版本重复）」和
 * 「@Bean 装配必须继续存在」。</p>
 */
class PredictionAutoPublishJobWiringTest {

    @Test
    void isNotAComponentSoTheConfigurationBeanOwnsWiring() {
        assertThat(PredictionAutoPublishJob.class.isAnnotationPresent(Component.class))
                .as("PredictionAutoPublishJob 必须由 PersistenceServicesAutoConfiguration 以 @Bean 装配；"
                        + "标 @Component 会让条件退化成扫描顺序依赖")
                .isFalse();
    }

    @Test
    void isDeclaredAsConditionalBeanInPersistenceConfiguration() throws NoSuchMethodException {
        Method factory = PersistenceServicesAutoConfiguration.class.getDeclaredMethod(
                "predictionAutoPublishJob",
                PredictionAutoPublishOrchestrator.class,
                JobMetrics.class
        );

        assertThat(factory.isAnnotationPresent(Bean.class)).isTrue();
        ConditionalOnProperty property = factory.getAnnotation(ConditionalOnProperty.class);
        assertThat(property).isNotNull();
        assertThat(property.prefix()).isEqualTo("app.tasks");
        assertThat(property.name()).containsExactly("enabled", "prediction-auto-publish.enabled");
        assertThat(property.havingValue()).isEqualTo("true");
    }
}
