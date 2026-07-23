package com.jingcaicompass.system.provider;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderHttpContractTest {

    private MockWebServer server;
    private ProviderHttpExecutor executor;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        executor = new ProviderHttpExecutor(duration -> {
        });
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void returnsBodyForAsianOddsStyleQuotaHeadersWithoutLeakingApiKey() throws InterruptedException {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader(ProviderQuotaHeaders.HEADER_REQUESTS_REMAINING, "3")
                .addHeader(ProviderQuotaHeaders.HEADER_REQUESTS_USED, "97")
                .setBody("{\"sports\":[]}"));

        String secret = "odds-api-secret-should-not-leak";
        ProviderHttpResponse response = executor.get(
                restClient(),
                "THE_ODDS_API",
                ProviderHttpRequest.of("/v4/sports", Map.of(HttpHeaders.AUTHORIZATION, "Bearer " + secret)),
                new ProviderRetryPolicy(2, Duration.ofMillis(100)),
                5
        );

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.quotaCost()).isEqualTo(1);
        assertThat(response.body()).doesNotContain(secret);
        assertThat(response.toString()).doesNotContain(secret);

        var recorded = server.takeRequest();
        assertThat(recorded.getHeader(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer " + secret);
    }

    @Test
    void maps429ToQuotaExceededWithoutRetryWhenAttemptsExhausted() {
        server.enqueue(new MockResponse().setResponseCode(429).setBody("quota"));

        assertThatThrownBy(() -> executor.get(
                restClient(),
                "THE_ODDS_API",
                ProviderHttpRequest.of("/v4/odds?apiKey=should-not-appear-in-message"),
                new ProviderRetryPolicy(1, Duration.ofMillis(100)),
                0
        ))
                .isInstanceOf(ProviderHttpException.class)
                .satisfies(exception -> {
                    ProviderHttpException httpException = (ProviderHttpException) exception;
                    assertThat(httpException.category()).isEqualTo(ProviderErrorCategory.QUOTA_EXCEEDED);
                    assertThat(httpException.getMessage()).doesNotContain("should-not-appear-in-message");
                    assertThat(httpException.getMessage()).doesNotContain("apiKey");
                });
    }

    @Test
    void parseRetryAfterSupportsDeltaSeconds() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "7");
        assertThat(ProviderQuotaHeaders.parseRetryAfter(headers)).contains(Duration.ofSeconds(7));
    }

    private RestClient restClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(2));
        return RestClient.builder()
                .baseUrl(server.url("/").toString())
                .requestFactory(factory)
                .build();
    }
}
