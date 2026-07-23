package com.jingcaicompass.system.config;

import com.jingcaicompass.match.client.SportteryProviderProperties;
import com.jingcaicompass.match.client.SportteryProviderType;
import com.jingcaicompass.odds.client.AsianOddsProviderProperties;
import com.jingcaicompass.odds.enums.AsianOddsProviderTypeEnum;
import com.jingcaicompass.system.config.properties.PaginationProperties;
import com.jingcaicompass.system.config.properties.SyncTaskProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationConfigurationPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues(
                    "app.sporttery.provider=china",
                    "app.sporttery.base-url=https://webapi.sporttery.cn",
                    "app.sporttery.connect-timeout=5s",
                    "app.sporttery.read-timeout=10s",
                    "app.sporttery.retry.max-attempts=2",
                    "app.sporttery.retry.delay=500ms",
                    "app.sporttery.quota-warning-threshold=0",
                    "app.asian-odds.provider=stub",
                    "app.asian-odds.base-url=https://api.the-odds-api.com",
                    "app.asian-odds.api-key=secret-key",
                    "app.asian-odds.connect-timeout=5s",
                    "app.asian-odds.read-timeout=10s",
                    "app.asian-odds.retry.max-attempts=2",
                    "app.asian-odds.retry.delay=500ms",
                    "app.asian-odds.quota-warning-threshold=0",
                    "app.pagination.max-page-size=100",
                    "app.tasks.enabled=false",
                    "app.tasks.sporttery-pool.enabled=false",
                    "app.tasks.sporttery-pool.fixed-delay=15m",
                    "app.tasks.sporttery-pool.initial-delay=30s"
            );

    @Test
    void bindsTypedProviderAndTaskProperties() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            SportteryProviderProperties sporttery = context.getBean(SportteryProviderProperties.class);
            assertThat(sporttery.provider()).isEqualTo(SportteryProviderType.CHINA);
            assertThat(sporttery.baseUrl()).isEqualTo(URI.create("https://webapi.sporttery.cn"));
            assertThat(sporttery.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(sporttery.readTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(sporttery.retry().maxAttempts()).isEqualTo(2);
            assertThat(sporttery.retry().delay()).isEqualTo(Duration.ofMillis(500));

            AsianOddsProviderProperties asianOdds = context.getBean(AsianOddsProviderProperties.class);
            assertThat(asianOdds.provider()).isEqualTo(AsianOddsProviderTypeEnum.STUB);
            assertThat(asianOdds.baseUrl()).isEqualTo(URI.create("https://api.the-odds-api.com"));
            assertThat(asianOdds.apiKey()).isEqualTo("secret-key");
            assertThat(asianOdds.toString()).doesNotContain("secret-key");
            assertThat(asianOdds.toString()).contains("apiKey=***");

            SyncTaskProperties tasks = context.getBean(SyncTaskProperties.class);
            assertThat(tasks.enabled()).isFalse();
            assertThat(tasks.sportteryPool().enabled()).isFalse();
            assertThat(tasks.sportteryPool().fixedDelay()).isEqualTo(Duration.ofMinutes(15));

            PaginationProperties pagination = context.getBean(PaginationProperties.class);
            assertThat(pagination.maxPageSize()).isEqualTo(100);
        });
    }

    @Test
    void rejectsConnectTimeoutBelowOneSecond() {
        contextRunner
                .withPropertyValues("app.sporttery.connect-timeout=0s")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("app.sporttery.connect-timeout");
                });
    }

    @Test
    void rejectsAsianOddsConnectTimeoutBelowOneSecond() {
        contextRunner
                .withPropertyValues("app.asian-odds.connect-timeout=0s")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("app.asian-odds.connect-timeout");
                });
    }

    @Test
    void rejectsRetryAttemptsBelowOne() {
        contextRunner
                .withPropertyValues("app.sporttery.retry.max-attempts=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("app.sporttery.retry.max-attempts");
                });
    }

    @Test
    void rejectsPageSizeBelowOne() {
        contextRunner
                .withPropertyValues("app.pagination.max-page-size=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("app.pagination.max-page-size");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
            SportteryProviderProperties.class,
            AsianOddsProviderProperties.class,
            SyncTaskProperties.class,
            PaginationProperties.class
    })
    static class PropertiesConfiguration {
    }
}
