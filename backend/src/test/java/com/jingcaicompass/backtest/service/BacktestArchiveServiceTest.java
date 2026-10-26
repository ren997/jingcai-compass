package com.jingcaicompass.backtest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.backtest.entity.OddsHistoryArchiveEntity;
import com.jingcaicompass.backtest.enums.BacktestArchiveStatusEnum;
import com.jingcaicompass.backtest.mapper.OddsHistoryArchiveMapper;
import com.jingcaicompass.odds.client.NovaApiHistoryClient;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 归档服务的决策表：<b>什么时候发请求、什么时候复用、失败归到哪一档</b>。
 *
 * <p>这四件事里任何一件错了都不会立刻报错：多发请求只是烧额度，误复用会让负缓存吞掉新增数据，
 * 归错档则让「重采策略」失去意义。因此每条都单独钉住。</p>
 */
class BacktestArchiveServiceTest {

    private static final String PROVIDER = "NOVA_API";
    private static final String EXTERNAL_ID = "2042155";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private OddsHistoryArchiveMapper archiveMapper;
    private RecordingHistoryClient historyClient;
    private BacktestArchiveService service;

    @BeforeEach
    void setUp() {
        archiveMapper = mock(OddsHistoryArchiveMapper.class);
        historyClient = new RecordingHistoryClient();
        @SuppressWarnings("unchecked")
        ObjectProvider<NovaApiHistoryClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(historyClient);
        service = new BacktestArchiveService(archiveMapper, objectMapper, provider);
    }

    @Test
    void reusesExistingFinalArchiveWithoutCallingUpstream() {
        when(archiveMapper.selectFinalByExternalMatchId(PROVIDER, EXTERNAL_ID))
                .thenReturn(archived(77L, BacktestArchiveStatusEnum.SUCCESS));

        BacktestArchiveService.ArchiveOutcome outcome = service.ensureArchived(request());

        assertThat(outcome.reused()).isTrue();
        assertThat(outcome.usable()).isTrue();
        assertThat(outcome.archiveId()).isEqualTo(77L);
        assertThat(historyClient.calls).as("已终局的归档不得再发请求").isZero();
    }

    @Test
    void reusesNegativeCacheAsWell() {
        // NO_HISTORY 是终局：约 4 个月以外的场次永远不会有历史，重采只是白烧额度。
        when(archiveMapper.selectFinalByExternalMatchId(PROVIDER, EXTERNAL_ID))
                .thenReturn(archived(78L, BacktestArchiveStatusEnum.NO_HISTORY));

        BacktestArchiveService.ArchiveOutcome outcome = service.ensureArchived(request());

        assertThat(outcome.reused()).isTrue();
        assertThat(outcome.usable()).isFalse();
        assertThat(outcome.status()).isEqualTo(BacktestArchiveStatusEnum.NO_HISTORY);
        assertThat(historyClient.calls).isZero();
    }

    @Test
    void httpNotFoundIsRecordedAsTerminalNoHistory() {
        historyClient.enqueue(NovaApiHistoryClient.HistoryFetch.ofHttpFailure(404, null, "404", 0, 1));

        BacktestArchiveService.ArchiveOutcome outcome = service.ensureArchived(request());

        assertThat(outcome.status()).isEqualTo(BacktestArchiveStatusEnum.NO_HISTORY);
        assertThat(outcome.status().isFinal()).isTrue();
        assertThat(outcome.usable()).isFalse();
        ArgumentCaptor<OddsHistoryArchiveEntity> captor = ArgumentCaptor.forClass(OddsHistoryArchiveEntity.class);
        verify(archiveMapper).insertFailure(captor.capture());
        assertThat(captor.getValue().getFetchStatus()).isEqualTo(BacktestArchiveStatusEnum.NO_HISTORY);
        assertThat(captor.getValue().getHttpStatus()).isEqualTo(404);
        assertThat(captor.getValue().getPayload()).as("失败行不得带载荷").isNull();
    }

    @Test
    void successfulResponseWithoutAnyPointIsNoQuoteYetNotFailure() {
        historyClient.enqueue(successFetch("{\"code\":200,\"data\":{\"providers\":[]}}"));

        BacktestArchiveService.ArchiveOutcome outcome = service.ensureArchived(request());

        assertThat(outcome.status()).isEqualTo(BacktestArchiveStatusEnum.NO_QUOTE_YET);
        assertThat(outcome.status().isFinal()).as("空序列是终局结论，可以负缓存").isTrue();
        verify(archiveMapper, never()).insertSuccessIgnore(any(), anyString());
    }

