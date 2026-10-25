package com.jingcaicompass.odds.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jingcaicompass.data.dto.ProviderFetchResult;
import com.jingcaicompass.odds.dto.AsianOddsLeagueDto;
import com.jingcaicompass.odds.dto.AsianOddsMatchOddsDto;
import com.jingcaicompass.odds.dto.AsianOddsQueryDto;
import com.jingcaicompass.odds.service.AsianOddsPayloadMapper;
import com.jingcaicompass.odds.service.AsianOddsProvider;
import com.jingcaicompass.odds.service.NovaApiOddsPayloadMapper;
import com.jingcaicompass.match.client.NovaApiClient;
import com.jingcaicompass.system.provider.ProviderErrorCategory;
import com.jingcaicompass.system.provider.ProviderException;
import com.jingcaicompass.system.provider.ProviderHttpException;
import com.jingcaicompass.system.provider.ProviderHttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * NovaAPI 亚盘赔率：按竞彩 {@code matchId} 逐场拉取全部机构与玩法。
 *
 * <p>与 The Odds API 的「按联赛 sport key 批量拉」是两种完全不同的取数模型：这里
 * <b>一场一次调用</b>，{@code /nova/tf/odds?matchId=} 一次就返回该场所有机构 ×
 * 所有玩法（{@code marketId=4} 让球、{@code 5} 大小球、{@code 1} 胜平负），
 * 因此额度 = 场次数，不再需要联赛 sport key 映射表。</p>
 *
 * <p>逐场响应被聚合成一个信封再交给
 * {@code ProviderSyncTemplate} 幂等入库：
 * {@code {provider:"NOVA_API", responses:[{matchId, status, retryCount, quotaCost, body}]}}。
 * 单场失败只落在该条 {@code body} 上，不中断其余场次；但<b>全部场次都非 200 时直接抛错</b>——
 * NovaAPI 把「密钥过期」这类致命问题也放在响应体的 {@code code} 里（HTTP 仍是 200），
 * 若逐场降级处理，同步记录会显示「成功」而快照数为 0，正是最难排查的那种静默失败。</p>
 */
@Component
@ConditionalOnProperty(name = "app.asian-odds.provider", havingValue = "NOVA_API")
public class NovaApiOddsProvider implements AsianOddsProvider {

    static final String PROVIDER_CODE = NovaApiOddsPayloadMapper.PROVIDER_CODE;
    static final String ODDS_PATH = "/nova/tf/odds";
    private static final String MATCH_ID_PARAMETER = "matchId";
    /** 列宽 1024（V28）下的保守上限，超出即退化为定长摘要键。 */
    private static final int REQUEST_KEY_MAX_LENGTH = 900;

    private final NovaApiClient client;
    private final AsianOddsPayloadMapper payloadMapper;
    private final ObjectMapper objectMapper;

