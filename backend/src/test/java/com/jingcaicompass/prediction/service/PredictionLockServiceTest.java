package com.jingcaicompass.prediction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PredictionLockServiceTest {

    private static final Instant LOCK_TIME = Instant.parse("2026-07-26T08:00:00Z");
    private static final Instant LOCKED_AT = Instant.parse("2026-07-26T08:00:03Z");

    @Mock
    private PredictionLockWorker worker;

    @Mock
    private PredictionLockMetrics metrics;

    private PredictionLockService service;

    @BeforeEach
    void setUp() {
        service = new PredictionLockServiceImpl(worker, metrics);
    }

    @Test
    void locksUntilNoDuePredictionRemains() {
        when(worker.lockNext(anyCollection()))
                .thenReturn(
                        item(1L),
                        item(2L),
                        (PredictionLockWorker.LockResult) null
                );

        var result = service.lockDuePredictions(100);

        assertThat(result.lockedCount()).isEqualTo(2);
        assertThat(result.failedCount()).isZero();
        assertThat(result.lockedPredictionIds()).containsExactly(1L, 2L);
        assertThat(result.failedPredictionIds()).isEmpty();
        assertThat(result.durationMs()).isNotNegative();
        verify(metrics, times(2)).recordLocked(Duration.ofSeconds(3));
        verify(metrics).recordBatch(any(Duration.class), eq("success"));
    }

    @Test
    void retriesWhenAClaimIsTemporarilySkipped() {
        when(worker.lockNext(anyCollection()))
                .thenReturn(null, item(3L), (PredictionLockWorker.LockResult) null);

        var result = service.lockDuePredictions(100);

        assertThat(result.lockedPredictionIds()).containsExactly(3L);
        assertThat(result.failedPredictionIds()).isEmpty();
        verify(worker, times(8)).lockNext(anyCollection());
    }

    @Test
    void excludesFailedItemAndContinuesWithOtherPredictions() {
        PredictionLockItemException failure = new PredictionLockItemException(
                10L,
                "audit",
                "audit failed",
                new IllegalStateException("audit failed")
        );
        when(worker.lockNext(anyCollection()))
                .thenThrow(failure)
                .thenReturn(item(11L), (PredictionLockWorker.LockResult) null);

        var result = service.lockDuePredictions(100);

        assertThat(result.lockedPredictionIds()).containsExactly(11L);
        assertThat(result.failedPredictionIds()).containsExactly(10L);
        verify(metrics).recordItemFailure();
        verify(metrics).recordBatch(any(Duration.class), eq("partial"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> exclusions = ArgumentCaptor.forClass(Collection.class);
        verify(worker, times(8)).lockNext(exclusions.capture());
        assertThat(exclusions.getAllValues().get(1)).containsExactly(10L);
    }

    @Test
    void respectsConfiguredBatchSize() {
        when(worker.lockNext(anyCollection()))
                .thenReturn(item(1L), item(2L), item(3L));

        var result = service.lockDuePredictions(2);

        assertThat(result.lockedPredictionIds()).containsExactly(1L, 2L);
        verify(worker, times(2)).lockNext(anyCollection());
    }

    @Test
    void recordsBatchExceptionAndRethrowsInfrastructureFailure() {
        RuntimeException failure = new IllegalStateException("database unavailable");
        when(worker.lockNext(anyCollection())).thenThrow(failure);

        assertThatThrownBy(() -> service.lockDuePredictions(100)).isSameAs(failure);

        verify(metrics).recordBatchFailure();
        verify(metrics).recordBatch(any(Duration.class), eq("failed"));
        verify(metrics, never()).recordItemFailure();
    }

    @Test
    void repeatedEmptyRunIsIdempotent() {
        when(worker.lockNext(anyCollection())).thenReturn(null);

        var result = service.lockDuePredictions(100);

        assertThat(result.lockedCount()).isZero();
        assertThat(result.failedCount()).isZero();
        verify(metrics, never()).recordLocked(any());
        verify(metrics).recordBatch(any(Duration.class), eq("success"));
    }

    @Test
    void rejectsBatchSizeOutsideSupportedRange() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.lockDuePredictions(0))
                .withMessageContaining("between 1 and 1000");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.lockDuePredictions(1001))
                .withMessageContaining("between 1 and 1000");
    }

    private PredictionLockWorker.LockResult item(Long predictionId) {
        return new PredictionLockWorker.LockResult(predictionId, LOCK_TIME, LOCKED_AT);
    }
}
