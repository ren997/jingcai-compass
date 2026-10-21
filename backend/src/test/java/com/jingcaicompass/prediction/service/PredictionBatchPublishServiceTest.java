package com.jingcaicompass.prediction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jingcaicompass.prediction.dto.PredictionBatchPublishDto;
import com.jingcaicompass.prediction.dto.PredictionPublishDto;
import com.jingcaicompass.prediction.entity.Prediction;
import com.jingcaicompass.prediction.enums.PredictionStatusEnum;
import com.jingcaicompass.prediction.mapper.PredictionMapper;
import com.jingcaicompass.prediction.vo.PredictionBatchPublishResultVo;
import com.jingcaicompass.prediction.vo.PredictionPublishResultVo;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PredictionBatchPublishServiceTest {

    private PredictionMapper predictionMapper;
    private PredictionPublishService publishService;
    private PredictionBatchPublishService service;

    @BeforeEach
    void setUp() {
        predictionMapper = mock(PredictionMapper.class);
        publishService = mock(PredictionPublishService.class);
        service = new PredictionBatchPublishService(predictionMapper, publishService);
    }

    /**
     * 执行顺序按 (matchId, predictionId) 固定：同场多条都要更新 `matches` 行，
     * 顺序不一致的两个批次会以相反方向拿锁而死锁。
     */
    @Test
    void deduplicatesAndPublishesInMatchThenPredictionOrder() {
        when(predictionMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                prediction(3L, 20L),
                prediction(5L, 10L),
                prediction(9L, 20L)
        ));
        List<Long> publishedOrder = new ArrayList<>();
        when(publishService.publish(any(PredictionPublishDto.class), eq("admin")))
                .thenAnswer(invocation -> {
                    publishedOrder.add(invocation.<PredictionPublishDto>getArgument(0).predictionId());
                    return published(invocation.<PredictionPublishDto>getArgument(0).predictionId());
                });

        PredictionBatchPublishResultVo result = service.publish(
                new PredictionBatchPublishDto(List.of(5L, 3L, 9L, 3L)),
                "admin"
        );

        // 重复的 3 只执行一次
        assertThat(publishedOrder).containsExactly(5L, 3L, 9L);
        assertThat(result.requestedCount()).isEqualTo(3);
        assertThat(result.successCount()).isEqualTo(3);
        assertThat(result.failureCount()).isZero();
        assertThat(result.results()).allMatch(PredictionBatchPublishResultVo.Item::success);
    }

    /** 一条坏数据不能把整批带走：已成功的条目不应因为后面失败而回滚。 */
    @Test
    void keepsPublishingAfterOneFailureAndReportsEachItem() {
        when(predictionMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                prediction(1L, 10L),
                prediction(2L, 10L),
                prediction(3L, 10L)
        ));
        when(publishService.publish(eq(new PredictionPublishDto(1L)), eq("admin")))
                .thenReturn(published(1L));
        when(publishService.publish(eq(new PredictionPublishDto(2L)), eq("admin")))
                .thenThrow(new BusinessException(ErrorCode.BUSINESS_ERROR, "prediction status must be DRAFT"));
        when(publishService.publish(eq(new PredictionPublishDto(3L)), eq("admin")))
                .thenReturn(published(3L));

        PredictionBatchPublishResultVo result = service.publish(
                new PredictionBatchPublishDto(List.of(1L, 2L, 3L)),
                "admin"
        );

        assertThat(result.successCount()).isEqualTo(2);
        assertThat(result.failureCount()).isEqualTo(1);
        PredictionBatchPublishResultVo.Item failed = result.results().get(1);
        assertThat(failed.success()).isFalse();
        assertThat(failed.predictionId()).isEqualTo(2L);
        assertThat(failed.matchId()).isEqualTo(10L);
        assertThat(failed.errorMessage()).contains("DRAFT");
        assertThat(failed.predictionHash()).isNull();
    }

    /**
     * 查不到记录的 ID 交给发布服务报错，而不是预先过滤掉。
     * 预先过滤会让「ID 不存在」与「发布成功」在响应里长得一样。
     */
    @Test
    void reportsUnknownIdsAsFailuresAndOrdersThemLast() {
        when(predictionMapper.selectBatchIds(anyCollection())).thenReturn(List.of(prediction(4L, 30L)));
        when(publishService.publish(eq(new PredictionPublishDto(4L)), eq("admin")))
                .thenReturn(published(4L));
        when(publishService.publish(eq(new PredictionPublishDto(7L)), eq("admin")))
                .thenThrow(new BusinessException(ErrorCode.PREDICTION_NOT_FOUND, "prediction not found: 7"));

        PredictionBatchPublishResultVo result = service.publish(
                new PredictionBatchPublishDto(List.of(7L, 4L)),
                "admin"
        );

        assertThat(result.results()).extracting(PredictionBatchPublishResultVo.Item::predictionId)
                .containsExactly(4L, 7L);
        PredictionBatchPublishResultVo.Item unknown = result.results().get(1);
        assertThat(unknown.success()).isFalse();
        assertThat(unknown.matchId()).isNull();
        assertThat(unknown.errorMessage()).contains("not found");
    }

    @Test
    void truncatesLongErrorMessagesSoOneRowCannotFloodTheResponse() {
        when(predictionMapper.selectBatchIds(anyCollection())).thenReturn(List.of(prediction(8L, 40L)));
        when(publishService.publish(any(PredictionPublishDto.class), eq("admin")))
                .thenThrow(new BusinessException(ErrorCode.BUSINESS_ERROR, "x".repeat(500)));

        PredictionBatchPublishResultVo result = service.publish(
                new PredictionBatchPublishDto(List.of(8L)),
                "admin"
        );

        assertThat(result.results().get(0).errorMessage()).hasSize(200);
    }

    @Test
    void asksTheDatabaseOnceForTheWholeBatch() {
        when(predictionMapper.selectBatchIds(anyCollection())).thenReturn(List.of(prediction(1L, 10L)));
        when(publishService.publish(any(PredictionPublishDto.class), eq("admin"))).thenReturn(published(1L));

        service.publish(new PredictionBatchPublishDto(List.of(1L)), "admin");

        verify(predictionMapper).selectBatchIds(anyCollection());
    }

    private Prediction prediction(Long id, Long matchId) {
        Prediction prediction = new Prediction();
        prediction.setId(id);
        prediction.setMatchId(matchId);
        return prediction;
    }

    private PredictionPublishResultVo published(Long predictionId) {
        return new PredictionPublishResultVo(
                predictionId,
                10L,
                "t306-asian-baseline-v6",
                1,
                PredictionStatusEnum.PUBLISHED,
                Instant.parse("2026-10-21T08:00:00Z"),
                Instant.parse("2026-10-21T10:00:00Z"),
                "a".repeat(64),
                false
        );
    }
}
