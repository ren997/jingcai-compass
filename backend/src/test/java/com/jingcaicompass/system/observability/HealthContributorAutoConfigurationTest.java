package com.jingcaicompass.system.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.data.redis.RedisHealthContributorAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.health.HealthContributorAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.jdbc.DataSourceHealthContributorAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;

class HealthContributorAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    HealthContributorAutoConfiguration.class,
                    DataSourceHealthContributorAutoConfiguration.class,
                    RedisHealthContributorAutoConfiguration.class
            ))
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class));

    @Test
    void registersDatabaseAndRedisHealthContributorsWhenClientsAreConfigured() {
        contextRunner.run(context -> {
            assertThat(context).hasBean("dbHealthContributor");
            assertThat(context).hasBean("redisHealthContributor");
        });
    }
}
