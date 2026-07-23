package com.jingcaicompass.system.provider;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderHttpRetryTest {

    private MockWebServer server;
    private final List<Duration> sleeps = new ArrayList<>();
    private ProviderHttpExecutor executor;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        sleeps.clear();
        executor = new ProviderHttpExecutor(duration -> sleeps.add(duration));
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void retries429RespectingRetryAfterThenSucceeds() {
        server.enqueue(new MockResponse()
                .setResponseCode(429)
                .addHeader(HttpHeaders.RETRY_AFTER, "2")
                .setBody("limited"));
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader(ProviderQuotaHeaders.HEADER_REQUESTS_REMAINING, "5")
                .addHeader(ProviderQuotaHeaders.HEADER_REQUESTS_USED, "10")
                .setBody("{\"ok\":true}"));

        ProviderHttpResponse response = executor.get(
                restClient(Duration.ofSeconds(2)),
                "THE_ODDS_API",
                ProviderHttpRequest.of("/v4/sports"),
                new ProviderRetryPolicy(3, Duration.ofMillis(500)),
                1
        );

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("ok");
        assertThat(response.retryCount()).isEqualTo(1);
        assertThat(response.quotaCost()).isEqualTo(1);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(2));
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void retries5xxUntilMaxAttemptsThenFails() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("err1"));
        server.enqueue(new MockResponse().setResponseCode(500).setBody("err2"));

        assertThatThrownBy(() -> executor.get(
                restClient(Duration.ofSeconds(2)),
                "STUB",
                ProviderHttpRequest.of("/pool"),
                new ProviderRetryPolicy(2, Duration.ofMillis(100)),
                0
        ))
                .isInstanceOf(ProviderHttpException.class)
                .satisfies(exception -> {
                    ProviderHttpException httpException = (ProviderHttpException) exception;
                    assertThat(httpException.category()).isEqualTo(ProviderErrorCategory.UPSTREAM_FAILURE);
                    assertThat(httpException.httpStatus()).isEqualTo(500);
                    assertThat(httpException.retryCount()).isEqualTo(1);
                });

        assertThat(server.getRequestCount()).isEqualTo(2);
        assertThat(sleeps).containsExactly(Duration.ofMillis(100));
    }

    @Test
    void doesNotRetry400Or401() {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("bad"));

        assertThatThrownBy(() -> executor.get(
                restClient(Duration.ofSeconds(2)),
                "STUB",
                ProviderHttpRequest.of("/pool?apiKey=secret-key"),
                new ProviderRetryPolicy(3, Duration.ofMillis(100)),
                0
        ))
                .isInstanceOf(ProviderHttpException.class)
                .satisfies(exception -> {
                    ProviderHttpException httpException = (ProviderHttpException) exception;
                    assertThat(httpException.category()).isEqualTo(ProviderErrorCategory.INVALID_PARAMETER);
                    assertThat(httpException.retryCount()).isZero();
                    assertThat(httpException.getMessage()).doesNotContain("secret-key");
                });

        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(sleeps).isEmpty();

        server.enqueue(new MockResponse().setResponseCode(401).setBody("unauthorized"));
        assertThatThrownBy(() -> executor.get(
                restClient(Duration.ofSeconds(2)),
                "STUB",
                ProviderHttpRequest.of("/pool"),
                new ProviderRetryPolicy(3, Duration.ofMillis(100)),
                0
        ))
                .isInstanceOf(ProviderHttpException.class)
                .satisfies(exception -> assertThat(((ProviderHttpException) exception).category())
                        .isEqualTo(ProviderErrorCategory.INVALID_PARAMETER));
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void retriesTimeoutThenSucceeds() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"recovered\":true}"));

        ProviderHttpResponse response = executor.get(
                restClient(Duration.ofMillis(200)),
                "THE_ODDS_API",
                ProviderHttpRequest.of("/v4/sports"),
                new ProviderRetryPolicy(2, Duration.ofMillis(50)),
                0
        );

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.retryCount()).isEqualTo(1);
        assertThat(response.body()).contains("recovered");
        assertThat(sleeps).containsExactly(Duration.ofMillis(50));
    }

    private RestClient restClient(Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(200))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder()
                .baseUrl(server.url("/").toString())
                .requestFactory(factory)
                .build();
    }
}
