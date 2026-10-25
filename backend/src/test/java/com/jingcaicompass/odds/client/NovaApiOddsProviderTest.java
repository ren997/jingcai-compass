package com.jingcaicompass.odds.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.data.dto.ProviderFetchResult;
import com.jingcaicompass.match.client.NovaApiClient;
import com.jingcaicompass.odds.dto.AsianOddsQueryDto;
import com.jingcaicompass.odds.service.AsianOddsPayloadMapper;
import com.jingcaicompass.system.provider.ProviderErrorCategory;
import com.jingcaicompass.system.provider.ProviderException;
import com.jingcaicompass.system.provider.ProviderHttpException;
import com.jingcaicompass.system.provider.ProviderHttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/**
 * NovaAPI 赔率 Provider 的取数契约：一场一次调用、聚合信封、以及「全失败必须抛错」。
 *
 * <p>最后一条不是洁癖。NovaAPI 把「密钥过期」放在响应体的 {@code code} 里而 HTTP 仍是 200，
 * 逐场降级处理的结果是同步记录显示成功、快照数为 0——这正是单源改造前反复踩的那类静默失败。</p>
 */
class NovaApiOddsProviderTest {

    private static final String MATCH_A = "2042106";
    private static final String MATCH_B = "2041974";

    private NovaApiClient client;
    private NovaApiOddsProvider provider;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        client = mock(NovaApiClient.class);
        objectMapper = new ObjectMapper();
        provider = new NovaApiOddsProvider(client, mock(AsianOddsPayloadMapper.class), objectMapper);
    }

    @Test
    void callsOncePerMatchAndAggregatesResponses() throws Exception {
        when(client.get(eq(NovaApiOddsProvider.PROVIDER_CODE), eq(NovaApiOddsProvider.ODDS_PATH), anyMap()))
                .thenAnswer(invocation -> ok("{\"code\":200,\"data\":{\"matchId\":1,\"providers\":[]}}", 1));

        ProviderFetchResult result = provider.fetchPreMatchOddsRaw(query(MATCH_A, MATCH_B));

        verify(client).get(NovaApiOddsProvider.PROVIDER_CODE, NovaApiOddsProvider.ODDS_PATH, Map.of("matchId", MATCH_A));
        verify(client).get(NovaApiOddsProvider.PROVIDER_CODE, NovaApiOddsProvider.ODDS_PATH, Map.of("matchId", MATCH_B));
        // 额度就是请求数：一场一次，不再按联赛折算。
        assertThat(result.quotaCost()).isEqualTo(2);

        JsonNode envelope = objectMapper.readTree(result.payloadJson());
        assertThat(envelope.path("provider").asText()).isEqualTo(NovaApiOddsProvider.PROVIDER_CODE);
        assertThat(envelope.path("responses")).hasSize(2);
        assertThat(envelope.path("responses").get(0).path("matchId").asText()).isEqualTo(MATCH_A);
        assertThat(envelope.path("responses").get(0).path("body").path("code").asInt()).isEqualTo(200);
    }

    @Test
    void estimatesQuotaFromMatchCount() {
        // 同一场比赛重复出现只请求一次，额度不能按重复项计。
        assertThat(provider.estimateQuotaCost(query(MATCH_A, MATCH_B, MATCH_A))).isEqualTo(2);
        assertThat(provider.estimateQuotaCost(new AsianOddsQueryDto(null, null, null, null))).isZero();
    }

    /**
     * 请求键必须短于落库列宽（V28 后为 1024），且场次越多越要收敛。
     *
     * <p>2026-10-25 的真实事故：29 场拼出 283 字符，撞上当时的 VARCHAR(256)，
     * 整轮亚盘同步失败、记录停在 RUNNING。场次数只增不减，所以不能只靠加宽列。</p>
     */
    @Test
    void keepsRequestKeyWithinColumnWidthAsMatchCountGrows() throws Exception {
        when(client.get(eq(NovaApiOddsProvider.PROVIDER_CODE), eq(NovaApiOddsProvider.ODDS_PATH), anyMap()))
                .thenAnswer(invocation -> ok("{\"code\":200,\"data\":{\"providers\":[]}}", 1));

        // 29 场：真实事故的规模。生产上窗口是 20 字符时间戳，拼出来 283 字符；
        // 这里窗口为空（*）所以更短，断言只要求「保持可读且不超列宽」。
        String[] today = matchIds(29);
        ProviderFetchResult readable = provider.fetchPreMatchOddsRaw(query(today));
        assertThat(readable.requestKey().length()).isLessThanOrEqualTo(1024);
        assertThat(readable.requestKey()).startsWith("nova-odds:2042213").contains(",2042229");

        // 120 场：可读键会到 ~1000 字符，必须退化成定长摘要。
        ProviderFetchResult degraded = provider.fetchPreMatchOddsRaw(query(matchIds(120)));
        assertThat(degraded.requestKey().length()).isLessThanOrEqualTo(1024);
        assertThat(degraded.requestKey()).startsWith("nova-odds:120:").doesNotContain(",2042229");
    }

    private static String[] matchIds(int count) {
        String[] ids = new String[count];
        for (int index = 0; index < count; index++) {
            // 2042213 起连续编号，与真实赛程同一量级（7 位）。
            ids[index] = String.valueOf(2042213 + index);
        }
        return ids;
    }

    @Test
    void refusesToCallProviderWithoutMatchIds() {
        assertThatThrownBy(() -> provider.fetchPreMatchOddsRaw(new AsianOddsQueryDto(null, null, null, null)))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("external_match_id");
        verifyNoInteractions(client);
    }

    @Test
    void failsTheWholeFetchWhenEveryMatchReturnsNonOkCode() {
        when(client.get(eq(NovaApiOddsProvider.PROVIDER_CODE), eq(NovaApiOddsProvider.ODDS_PATH), anyMap()))
                .thenReturn(ok("{\"code\":401,\"message\":\"API密钥无效或已过期\"}", 1));

        assertThatThrownBy(() -> provider.fetchPreMatchOddsRaw(query(MATCH_A, MATCH_B)))
                .isInstanceOf(ProviderException.class)
                .satisfies(exception -> assertThat(((ProviderException) exception).category())
                        .isEqualTo(ProviderErrorCategory.PARSE_FAILURE))
                .hasMessageContaining("code=401");
    }

    @Test
    void keepsPartialFailuresInsideTheEnvelope() throws Exception {
        when(client.get(eq(NovaApiOddsProvider.PROVIDER_CODE), eq(NovaApiOddsProvider.ODDS_PATH), anyMap()))
                .thenAnswer(invocation -> {
                    Map<String, String> params = invocation.getArgument(2);
                    return MATCH_A.equals(params.get("matchId"))
                            ? ok("{\"code\":200,\"data\":{\"providers\":[]}}", 1)
                            : ok("{\"code\":404,\"message\":\"未找到赔率\"}", 1);
                });

        ProviderFetchResult result = provider.fetchPreMatchOddsRaw(query(MATCH_A, MATCH_B));

        JsonNode responses = objectMapper.readTree(result.payloadJson()).path("responses");
        assertThat(responses).hasSize(2);
        // 单场 404 只标记该场，不拖垮整批；解析器会把 404 记成该场的 parseError。
        assertThat(responses.get(1).path("body").path("code").asInt()).isEqualTo(404);
    }

    @Test
    void abortsImmediatelyOnHttpAuthenticationFailure() {
        when(client.get(eq(NovaApiOddsProvider.PROVIDER_CODE), eq(NovaApiOddsProvider.ODDS_PATH), anyMap()))
                .thenThrow(new ProviderHttpException(
                        NovaApiOddsProvider.PROVIDER_CODE,
                        ProviderErrorCategory.UPSTREAM_FAILURE,
                        "unauthorized",
                        0,
                        0,
                        401
                ));

        assertThatThrownBy(() -> provider.fetchPreMatchOddsRaw(query(MATCH_A, MATCH_B)))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("鉴权失败");
        // 认证失败对后续场次同样成立，不该把同一份凭据问题刷 N 遍。
        verify(client).get(NovaApiOddsProvider.PROVIDER_CODE, NovaApiOddsProvider.ODDS_PATH, Map.of("matchId", MATCH_A));
    }

    private static AsianOddsQueryDto query(String... matchIds) {
        return new AsianOddsQueryDto(null, null, null, null, List.of(), List.of(matchIds));
    }

    private static ProviderHttpResponse ok(String body, int quotaCost) {
        return new ProviderHttpResponse(200, body, 0, quotaCost, new HttpHeaders());
    }
}