    public NovaApiOddsProvider(
            NovaApiClient client,
            AsianOddsPayloadMapper payloadMapper,
            ObjectMapper objectMapper
    ) {
        this.client = client;
        this.payloadMapper = payloadMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public String providerCode() {
        return PROVIDER_CODE;
    }

    /**
     * NovaAPI 没有独立的联赛字典接口（联赛随赛程一起返回），单源后也不再需要
     * 「体彩联赛 → 供应商 sport key」的映射，因此这里恒为空。
     */
    @Override
    public List<AsianOddsLeagueDto> fetchLeagues() {
        return List.of();
    }

    @Override
    public List<AsianOddsMatchOddsDto> fetchPreMatchOdds(AsianOddsQueryDto query) {
        return payloadMapper.parseMatches(fetchPreMatchOddsRaw(query).payloadJson());
    }

    @Override
    public ProviderFetchResult fetchPreMatchOddsRaw(AsianOddsQueryDto query) {
        List<String> matchIds = matchIds(query);
        if (matchIds.isEmpty()) {
            throw new ProviderException(
                    providerCode(),
                    ProviderErrorCategory.INVALID_PARAMETER,
                    "NovaAPI 赔率按场次拉取，但本次没有任何带 external_match_id 的比赛"
            );
        }

        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("provider", providerCode());
        putIfNotNull(envelope, "kickoffFrom", query.kickoffFrom());
        putIfNotNull(envelope, "kickoffTo", query.kickoffTo());
        var responses = envelope.putArray("responses");

        int retryCount = 0;
        int quotaCost = 0;
        int successCount = 0;
        String firstFailureCode = null;

        for (String matchId : matchIds) {
            ProviderHttpResponse response;
            try {
                response = client.get(providerCode(), ODDS_PATH, Map.of(MATCH_ID_PARAMETER, matchId));
            } catch (ProviderHttpException exception) {
                retryCount += exception.retryCount();
                quotaCost += exception.quotaCost();
                // 认证类失败对后续场次同样成立，继续逐场重试只会把同一份凭据问题刷 N 遍。
                if (isAuthenticationFailure(exception)) {
                    throw new ProviderException(
                            providerCode(),
                            ProviderErrorCategory.UPSTREAM_FAILURE,
                            "NovaAPI 赔率鉴权失败（HTTP %s），请检查 app.nova.api-key 与私钥"
                                    .formatted(exception.httpStatus()),
                            exception
                    );
                }
                responses.addObject()
                        .put("matchId", matchId)
                        .put("status", exception.httpStatus())
                        .put("retryCount", exception.retryCount())
                        .put("quotaCost", exception.quotaCost())
                        .putObject("body")
                        .put("code", -1);
                continue;
            }

            retryCount += response.retryCount();
            quotaCost += response.quotaCost();

            ObjectNode entry = responses.addObject();
            entry.put("matchId", matchId);
            entry.put("status", response.status());
            entry.put("retryCount", response.retryCount());
            entry.put("quotaCost", response.quotaCost());
            JsonNode body = readBody(response.body(), matchId);
            entry.set("body", body);
            int code = body.path("code").asInt(-1);
            if (code == 200) {
                successCount++;
            } else if (firstFailureCode == null) {
                firstFailureCode = matchId + ":code=" + code + ":" + body.path("message").asText("");
            }
        }

        if (successCount == 0) {
            throw new ProviderException(
                    providerCode(),
                    ProviderErrorCategory.PARSE_FAILURE,
                    "NovaAPI 赔率全部场次返回非 200（首个 %s）".formatted(firstFailureCode)
            );
        }

        try {
            return new ProviderFetchResult(
                    requestKey(query, matchIds),
                    objectMapper.writeValueAsString(envelope),
                    200,
                    Instant.now(),
                    retryCount,
                    quotaCost
            );
        } catch (JsonProcessingException exception) {
            throw new ProviderException(
                    providerCode(),
                    ProviderErrorCategory.PARSE_FAILURE,
                    "NovaAPI 赔率载荷序列化失败",
                    exception
            );
        }
    }

    /** 一场一次调用，因此额度就是场次数（不是联赛数）。 */
    @Override
    public int estimateQuotaCost(AsianOddsQueryDto query) {
        return matchIds(query).size();
    }

    private static List<String> matchIds(AsianOddsQueryDto query) {
        if (query == null || query.matchExternalIds() == null) {
            return List.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String matchId : query.matchExternalIds()) {
            if (StringUtils.hasText(matchId)) {
                ids.add(matchId.trim());
            }
        }
        return List.copyOf(ids);
    }

    private JsonNode readBody(String body, String matchId) {
        if (!StringUtils.hasText(body)) {
            throw new ProviderException(
                    providerCode(),
                    ProviderErrorCategory.PARSE_FAILURE,
                    "NovaAPI 赔率响应为空：matchId=" + matchId
            );
        }
        try {
            JsonNode parsed = objectMapper.readTree(body);
            if (parsed == null || !parsed.isObject()) {
                throw new ProviderException(
                        providerCode(),
                        ProviderErrorCategory.PARSE_FAILURE,
                        "NovaAPI 赔率响应不是对象：matchId=" + matchId
                );
            }
            return parsed;
        } catch (JsonProcessingException exception) {
            throw new ProviderException(
                    providerCode(),
                    ProviderErrorCategory.PARSE_FAILURE,
                    "NovaAPI 赔率响应不是合法 JSON：matchId=" + matchId,
                    exception
            );
        }
    }

    private static boolean isAuthenticationFailure(ProviderHttpException exception) {
        Integer status = exception.httpStatus();
        return status != null && (status == 401 || status == 403);
    }

    /**
     * 请求键：可读格式把当日全部 matchId 拼进去，长度随场次数线性增长。
     *
     * <p>2026-10-25 踩过一次：29 场拼出 283 字符，写库时撞上
     * {@code raw_data_payloads.request_key VARCHAR(256)}，整轮同步失败且记录停在 RUNNING。
     * 列已由 V28 加宽到 1024，但「场次数只增不减」这个前提本身不成立（周末 60~80 场很常见），
     * 所以这里再加一道守卫：超过阈值就退化成「场次数 + 集合摘要」的定长键。</p>
     *
     * <p>退化键仍然等价：同一批 matchId 得到同一个摘要，不同批次不会误判为重复；
     * 丢掉的是「肉眼能看出具体是哪几场」，排查时改用 payload 内容即可。</p>
     */
    private static String requestKey(AsianOddsQueryDto query, List<String> matchIds) {
        String from = query.kickoffFrom() == null ? "*" : query.kickoffFrom().toInstant().toString();
        String to = query.kickoffTo() == null ? "*" : query.kickoffTo().toInstant().toString();
        String joined = String.join(",", matchIds);
        String readable = "nova-odds:" + joined + ":" + from + ":" + to;
        if (readable.length() <= REQUEST_KEY_MAX_LENGTH) {
            return readable;
        }
        return "nova-odds:" + matchIds.size() + ":" + shortDigest(joined) + ":" + from + ":" + to;
    }

    private static String shortDigest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (int index = 0; index < 16; index++) {
                hex.append(Character.forDigit((hash[index] >> 4) & 0xF, 16));
                hex.append(Character.forDigit(hash[index] & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            // SHA-256 是 JDK 强制要求提供的算法，走到这里说明运行环境本身异常。
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static void putIfNotNull(ObjectNode target, String name, java.time.OffsetDateTime value) {
        if (value != null) {
            target.put(name, value.toInstant().toString());
        }
    }
}
