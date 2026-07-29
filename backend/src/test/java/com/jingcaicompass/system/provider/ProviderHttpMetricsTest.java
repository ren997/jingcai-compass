package com.jingcaicompass.system.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jingcaicompass.system.observability.ProviderMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

class ProviderHttpMetricsTest {

    private MockWebServer server;
    private SimpleMeterRegistry registry;
    private ProviderHttpExecutor executor;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        registry = new SimpleMeterRegistry();
        executor = new ProviderHttpExecutor(duration -> { }, new ProviderMetrics(registry));
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
        registry.close();
    }

    @Test
    void recordsSuccess4295xxAndRetriesWithStableTags() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        executor.get(restClient(), "THE_ODDS_API", ProviderHttpRequest.of("/ok"), policy(1), 0);

        server.enqueue(new MockResponse().setResponseCode(429).setBody("limited"));
        assertThatThrownBy(() -> executor.get(restClient(), "THE_ODDS_API", ProviderHttpRequest.of("/quota"), policy(1), 0))
                .isInstanceOf(ProviderHttpException.class);

        server.enqueue(new MockResponse().setResponseCode(500).setBody("upstream"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        executor.get(restClient(), "THE_ODDS_API", ProviderHttpRequest.of("/retry"), policy(2), 0);

        assertThat(counter("success")).isEqualTo(2.0);
        assertThat(counter("http_429")).isEqualTo(1.0);
        assertThat(counter("http_5xx")).isEqualTo(1.0);
        assertThat(registry.get("jingcai.provider.retry").tag("provider", "THE_ODDS_API").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void recordsTimeoutAndItsRetry() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

        executor.get(restClient(Duration.ofMillis(200)), "THE_ODDS_API", ProviderHttpRequest.of("/timeout"), policy(2), 0);

        assertThat(counter("timeout")).isEqualTo(1.0);
        assertThat(counter("success")).isEqualTo(1.0);
        assertThat(registry.get("jingcai.provider.retry").tag("provider", "THE_ODDS_API").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void recordsClient4xxWithoutUsingRequestPathAsATag() {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("bad"));

        assertThatThrownBy(() -> executor.get(
                restClient(), "THE_ODDS_API", ProviderHttpRequest.of("/bad?apiKey=never-a-tag"), policy(1), 0
        )).isInstanceOf(ProviderHttpException.class);

        assertThat(counter("http_4xx")).isEqualTo(1.0);
        assertThat(registry.getMeters().stream().flatMap(meter -> meter.getId().getTags().stream())
                .map(tag -> tag.getKey()).toList())
                .doesNotContain("path", "url", "traceId");
    }

    private double counter(String result) {
        return registry.get("jingcai.provider.request")
                .tags("provider", "THE_ODDS_API", "result", result)
                .counter()
                .count();
    }

    private ProviderRetryPolicy policy(int maxAttempts) {
        return new ProviderRetryPolicy(maxAttempts, Duration.ZERO);
    }

    private RestClient restClient() {
        return restClient(Duration.ofSeconds(2));
    }

    private RestClient restClient(Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()
        );
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().baseUrl(server.url("/").toString()).requestFactory(factory).build();
    }
}
