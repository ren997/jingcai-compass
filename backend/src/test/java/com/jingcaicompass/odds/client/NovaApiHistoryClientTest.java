package com.jingcaicompass.odds.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.match.client.NovaApiClient;
import com.jingcaicompass.system.provider.ProviderErrorCategory;
import com.jingcaicompass.system.provider.ProviderException;
import com.jingcaicompass.system.provider.ProviderHttpException;
import com.jingcaicompass.system.provider.ProviderHttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/**
 * 历史赔率客户端的五类失败分类。
 *
 * <p>这里的断言全部是关于<b>归因</b>的，不是关于 HTTP 本身的：同样一个「没采到」，
 * 可能是「上游确实没有这场历史」（终局，可负缓存）、「上游偶发 500」（下次重采）、
 * 「凭据失效」（立刻中断整批）。三者混成一类，重采策略就只能靠猜。</p>
 */
class NovaApiHistoryClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void successReportsUpstreamCodeAndPayload() {
        StubClient stub = new StubClient(response(200, "{\"code\":200,\"data\":{\"providers\":[]}}"), null);
        NovaApiHistoryClient client = client(stub);

        NovaApiHistoryClient.HistoryFetch fetch = client.fetch("2042155");

        assertThat(fetch.success()).isTrue();
        assertThat(fetch.noHistory()).isFalse();
        assertThat(fetch.isBodyLevelRetryable()).isFalse();
        assertThat(fetch.payload().path("data").isObject()).isTrue();
        assertThat(stub.calls).isEqualTo(1);
    }

    @Test
    void httpNotFoundIsTerminalNoHistoryNotFailure() {
        ProviderHttpException notFound = new ProviderHttpException(
                "NOVA_API", ProviderErrorCategory.INVALID_PARAMETER, "provider HTTP 404", 0, 1, 404);
        StubClient stub = new StubClient(null, notFound);
        NovaApiHistoryClient client = client(stub);

        NovaApiHistoryClient.HistoryFetch fetch = client.fetch("2040339");

        assertThat(fetch.noHistory()).as("404 = 上游没有这场历史，是终局结论").isTrue();
        assertThat(fetch.httpStatus()).isEqualTo(404);
        assertThat(fetch.payload()).isNull();
        assertThat(stub.calls).as("404 不该被重试").isEqualTo(1);
    }

    @Test
    void codeNotFoundInBodyAlsoMeansNoHistory() {
        // 同一件事在 HTTP 200 下也出现过：只看 HTTP 状态会把这类场次永远当成「失败」。
        StubClient stub = new StubClient(response(200, "{\"code\":404,\"msg\":\"未找到赔率变动记录\"}"), null);
        NovaApiHistoryClient client = client(stub);

        assertThat(client.fetch("2040339").noHistory()).isTrue();
    }

    @Test
    void upstreamBodyLevel500IsRetriedOnceThenReported() {
        StubClient stub = new StubClient(
                response(200, "{\"code\":500,\"msg\":\"网络请求异常：Failed to connect\"}"), null);
        NovaApiHistoryClient client = client(stub);

        NovaApiHistoryClient.HistoryFetch fetch = client.fetch("2041514");

        assertThat(fetch.success()).isFalse();
        assertThat(fetch.isBodyLevelRetryable()).as("响应体级 500 是可重试").isTrue();
        assertThat(stub.calls).as("响应体级 500 自己再试一次，共 2 次调用").isEqualTo(2);
    }

    @Test
    void authenticationFailureFailsFastInsteadOfDegrading() {
        ProviderHttpException unauthorized = new ProviderHttpException(
                "NOVA_API", ProviderErrorCategory.INVALID_PARAMETER, "provider HTTP 401", 0, 1, 401);
        StubClient stub = new StubClient(null, unauthorized);
        NovaApiHistoryClient client = client(stub);

        assertThatThrownBy(() -> client.fetch("2042155"))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("鉴权失败");
    }

    @Test
    void emptyBodyIsRejectedAsParseFailure() {
        StubClient stub = new StubClient(response(200, ""), null);
        NovaApiHistoryClient client = client(stub);

        NovaApiHistoryClient.HistoryFetch fetch = client.fetch("2042155");

        assertThat(fetch.success()).isFalse();
        assertThat(fetch.payload()).isNull();
    }

    @Test
    void invalidJsonIsRejectedAsParseFailure() {
        StubClient stub = new StubClient(response(200, "<html>gateway</html>"), null);
        NovaApiHistoryClient client = client(stub);

        assertThatThrownBy(() -> client.fetch("2042155"))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("不是合法 JSON");
    }

    @Test
    void blankMatchIdIsRejectedBeforeAnyCall() {
        StubClient stub = new StubClient(response(200, "{\"code\":200}"), null);
        NovaApiHistoryClient client = client(stub);

        assertThatThrownBy(() -> client.fetch("  "))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("externalMatchId 为空");
        assertThat(stub.calls).isZero();
    }

    private NovaApiHistoryClient client(StubClient stub) {
        return new NovaApiHistoryClient(stub, objectMapper);
    }

    private static ProviderHttpResponse response(int status, String body) {
        return new ProviderHttpResponse(status, body, 0, 1, new HttpHeaders());
    }

    /** 直接替换传输层，只验证分类逻辑；签名与限速不在本用例关注范围内。 */
    private static final class StubClient extends NovaApiClient {

        private final ProviderHttpResponse response;
        private final RuntimeException failure;
        private int calls;

        private StubClient(ProviderHttpResponse response, RuntimeException failure) {
            super(null, null, null, null);
            this.response = response;
            this.failure = failure;
        }

        @Override
        public ProviderHttpResponse getHistory(
                String providerCode,
                String path,
                Map<String, String> params
        ) {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return response;
        }
    }
}
