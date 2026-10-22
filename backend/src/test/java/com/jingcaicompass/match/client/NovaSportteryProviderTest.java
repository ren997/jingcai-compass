package com.jingcaicompass.match.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.data.dto.ProviderFetchResult;
import com.jingcaicompass.match.dto.SportteryMatchResultDto;
import com.jingcaicompass.match.exception.SportteryDataAccessException;
import com.jingcaicompass.match.service.NovaMatchResultPayloadMapper;
import com.jingcaicompass.match.service.NovaSchedulePayloadMapper;
import com.jingcaicompass.system.provider.ProviderErrorCategory;
import com.jingcaicompass.system.provider.ProviderHttpException;
import com.jingcaicompass.system.provider.ProviderHttpResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/**
 * NovaAPI 赛果取数契约：区间参数、跨度上限、额度计数与失败语义。
 *
 * <p>这里唯一不能用「能跑通」替代的是<b>跨度上限</b>：定时补数窗口一旦被调到 30 天以上，
 * 服务端会直接拒绝，而日志里只会看到一次 400，分不清是配置问题还是接口问题。</p>
 */
class NovaSportteryProviderTest {

    private static final String FIXTURE = "sporttery/novaapi-match-results-2026-10-20-to-21.json";
    private static final LocalDate START = LocalDate.of(2026, 10, 20);
    private static final LocalDate END = LocalDate.of(2026, 10, 21);

    private NovaApiClient client;
    private NovaSportteryProvider provider;

    @BeforeEach
    void setUp() {
        client = mock(NovaApiClient.class);
        ObjectMapper objectMapper = new ObjectMapper();
        provider = new NovaSportteryProvider(
                client,
                new NovaSchedulePayloadMapper(objectMapper),
                new NovaMatchResultPayloadMapper(objectMapper)
        );
    }

    @Test
    void queriesResultsEndpointWithBothBounds() {
        when(client.get(
                eq(NovaSportteryProvider.PROVIDER_CODE),
                eq(NovaSportteryProvider.MATCH_RESULTS_PATH),
                anyMap()
        )).thenReturn(ok(rawFixture(), 1));

        ProviderFetchResult result = provider.fetchMatchResultsRaw(START, END);

        verify(client).get(
                NovaSportteryProvider.PROVIDER_CODE,
                NovaSportteryProvider.MATCH_RESULTS_PATH,
                Map.of("startDate", "2026-10-20", "endDate", "2026-10-21")
        );
        // 一次请求 = 一个额度单位，与赛程、赔率共用同一份额度池。
        assertThat(result.quotaCost()).isEqualTo(1);
        assertThat(result.httpStatus()).isEqualTo(200);
        // 载荷去重键要能区分不同区间，否则跨区间会互相覆盖。
        assertThat(result.requestKey()).isEqualTo("2026-10-20:2026-10-21");
    }

    /** 30 天是官方上限；超限在本地就拒绝，不让服务端返回 400 之后再猜。 */
    @Test
    void rejectsQuerySpanBeyondProviderLimit() {
        LocalDate tooFar = START.plusDays(NovaSportteryProvider.MAX_RESULT_QUERY_SPAN_DAYS);

        assertThatThrownBy(() -> provider.fetchMatchResultsRaw(START, tooFar))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不得超过 30 天");
        verifyNoInteractions(client);
    }

    @Test
    void acceptsExactlyTheProviderLimit() {
        when(client.get(
                eq(NovaSportteryProvider.PROVIDER_CODE),
                eq(NovaSportteryProvider.MATCH_RESULTS_PATH),
                anyMap()
        )).thenReturn(ok(rawFixture(), 1));

        LocalDate lastDay = START.plusDays(NovaSportteryProvider.MAX_RESULT_QUERY_SPAN_DAYS - 1L);

        assertThat(provider.fetchMatchResultsRaw(START, lastDay).payloadJson()).isNotBlank();
    }

    @Test
    void parsesArchivedPayloadThroughPayloadMapper() {
        List<SportteryMatchResultDto> items = provider.parseMatchResults(rawFixture(), START, END);

        assertThat(items).hasSize(21);
        assertThat(items).extracting(SportteryMatchResultDto::lotteryMatchNo)
                .contains("周三003", "周一001");
    }

    @Test
    void refusesEmptyResponse() {
        when(client.get(
                eq(NovaSportteryProvider.PROVIDER_CODE),
                eq(NovaSportteryProvider.MATCH_RESULTS_PATH),
                anyMap()
        )).thenReturn(ok("   ", 1));

        assertThatThrownBy(() -> provider.fetchMatchResultsRaw(START, END))
                .isInstanceOf(SportteryDataAccessException.class)
                .hasMessageContaining("空响应");
    }

    @Test
    void surfacesHttpFailureAsDataAccessException() {
        when(client.get(
                eq(NovaSportteryProvider.PROVIDER_CODE),
                eq(NovaSportteryProvider.MATCH_RESULTS_PATH),
                anyMap()
        )).thenThrow(new ProviderHttpException(
                NovaSportteryProvider.PROVIDER_CODE,
                ProviderErrorCategory.UPSTREAM_FAILURE,
                "upstream 502",
                1,
                2,
                502
        ));

        assertThatThrownBy(() -> provider.fetchMatchResultsRaw(START, END))
                .isInstanceOf(SportteryDataAccessException.class)
                .hasMessageContaining("upstream 502");
    }

    private static ProviderHttpResponse ok(String body, int quotaCost) {
        return new ProviderHttpResponse(200, body, 0, quotaCost, new HttpHeaders());
    }

    private static String rawFixture() {
        try (InputStream stream = NovaSportteryProviderTest.class
                .getClassLoader()
                .getResourceAsStream(FIXTURE)) {
            if (stream == null) {
                throw new IllegalStateException("fixture not found on classpath: " + FIXTURE);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