    @Test
    void successfulSeriesIsTrimmedHashedAndArchived() {
        when(archiveMapper.insertSuccessIgnore(any(), anyString())).thenReturn(1);
        // 同一个方法先被「复用判定」调用（必须为空才会去采），再被「回读新行 id」调用。
        when(archiveMapper.selectFinalByExternalMatchId(PROVIDER, EXTERNAL_ID))
                .thenReturn(null, archived(91L, BacktestArchiveStatusEnum.SUCCESS));
        historyClient.enqueue(successFetch("""
                {"code":200,"msg":"ok","data":{"providers":[
                  {"name":"平博","markets":[
                    {"marketId":1,"options":[{"option":"Home","points":[{"odd":2.1,"updateTime":"2026-06-20 10:00:00"}]}]},
                    {"marketId":4,"options":[
                      {"option":"Home","line":-0.25,"points":[
                        {"odd":1.9,"updateTime":"2026-06-20 12:00:00"},
                        {"odd":1.88,"updateTime":"2026-06-21 12:00:00"}]},
                      {"option":"Away","line":-0.25,"points":[{"odd":2.0,"updateTime":"2026-06-20 12:00:00"}]}
                    ]},
                    {"marketId":5,"options":[
                      {"option":"Over","line":3.0,"points":[{"odd":1.99,"updateTime":"2026-06-20 14:00:00"}]},
                      {"option":"Under","line":3.0,"points":[{"odd":1.9,"updateTime":"2026-06-20 14:00:00"}]}
                    ]}
                  ]}
                ]}}
                """));

        BacktestArchiveService.ArchiveOutcome outcome = service.ensureArchived(request());

        assertThat(outcome.status()).isEqualTo(BacktestArchiveStatusEnum.SUCCESS);
        assertThat(outcome.usable()).isTrue();
        assertThat(outcome.reused()).isFalse();
        assertThat(outcome.archiveId()).as("回读到的行 id，供 case 建外键").isEqualTo(91L);

        ArgumentCaptor<OddsHistoryArchiveEntity> entityCaptor =
                ArgumentCaptor.forClass(OddsHistoryArchiveEntity.class);
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(archiveMapper).insertSuccessIgnore(entityCaptor.capture(), payloadCaptor.capture());

        OddsHistoryArchiveEntity entity = entityCaptor.getValue();
        assertThat(entity.getBookmakerCount()).isEqualTo(1);
        assertThat(entity.getPointCount()).as("只数让球与大小球的点").isEqualTo(5);
        assertThat(entity.getSeriesStartAt()).isEqualTo(Instant.parse("2026-06-20T04:00:00Z"));
        assertThat(entity.getSeriesEndAt()).isEqualTo(Instant.parse("2026-06-21T04:00:00Z"));
        assertThat(entity.getTrimmed()).isTrue();
        assertThat(entity.getPayloadHash())
                .as("SHA-256 十六进制，约束 ck_odds_history_archive_payload_hash 要求 64 位")
                .hasSize(64)
                .matches("^[0-9a-f]{64}$");
        assertThat(entity.getAttemptedAt()).isNotNull();

        JsonNode stored = readTree(payloadCaptor.getValue());
        assertThat(stored.path("data").path("providers").size()).isEqualTo(1);
        assertThat(stored.path("data").path("providers").get(0).path("markets").size())
                .as("落库载荷里只剩让球与大小球").isEqualTo(2);
    }

    @Test
    void conflictOnPartialUniqueIndexFallsBackToReuse() {
        // 并发下另一个 worker 先落了同一场：这不是失败，只是「别人赢了」。
        when(archiveMapper.insertSuccessIgnore(any(), anyString())).thenReturn(0);
        when(archiveMapper.selectFinalByExternalMatchId(PROVIDER, EXTERNAL_ID))
                .thenReturn(null, archived(95L, BacktestArchiveStatusEnum.SUCCESS));
        historyClient.enqueue(successFetch("""
                {"code":200,"data":{"providers":[{"name":"平博","markets":[
                  {"marketId":4,"options":[{"option":"Home","line":0,"points":[{"odd":1.9,"updateTime":"2026-06-20 12:00:00"}]}]}
                ]}]}}
                """));

        BacktestArchiveService.ArchiveOutcome outcome = service.ensureArchived(request());

        assertThat(outcome.reused()).isTrue();
        assertThat(outcome.archiveId()).isEqualTo(95L);
        assertThat(outcome.usable()).isTrue();
    }

