package com.jingcaicompass.prediction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jingcaicompass.audit.enums.AuditActionTypeEnum;
import com.jingcaicompass.audit.enums.AuditTargetTypeEnum;
import com.jingcaicompass.audit.service.AuditLogService;
import com.jingcaicompass.match.entity.MatchEntity;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import com.jingcaicompass.match.mapper.MatchMapper;
import com.jingcaicompass.prediction.dto.PredictionPublishDto;
import com.jingcaicompass.prediction.entity.Prediction;
import com.jingcaicompass.prediction.enums.ConfidenceLevelEnum;
import com.jingcaicompass.prediction.enums.HandicapPickEnum;
import com.jingcaicompass.prediction.enums.PredictionStatusEnum;
import com.jingcaicompass.prediction.mapper.PredictionMapper;
import com.jingcaicompass.prediction.vo.PredictionPublishResultVo;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PredictionPublishServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-26T08:00:00.123456Z");
    private static final Instant KICKOFF = Instant.parse("2026-07-26T10:00:00Z");
    private static final String PREDICTION_HASH = "f".repeat(64);

    @Mock
    private PredictionMapper predictionMapper;

    @Mock
    private MatchMapper matchMapper;

    @Mock
    private PredictionContentHasher contentHasher;

    @Mock
    private AuditLogService auditLogService;

    private PredictionPublishService service;

    @BeforeEach
    void setUp() {
        service = new PredictionPublishServiceImpl(
                predictionMapper,
                matchMapper,
                contentHasher,
                auditLogService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void publishesFirstDraftAndAuditsAuthenticatedOperator() {
        Prediction draft = prediction(1L, PredictionStatusEnum.DRAFT);
        stubLockedRows(draft, match(MatchStatusEnum.SCHEDULED, KICKOFF));
        when(predictionMapper.selectLatestPublishedVersion(101L, "model-v1")).thenReturn(null);
        when(contentHasher.sha256Hex(draft, NOW, KICKOFF)).thenReturn(PREDICTION_HASH);
        when(predictionMapper.publishDraft(1L, NOW, KICKOFF, PREDICTION_HASH)).thenReturn(1);

        PredictionPublishResultVo result = service.publish(
                new PredictionPublishDto(1L),
                " authenticated-admin "
        );

        assertThat(result.predictionId()).isEqualTo(1L);
        assertThat(result.predictionVersion()).isEqualTo(1);
        assertThat(result.predictionStatus()).isEqualTo(PredictionStatusEnum.PUBLISHED);
        assertThat(result.publishTime()).isEqualTo(NOW);
        assertThat(result.lockTime()).isEqualTo(KICKOFF);
        assertThat(result.predictionHash()).isEqualTo(PREDICTION_HASH);
        assertThat(result.reused()).isFalse();
        verify(auditLogService).append(
                eq("authenticated-admin"),
                eq(AuditTargetTypeEnum.PREDICTION),
                eq("1"),
                eq(AuditActionTypeEnum.PUBLISH),
                eq("predictionStatus"),
                eq("status=DRAFT;version=1;publishTime=null;lockTime=null;hash=null"),
                eq("status=PUBLISHED;version=1;publishTime=" + NOW
                        + ";lockTime=" + KICKOFF + ";hash=" + PREDICTION_HASH)
        );
    }

    @Test
    void publishesOnlyTheNextImportedDraftVersion() {
        Prediction draft = prediction(2L, PredictionStatusEnum.DRAFT);
        draft.setPredictionVersion(2);
        stubLockedRows(draft, match(MatchStatusEnum.LOCKED, KICKOFF));
        when(predictionMapper.selectLatestPublishedVersion(101L, "model-v1")).thenReturn(1);
        when(contentHasher.sha256Hex(draft, NOW, KICKOFF)).thenReturn(PREDICTION_HASH);
        when(predictionMapper.publishDraft(2L, NOW, KICKOFF, PREDICTION_HASH)).thenReturn(1);

        PredictionPublishResultVo result = service.publish(
                new PredictionPublishDto(2L),
                "admin"
        );

        assertThat(result.predictionVersion()).isEqualTo(2);
        assertThat(result.reused()).isFalse();
        verify(predictionMapper).publishDraft(2L, NOW, KICKOFF, PREDICTION_HASH);
    }

    @ParameterizedTest
    @MethodSource("alreadyPublishedStatuses")
    void reusesAlreadyPublishedOrLockedResultWithoutWritingOrAuditing(
            PredictionStatusEnum status
    ) {
        Prediction existing = prediction(1L, status);
        existing.setPublishTime(NOW.minusSeconds(60));
        existing.setLockTime(KICKOFF);
        existing.setPredictionHash(PREDICTION_HASH);
        stubLockedRows(existing, match(MatchStatusEnum.FINISHED, NOW.minusSeconds(1)));

        PredictionPublishResultVo result = service.publish(
                new PredictionPublishDto(1L),
                "admin"
        );

        assertThat(result.predictionStatus()).isEqualTo(status);
        assertThat(result.reused()).isTrue();
        assertThat(result.predictionHash()).isEqualTo(PREDICTION_HASH);
        verify(predictionMapper, never()).publishDraft(any(), any(), any(), any());
        verifyNoInteractions(contentHasher, auditLogService);
    }

    @ParameterizedTest
    @MethodSource("invalidMatches")
    void rejectsInvalidMatchStateOrPublishBoundary(MatchEntity match, String message) {
        Prediction draft = prediction(1L, PredictionStatusEnum.DRAFT);
        stubLockedRows(draft, match);

        assertConflict(() -> service.publish(new PredictionPublishDto(1L), "admin"), message);
        verify(predictionMapper, never()).selectLatestPublishedVersion(any(), any());
        verifyNoInteractions(contentHasher, auditLogService);
    }

    @Test
    void rejectsSkippedOrStaleVersionBeforeHashing() {
        Prediction skipped = prediction(3L, PredictionStatusEnum.DRAFT);
        skipped.setPredictionVersion(3);
        stubLockedRows(skipped, match(MatchStatusEnum.SCHEDULED, KICKOFF));
        when(predictionMapper.selectLatestPublishedVersion(101L, "model-v1")).thenReturn(1);

        assertConflict(
                () -> service.publish(new PredictionPublishDto(3L), "admin"),
                "expected 2 but was 3"
        );
        verifyNoInteractions(contentHasher, auditLogService);
    }

    @Test
    void rejectsConditionalUpdateConflictAndDoesNotAudit() {
        Prediction draft = prediction(1L, PredictionStatusEnum.DRAFT);
        stubLockedRows(draft, match(MatchStatusEnum.SCHEDULED, KICKOFF));
        when(predictionMapper.selectLatestPublishedVersion(101L, "model-v1")).thenReturn(null);
        when(contentHasher.sha256Hex(draft, NOW, KICKOFF)).thenReturn(PREDICTION_HASH);
        when(predictionMapper.publishDraft(1L, NOW, KICKOFF, PREDICTION_HASH)).thenReturn(0);

        assertConflict(
                () -> service.publish(new PredictionPublishDto(1L), "admin"),
                "publish conflict"
        );
        verifyNoInteractions(auditLogService);
    }

    @Test
    void rejectsMissingPredictionAndUntrustedOperator() {
        when(predictionMapper.selectById(99L)).thenReturn(null);

        assertConflict(
                () -> service.publish(new PredictionPublishDto(99L), "admin"),
                "not found"
        );
        assertThatThrownBy(() -> service.publish(new PredictionPublishDto(1L), " "))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.AUTH_UNAUTHORIZED));
    }

    private void stubLockedRows(Prediction prediction, MatchEntity match) {
        when(predictionMapper.selectById(prediction.getId())).thenReturn(prediction);
        when(matchMapper.selectByIdForUpdate(prediction.getMatchId())).thenReturn(match);
        when(predictionMapper.selectByIdForUpdate(prediction.getId())).thenReturn(prediction);
    }

    private Prediction prediction(long id, PredictionStatusEnum status) {
        Prediction prediction = new Prediction();
        prediction.setId(id);
        prediction.setMatchId(101L);
        prediction.setModelVersion("model-v1");
        prediction.setFeatureVersion("feature-v1");
        prediction.setGenerationBatchId("batch-v1");
        prediction.setGenerationBatchHash("a".repeat(64));
        prediction.setPredictionVersion(1);
        prediction.setHomeWinProb(new BigDecimal("0.450000"));
        prediction.setDrawProb(new BigDecimal("0.300000"));
        prediction.setAwayWinProb(new BigDecimal("0.250000"));
        prediction.setHandicapPick(HandicapPickEnum.HOME_WIN);
        prediction.setExpectedTotalGoals(new BigDecimal("2.50"));
        prediction.setConfidenceLevel(ConfidenceLevelEnum.HIGH);
        prediction.setAnalysisSummary("预测发布单元测试");
        prediction.setGeneratedAt(NOW.minusSeconds(300));
        prediction.setPredictionStatus(status);
        return prediction;
    }

    private MatchEntity match(MatchStatusEnum status, Instant kickoff) {
        MatchEntity match = new MatchEntity();
        match.setId(101L);
        match.setMatchStatus(status);
        match.setKickoffTime(kickoff);
        return match;
    }

    private void assertConflict(ThrowingRunnable runnable, String message) {
        assertThatThrownBy(runnable::run)
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(ErrorCode.BUSINESS_ERROR);
                    assertThat(exception).hasMessageContaining(message);
                });
    }

    private static Stream<Arguments> alreadyPublishedStatuses() {
        return Stream.of(
                Arguments.of(PredictionStatusEnum.PUBLISHED),
                Arguments.of(PredictionStatusEnum.LOCKED)
        );
    }

    private static Stream<Arguments> invalidMatches() {
        return Stream.of(
                Arguments.of(
                        staticMatch(MatchStatusEnum.FINISHED, KICKOFF),
                        "match status"
                ),
                Arguments.of(
                        staticMatch(MatchStatusEnum.SCHEDULED, NOW),
                        "deadline"
                ),
                Arguments.of(
                        staticMatch(MatchStatusEnum.LOCKED, NOW.minusSeconds(1)),
                        "deadline"
                )
        );
    }

    private static MatchEntity staticMatch(MatchStatusEnum status, Instant kickoff) {
        MatchEntity match = new MatchEntity();
        match.setId(101L);
        match.setMatchStatus(status);
        match.setKickoffTime(kickoff);
        return match;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }
}
