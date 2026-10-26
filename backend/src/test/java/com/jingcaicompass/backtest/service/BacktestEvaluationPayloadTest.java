package com.jingcaicompass.backtest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.backtest.BacktestProperties;
import com.jingcaicompass.backtest.entity.OddsHistoryArchiveEntity;
import com.jingcaicompass.backtest.mapper.OddsHistoryArchiveMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BacktestEvaluationPayloadTest {

    private static final String PAYLOAD = """
            {"code":200,"data":{"idEvent":"2594654","providers":[
              {"name":"crown","markets":[{"marketId":4,"name":"handicap","options":[
                {"line":"-0.5","option":"Home","points":[{"odd":1.95,"updateTime":"2026-10-20 22:00:00"}]},
                {"line":"-0.5","option":"Away","points":[{"odd":1.95,"updateTime":"2026-10-20 22:00:00"}]}
              ]}]}]}}
            """;

    private final OddsHistoryArchiveMapper archiveMapper = mock(OddsHistoryArchiveMapper.class);
    private final BacktestEvaluationService service = new BacktestEvaluationService(
            mock(BacktestArchiveService.class),
            archiveMapper,
            new ObjectMapper(),
            BacktestProperties.defaults()
    );

    private static OddsHistoryArchiveEntity archive(Long id) {
        OddsHistoryArchiveEntity entity = new OddsHistoryArchiveEntity();
        entity.setId(id);
        entity.setExternalMatchId("2042096");
        return entity;
    }

    @Test
    @DisplayName("读 jsonb 原文，而不是实体的 payload 字段")
    void readsRawJsonbTextInsteadOfEntityField() {
        when(archiveMapper.selectPayloadTextById(7L)).thenReturn(PAYLOAD);

        JsonNode payload = service.payloadOf(archive(7L));

        assertThat(payload).isNotNull();
        assertThat(payload.path("data").path("providers")).hasSize(1);
        assertThat(payload.path("data").path("providers").get(0).path("markets").get(0)
                .path("marketId").asInt()).isEqualTo(4);
        // 关键断言：必须走 text 查询。走实体字段时 @Select 的原生映射会丢掉 typeHandler，
        // jsonb 读回来是空 Map，切片恒为 0 行而归档状态看起来完全正常。
        verify(archiveMapper).selectPayloadTextById(7L);
    }

    @Test
    @DisplayName("载荷为空时返回 null，由调用方按「无可用报价」处理")
    void returnsNullForMissingPayload() {
        when(archiveMapper.selectPayloadTextById(8L)).thenReturn(null);
        assertThat(service.payloadOf(archive(8L))).isNull();

        when(archiveMapper.selectPayloadTextById(9L)).thenReturn("   ");
        assertThat(service.payloadOf(archive(9L))).isNull();
    }

    @Test
    @DisplayName("载荷不可解析时返回 null，不把异常抛给整个 run")
    void returnsNullForBrokenPayload() {
        when(archiveMapper.selectPayloadTextById(10L)).thenReturn("{not json");

        // 一场脏数据不该让 2000 场的 run 整体失败；它只该让这一场变成「没有报价」。
        assertThat(service.payloadOf(archive(10L))).isNull();
    }
}
