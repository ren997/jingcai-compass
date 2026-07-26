package com.jingcaicompass.prediction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jingcaicompass.audit.enums.AuditActionTypeEnum;
import com.jingcaicompass.audit.enums.AuditTargetTypeEnum;
import com.jingcaicompass.audit.service.AuditLogService;
import com.jingcaicompass.prediction.dto.PredictionLockCandidateDto;
import com.jingcaicompass.prediction.mapper.PredictionMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PredictionLockWorkerTest {

    private static final Instant LOCK_TIME = Instant.parse("2026-07-26T08:00:00Z");
    private static final Instant DATABASE_TIME = Instant.parse("2026-07-26T08:00:02Z");

    @Mock
    private PredictionMapper predictionMapper;

    @Mock
    private AuditLogService auditLogService;

    private PredictionLockWorker worker;

    @BeforeEach
    void setUp() {
        worker = new PredictionLockWorker(predictionMapper, auditLogService);
    }

    @Test
    void locksAndAuditsOnePredictionInOrder() {
        PredictionLockCandidateDto candidate = candidate(11L);
        when(predictionMapper.selectNextDueForUpdate(List.of())).thenReturn(candidate);
        when(predictionMapper.lockPublishedPrediction(11L)).thenReturn(1);

        PredictionLockWorker.LockResult result = worker.lockNext(List.of());

        assertThat(result.predictionId()).isEqualTo(11L);
        assertThat(result.lockTime()).isEqualTo(LOCK_TIME);
        assertThat(result.lockedAt()).isEqualTo(DATABASE_TIME);
        verify(auditLogService).append(
                PredictionLockWorker.SYSTEM_OPERATOR,
                AuditTargetTypeEnum.PREDICTION,
                "11",
                AuditActionTypeEnum.LOCK,
                "predictionStatus",
                "status=PUBLISHED;lockTime=" + LOCK_TIME,
                "status=LOCKED;lockTime=" + LOCK_TIME
        );
    }

    @Test
    void returnsNullWhenNoDuePredictionCanBeClaimed() {
        when(predictionMapper.selectNextDueForUpdate(anyCollection())).thenReturn(null);

        assertThat(worker.lockNext(List.of())).isNull();

        verify(predictionMapper, never()).lockPublishedPrediction(11L);
        verifyNoInteractions(auditLogService);
    }

    @Test
    void exposesPredictionIdWhenConditionalUpdateLoses() {
        when(predictionMapper.selectNextDueForUpdate(anyCollection())).thenReturn(candidate(12L));
        when(predictionMapper.lockPublishedPrediction(12L)).thenReturn(0);

        assertThatThrownBy(() -> worker.lockNext(List.of()))
                .isInstanceOfSatisfying(PredictionLockItemException.class, exception -> {
                    assertThat(exception.predictionId()).isEqualTo(12L);
                    assertThat(exception.stage()).isEqualTo("update");
                });

        verifyNoInteractions(auditLogService);
    }

    @Test
    void wrapsAuditFailureSoTransactionCanRollBackTheItem() {
        when(predictionMapper.selectNextDueForUpdate(anyCollection())).thenReturn(candidate(13L));
        when(predictionMapper.lockPublishedPrediction(13L)).thenReturn(1);
        org.mockito.Mockito.doThrow(new IllegalStateException("audit unavailable"))
                .when(auditLogService)
                .append(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString()
                );

        assertThatThrownBy(() -> worker.lockNext(List.of()))
                .isInstanceOfSatisfying(PredictionLockItemException.class, exception -> {
                    assertThat(exception.predictionId()).isEqualTo(13L);
                    assertThat(exception.stage()).isEqualTo("audit");
                });
    }

    private PredictionLockCandidateDto candidate(Long predictionId) {
        PredictionLockCandidateDto candidate = new PredictionLockCandidateDto();
        candidate.setPredictionId(predictionId);
        candidate.setLockTime(LOCK_TIME);
        candidate.setDatabaseTime(DATABASE_TIME);
        return candidate;
    }
}
