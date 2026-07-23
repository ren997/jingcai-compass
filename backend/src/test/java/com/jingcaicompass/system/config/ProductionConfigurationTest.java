package com.jingcaicompass.system.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionConfigurationTest {

    @Test
    void requiresBaseInfrastructureCredentialsWithoutEmbeddedDefaults() throws IOException {
        PropertySource<?> application = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .getFirst();
        MutablePropertySources sources = new MutablePropertySources();
        sources.addLast(application);
        PropertySourcesPropertyResolver resolver = new PropertySourcesPropertyResolver(sources);

        assertThatThrownBy(() -> resolver.getProperty("spring.datasource.url"))
                .hasMessageContaining("DB_URL");
        assertThatThrownBy(() -> resolver.getProperty("spring.datasource.password"))
                .hasMessageContaining("DB_PASSWORD");
        assertThatThrownBy(() -> resolver.getProperty("spring.data.redis.host"))
                .hasMessageContaining("REDIS_HOST");
        assertThat(application.getProperty("spring.profiles.active")).isEqualTo("local");
        assertThat(application.getProperty("app.asian-odds.api-key")).isEqualTo("${ASIAN_ODDS_API_KEY:}");
    }

    @Test
    void requiresProductionInfrastructureCredentialsFromEnvironment() throws IOException {
        PropertySource<?> production = new YamlPropertySourceLoader()
                .load("production", new ClassPathResource("application-prod.yml"))
                .getFirst();
        MutablePropertySources sources = new MutablePropertySources();
        sources.addLast(production);
        PropertySourcesPropertyResolver resolver = new PropertySourcesPropertyResolver(sources);

        assertThatThrownBy(() -> resolver.getProperty("spring.datasource.url"))
                .hasMessageContaining("DB_URL");
        assertThatThrownBy(() -> resolver.getProperty("spring.data.redis.host"))
                .hasMessageContaining("REDIS_HOST");

        sources.addFirst(new MapPropertySource("deployment-secrets", Map.of(
                "DB_URL", "jdbc:postgresql://db.example.invalid/app",
                "DB_USERNAME", "app",
                "DB_PASSWORD", "secret",
                "REDIS_HOST", "redis.example.invalid",
                "REDIS_PORT", "6379",
                "REDIS_PASSWORD", "secret"
        )));

        assertThat(resolver.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://db.example.invalid/app");
        assertThat(resolver.getProperty("spring.data.redis.host"))
                .isEqualTo("redis.example.invalid");
        assertThat(production.getProperty("springdoc.api-docs.enabled"))
                .isEqualTo("${SPRINGDOC_ENABLED:false}");
    }
}
