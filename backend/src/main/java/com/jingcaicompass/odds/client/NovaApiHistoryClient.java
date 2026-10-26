package com.jingcaicompass.odds.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.match.client.NovaApiClient;
import com.jingcaicompass.system.provider.ProviderErrorCategory;
import com.jingcaicompass.system.provider.ProviderException;
import com.jingcaicompass.system.provider.ProviderHttpException;
import com.jingcaicompass.system.provider.ProviderHttpResponse;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * NovaAPI 历史赔率接口（{@code /nova/tf/odds-history}）的取数客户端。
 *
 * <p>与实时 {@code /nova/tf/odds} 的三个真实差异，决定了它必须是单独一个类而不是复用一个方法：</p>
 * <ol>
 *   <li><b>响应体积与耗时</b>：老场次实测 388KB / <b>20.8s</b>，而实时接口 1~2s。
 *       因此走 {@link NovaApiClient#getHistory} 的长超时客户端——用默认 20s 会表现为
 *       「这一场没有历史赔率」，而真实原因是读超时。</li>
 *   <li><b>失败语义不同</b>：404 是<b>终局结论</b>（上游确实没有这场的历史，通常是早于约 4 个月），
 *       而 HTTP 200 + 响应体 {@code code:500} 是<b>可重试</b>的上游网络异常
 *       （实测换 {@code idEvent} 即恢复）。两者一旦混成「失败」，重采策略就无从下手。</li>
 *   <li><b>鉴权失败必须立刻抛出</b>：401/403 对后续每一场同样成立，逐场降级只会把同一份凭据
 *       问题刷 N 遍，还让 run 看起来「跑了但一场都没采到」。</li>
 * </ol>
 *
 * <p><b>本类不做业务判定</b>：它只回答「上游给了我什么」。把「算不算 NO_HISTORY」这类归因
 * 留给 {@code BacktestArchiveService}，是为了不把 {@code backtest} 的词汇（归档状态）
 * 拉进 {@code odds} 包——依赖方向必须是 {@code backtest → odds}。</p>
 */
@Component
@ConditionalOnProperty(name = "app.asian-odds.provider", havingValue = "NOVA_API")
public class NovaApiHistoryClient {

    private static final Logger log = LoggerFactory.getLogger(NovaApiHistoryClient.class);

    static final String ODDS_HISTORY_PATH = "/nova/tf/odds-history";
    static final String PROVIDER_CODE = "NOVA_API";

    private static final String MATCH_ID_PARAMETER = "matchId";

    /**
     * 响应体级 {@code code} 为 500 时的重试次数。
     *
     * <p>这类失败 HTTP 是 200，{@code ProviderHttpExecutor} 的重试策略看不到它，
     * 只能在响应体这一层自己再试一次。实测同一场换参数即 200，说明是上游偶发。
     * 不做更多次：额度是硬的（5000/日），而补采是成批的，单场多试几次会把整批拖长。</p>
     */
    private static final int BODY_LEVEL_ATTEMPTS = 2;

    private final NovaApiClient client;
    private final ObjectMapper objectMapper;

    public NovaApiHistoryClient(NovaApiClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    public String providerCode() {
        return PROVIDER_CODE;
    }

    /**
     * 拉取一场比赛的完整赔率时间序列。
     *
     * @param externalMatchId 竞彩 {@code matchId}
     * @return 上游原样返回的结果（HTTP 状态 + 响应体 code + 解析后的载荷）
     * @throws ProviderException 凭据失败（401/403）、网络层重试耗尽、或响应不是合法 JSON
     */
    public HistoryFetch fetch(String externalMatchId) {
        if (!StringUtils.hasText(externalMatchId)) {
            throw new ProviderException(
                    PROVIDER_CODE,
                    ProviderErrorCategory.INVALID_PARAMETER,
                    "历史赔率按场次拉取，但 externalMatchId 为空"
            );
        }
        String matchId = externalMatchId.trim();
        HistoryFetch last = null;
        for (int attempt = 1; attempt <= BODY_LEVEL_ATTEMPTS; attempt++) {
            HistoryFetch fetch = fetchOnce(matchId);
            if (!fetch.isBodyLevelRetryable()) {
                return fetch;
            }
            last = fetch;
            if (attempt < BODY_LEVEL_ATTEMPTS) {
                log.warn("NovaAPI 历史赔率响应体级失败，准备重试 matchId={} code={} attempt={}",
                        matchId, fetch.upstreamCode(), attempt);
            }
        }
        return last;
    }

    private HistoryFetch fetchOnce(String matchId) {
        ProviderHttpResponse response;
        try {
            response = client.getHistory(PROVIDER_CODE, ODDS_HISTORY_PATH, Map.of(MATCH_ID_PARAMETER, matchId));
        } catch (ProviderHttpException exception) {
            Integer status = exception.httpStatus();
            if (status != null && (status == 401 || status == 403)) {
                // 凭据问题对整批成立，必须立刻中断而不是逐场落一条失败记录。
                throw new ProviderException(
                        PROVIDER_CODE,
                        ProviderErrorCategory.UPSTREAM_FAILURE,
                        "NovaAPI 历史赔率鉴权失败（HTTP %s），请检查 app.nova.api-key 与私钥"
                                .formatted(status),
                        exception
                );
            }
            // 404 也在这一支：ProviderHttpExecutor 对 4xx 抛 ProviderHttpException，
            // 而 404 恰是「上游没有这场历史」的终局结论，不是错误，需要区分出来。
            return HistoryFetch.ofHttpFailure(
                    status,
                    null,
                    exception.getMessage(),
                    exception.retryCount(),
                    exception.quotaCost()
            );
        }

        JsonNode body = parseBody(response.body(), matchId);
        return new HistoryFetch(
                response.status(),
                body == null ? null : intOrNull(body, "code"),
                body,
                null,
                response.retryCount(),
                response.quotaCost()
        );
    }

    private JsonNode parseBody(String rawBody, String matchId) {
        if (!StringUtils.hasText(rawBody)) {
            // 空响应体：可能是 nginx 层截断，也可能是超时后写入的空串。归为可重试失败。
            return null;
        }
        try {
            JsonNode parsed = objectMapper.readTree(rawBody);
            if (parsed == null || !parsed.isObject()) {
                throw new ProviderException(
                        PROVIDER_CODE,
                        ProviderErrorCategory.PARSE_FAILURE,
                        "NovaAPI 历史赔率响应不是对象：matchId=" + matchId
                );
            }
            return parsed;
        } catch (JsonProcessingException exception) {
            throw new ProviderException(
                    PROVIDER_CODE,
                    ProviderErrorCategory.PARSE_FAILURE,
                    "NovaAPI 历史赔率响应不是合法 JSON：matchId=" + matchId,
                    exception
            );
        }
    }

    private static Integer intOrNull(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        if (field == null || field.isNull()) {
            return null;
        }
        // 字段类型会漂移（字符串数字与数字混用），一律按文本再解析。
        if (field.isNumber()) {
            return field.intValue();
        }
        String text = field.asText(null);
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return Integer.valueOf(text.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /**
     * 一次取数的原始结果。
     *
     * @param httpStatus     HTTP 状态；网络层失败时为异常里带的那个状态（可能为 null）
     * @param upstreamCode   响应体 {@code code}；没有响应体时为 null
     * @param payload        解析后的响应体；没有响应体时为 null
     * @param transportError 网络层失败信息；成功时为 null
     */
    public record HistoryFetch(
            Integer httpStatus,
            Integer upstreamCode,
            JsonNode payload,
            String transportError,
            int retryCount,
            int quotaCost
    ) {

        /** 网络层失败（HTTP 非 2xx 或重试耗尽）：没有可用载荷。 */
        public static HistoryFetch ofHttpFailure(
                Integer httpStatus,
                Integer upstreamCode,
                String transportError,
                int retryCount,
                int quotaCost
        ) {
            return new HistoryFetch(httpStatus, upstreamCode, null, transportError, retryCount, quotaCost);
        }

        /** 上游明确说「没有这场的历史记录」。 */
        public boolean noHistory() {
            if (httpStatus != null && httpStatus == 404) {
                return true;
            }
            // 同一件事在 HTTP 200 下也可能以 code=404 表达，实测两者都出现过。
            return upstreamCode != null && upstreamCode == 404;
        }

        public boolean success() {
            boolean httpOk = httpStatus != null && httpStatus >= 200 && httpStatus < 300;
            return httpOk && upstreamCode != null && upstreamCode == 200;
        }

        /**
         * 响应体级可重试：HTTP 200 但 {@code code} 为 5xx。
         *
         * <p>实测报文 {@code {"code":500,"msg":"网络请求异常：Failed to connect to ..."}}，
         * 同一场换成 {@code idEvent} 参数即 200，说明是上游侧偶发，不是参数问题。</p>
         */
        public boolean isBodyLevelRetryable() {
            return upstreamCode != null && upstreamCode >= 500 && upstreamCode < 600;
        }
    }
}
