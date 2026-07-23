package com.jingcaicompass.system.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * 对 RestClient 执行受控重试的 GET 调用。
 */
@Component
public class ProviderHttpExecutor {

    private static final Logger log = LoggerFactory.getLogger(ProviderHttpExecutor.class);

    private final ProviderSleeper sleeper;

    public ProviderHttpExecutor() {
        this(duration -> {
            if (duration != null && !duration.isZero() && !duration.isNegative()) {
                Thread.sleep(duration.toMillis());
            }
        });
    }

    public ProviderHttpExecutor(ProviderSleeper sleeper) {
        this.sleeper = sleeper;
    }

    /**
     * 执行 GET，按策略重试，并解析额度头。
     */
    public ProviderHttpResponse get(
            RestClient restClient,
            String providerCode,
            ProviderHttpRequest request,
            ProviderRetryPolicy policy,
            int quotaWarningThreshold
    ) {
        if (restClient == null) {
            throw new IllegalArgumentException("restClient must not be null");
        }
        if (!StringUtils.hasText(providerCode)) {
            throw new IllegalArgumentException("providerCode must not be blank");
        }
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }

        int attempt = 0;
        int quotaCost = 0;
        ProviderHttpException lastFailure = null;

        while (attempt < policy.maxAttempts()) {
            attempt++;
            int retryCount = attempt - 1;
            try {
                AttemptResult result = exchangeOnce(restClient, request);
                if (result.headers() != null
                        && (ProviderQuotaHeaders.parseRemaining(result.headers()).isPresent()
                        || ProviderQuotaHeaders.parseUsed(result.headers()).isPresent())) {
                    quotaCost++;
                    warnIfLowQuota(providerCode, result.headers(), quotaWarningThreshold);
                }

                int status = result.status();
                if (status >= 200 && status < 300) {
                    return new ProviderHttpResponse(
                            status,
                            result.body(),
                            retryCount,
                            Math.max(quotaCost, 0),
                            result.headers()
                    );
                }

                ProviderErrorCategory category = categorize(status);
                String message = "provider HTTP %d for %s".formatted(status, sanitizePath(request.path()));
                lastFailure = new ProviderHttpException(
                        providerCode, category, message, retryCount, quotaCost, status
                );

                if (policy.isNonRetryableClientError(status) || attempt >= policy.maxAttempts()) {
                    throw lastFailure;
                }
                if (!policy.isRetryableStatus(status)) {
                    throw lastFailure;
                }

                Duration wait = resolveWait(status, result.headers(), policy.delay());
                sleepQuietly(wait);
            } catch (ProviderHttpException exception) {
                throw exception;
            } catch (ResourceAccessException exception) {
                lastFailure = new ProviderHttpException(
                        providerCode,
                        ProviderErrorCategory.UPSTREAM_FAILURE,
                        "provider network/timeout for %s".formatted(sanitizePath(request.path())),
                        retryCount,
                        quotaCost,
                        null,
                        exception
                );
                if (attempt >= policy.maxAttempts()) {
                    throw lastFailure;
                }
                sleepQuietly(policy.delay());
            } catch (RuntimeException exception) {
                throw new ProviderHttpException(
                        providerCode,
                        ProviderErrorCategory.UPSTREAM_FAILURE,
                        "provider request failed for %s".formatted(sanitizePath(request.path())),
                        retryCount,
                        quotaCost,
                        null,
                        exception
                );
            }
        }

        if (lastFailure != null) {
            throw lastFailure;
        }
        throw new ProviderHttpException(
                providerCode,
                ProviderErrorCategory.UPSTREAM_FAILURE,
                "provider request exhausted retries",
                Math.max(policy.maxAttempts() - 1, 0),
                quotaCost,
                null
        );
    }

    private AttemptResult exchangeOnce(RestClient restClient, ProviderHttpRequest request) {
        return restClient.get()
                .uri(request.path())
                .headers(headers -> {
                    for (Map.Entry<String, String> entry : request.headers().entrySet()) {
                        headers.set(entry.getKey(), entry.getValue());
                    }
                })
                .exchange((clientRequest, clientResponse) -> {
                    HttpStatusCode statusCode = clientResponse.getStatusCode();
                    HttpHeaders headers = clientResponse.getHeaders();
                    String body = readBody(clientResponse.getBody());
                    return new AttemptResult(statusCode.value(), body, headers);
                });
    }

    private String readBody(InputStream bodyStream) throws IOException {
        if (bodyStream == null) {
            return "";
        }
        return new String(bodyStream.readAllBytes(), StandardCharsets.UTF_8);
    }

    private ProviderErrorCategory categorize(int status) {
        if (status == 429) {
            return ProviderErrorCategory.QUOTA_EXCEEDED;
        }
        if (status == 400 || status == 401 || status == 403) {
            return ProviderErrorCategory.INVALID_PARAMETER;
        }
        return ProviderErrorCategory.UPSTREAM_FAILURE;
    }

    private Duration resolveWait(int status, HttpHeaders headers, Duration fallback) {
        if (status == 429) {
            Optional<Duration> retryAfter = ProviderQuotaHeaders.parseRetryAfter(headers);
            if (retryAfter.isPresent()) {
                return retryAfter.get();
            }
        }
        return fallback;
    }

    private void warnIfLowQuota(String providerCode, HttpHeaders headers, int quotaWarningThreshold) {
        if (quotaWarningThreshold <= 0) {
            return;
        }
        ProviderQuotaHeaders.parseRemaining(headers).ifPresent(remaining -> {
            if (remaining <= quotaWarningThreshold) {
                log.warn("provider quota low providerCode={} remaining={} threshold={}",
                        providerCode, remaining, quotaWarningThreshold);
            }
        });
    }

    private void sleepQuietly(Duration wait) {
        if (wait == null || wait.isZero() || wait.isNegative()) {
            return;
        }
        try {
            sleeper.sleep(wait);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("provider retry sleep interrupted", exception);
        }
    }

    private static String sanitizePath(String path) {
        if (!StringUtils.hasText(path)) {
            return "";
        }
        // 避免把 query 里的 key 打进异常/日志
        int queryIndex = path.indexOf('?');
        return queryIndex >= 0 ? path.substring(0, queryIndex) : path;
    }

    private record AttemptResult(int status, String body, HttpHeaders headers) {
    }
}