    @Test
    void bodyLevelUpstreamErrorIsRetryable() {
        historyClient.enqueue(new NovaApiHistoryClient.HistoryFetch(
                200, 500, readTree("{\"code\":500,\"msg\":\"net\"}"), null, 0, 1));

        BacktestArchiveService.ArchiveOutcome outcome = service.ensureArchived(request());

        assertThat(outcome.status()).isEqualTo(BacktestArchiveStatusEnum.RETRYABLE_FAILURE);
        assertThat(outcome.status().isRetryable()).isTrue();
        assertThat(outcome.status().isFinal()).as("可重试失败不能进负缓存").isFalse();
    }

    @Test
    void transportFailureWithoutStatusIsRetryable() {
        historyClient.enqueue(NovaApiHistoryClient.HistoryFetch.ofHttpFailure(null, null, "timeout", 1, 2));

        BacktestArchiveService.ArchiveOutcome outcome = service.ensureArchived(request());

        assertThat(outcome.status()).isEqualTo(BacktestArchiveStatusEnum.RETRYABLE_FAILURE);
        assertThat(outcome.message()).contains("timeout");
    }

    @Test
    void clientRejectedParameterIsPermanentFailure() {
        historyClient.enqueue(NovaApiHistoryClient.HistoryFetch.ofHttpFailure(400, -1, "bad", 0, 1));

        BacktestArchiveService.ArchiveOutcome outcome = service.ensureArchived(request());

        assertThat(outcome.status()).isEqualTo(BacktestArchiveStatusEnum.PERMANENT_FAILURE);
        assertThat(outcome.status().isRetryable()).as("参数问题重采只会重复同一个错误").isFalse();
    }

    @Test
    void absentHistoryClientFailsLoudlyInsteadOfSilentlySkipping() {
        @SuppressWarnings("unchecked")
        ObjectProvider<NovaApiHistoryClient> empty = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);
        BacktestArchiveService withoutClient = new BacktestArchiveService(archiveMapper, objectMapper, empty);

        assertThatThrownBy(() -> withoutClient.ensureArchived(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("历史赔率客户端");
    }

    private static BacktestArchiveService.ArchiveRequest request() {
        return new BacktestArchiveService.ArchiveRequest(
                PROVIDER,
                EXTERNAL_ID,
                42L,
                LocalDate.of(2026, 6, 20),
                "英超",
                "主队",
                "客队",
                Instant.parse("2026-06-21T12:00:00Z")
        );
    }

    private static OddsHistoryArchiveEntity archived(Long id, BacktestArchiveStatusEnum status) {
        OddsHistoryArchiveEntity entity = new OddsHistoryArchiveEntity();
        entity.setId(id);
        entity.setFetchStatus(status);
        return entity;
    }

    private NovaApiHistoryClient.HistoryFetch successFetch(String body) {
        return new NovaApiHistoryClient.HistoryFetch(200, 200, readTree(body), null, 0, 1);
    }

    private JsonNode readTree(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** 只替换 {@code fetch}，其余逻辑仍是真实实现；这样测试不依赖 HTTP 层与签名。 */
    private static final class RecordingHistoryClient extends NovaApiHistoryClient {

        private final java.util.ArrayDeque<NovaApiHistoryClient.HistoryFetch> queue =
                new java.util.ArrayDeque<>();
        private int calls;

        private RecordingHistoryClient() {
            super(null, null);
        }

        private void enqueue(NovaApiHistoryClient.HistoryFetch fetch) {
            queue.add(fetch);
        }

        @Override
        public NovaApiHistoryClient.HistoryFetch fetch(String externalMatchId) {
            calls++;
            NovaApiHistoryClient.HistoryFetch next = queue.poll();
            if (next == null) {
                throw new IllegalStateException("测试未准备取数结果");
            }
            return next;
        }
    }
}
