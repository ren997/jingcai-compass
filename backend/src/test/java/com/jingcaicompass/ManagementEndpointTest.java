package com.jingcaicompass;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "management.server.address=127.0.0.1",
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration"
})
class ManagementEndpointTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @LocalServerPort
    private int serverPort;

    @LocalManagementPort
    private int managementPort;

    @Test
    void exposesHealthMetricsAndPrometheusOnlyOnManagementPort() {
        ResponseEntity<String> health = restTemplate.getForEntity(managementUrl("/actuator/health"), String.class);
        ResponseEntity<String> metrics = restTemplate.getForEntity(managementUrl("/actuator/metrics"), String.class);
        ResponseEntity<String> prometheus = restTemplate.getForEntity(managementUrl("/actuator/prometheus"), String.class);
        ResponseEntity<String> mainPort = restTemplate.getForEntity(mainUrl("/actuator/health"), String.class);

        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(metrics.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(prometheus.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(prometheus.getBody()).contains("jvm_");
        assertThat(mainPort.getStatusCode()).isNotEqualTo(HttpStatus.OK);
    }

    private String managementUrl(String path) {
        return "http://127.0.0.1:" + managementPort + path;
    }

    private String mainUrl(String path) {
        return "http://127.0.0.1:" + serverPort + path;
    }
}
