package com.jingcaicompass.snapshot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jingcaicompass.prediction.mapper.PredictionMapper;
import com.jingcaicompass.snapshot.dto.SnapshotManifestContentDto;
import com.jingcaicompass.snapshot.entity.PredictionSnapshot;
import com.jingcaicompass.snapshot.enums.PredictionSnapshotStatusEnum;
import com.jingcaicompass.snapshot.enums.SnapshotStorageTypeEnum;
import com.jingcaicompass.snapshot.mapper.PredictionSnapshotMapper;
import com.jingcaicompass.snapshot.storage.SnapshotStagedObject;
import com.jingcaicompass.snapshot.storage.SnapshotStorage;
import com.jingcaicompass.snapshot.storage.SnapshotStorageException;
import com.jingcaicompass.snapshot.storage.SnapshotStoredObject;
import com.jingcaicompass.system.observability.PredictionLifecycleMetrics;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class PredictionSnapshotServiceTest {

    private static final LocalDate SNAPSHOT_DATE = LocalDate.of(2026, 7, 26);
    private static final byte[] MANIFEST_BYTES =
            "{\"schemaVersion\":1}".getBytes(StandardCharsets.UTF_8);
    private static final String MANIFEST_HASH = "a".repeat(64);

    @Mock
    private PredictionMapper predictionMapper;

    @Mock
    private PredictionSnapshotMapper snapshotMapper;

    @Mock
    private SnapshotManifestGenerator manifestGenerator;

    @Mock
    private SnapshotStorage snapshotStorage;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private PredictionLifecycleMetrics lifecycleMetrics;

    private PredictionSnapshotService service;

    @BeforeEach
    void setUp() {
        service = new PredictionSnapshotServiceImpl(
                predictionMapper,
                snapshotMapper,
                manifestGenerator,
                snapshotStorage,
                jdbcTemplate,
                lifecycleMetrics
        );
        when(predictionMapper.selectCurrentPublishedByLotteryDate(SNAPSHOT_DATE))
                .thenReturn(List.of());
        when(manifestGenerator.generate(SNAPSHOT_DATE, List.of()))
                .thenReturn(manifest());
        lenient().when(snapshotStorage.storageType()).thenReturn(SnapshotStorageTypeEnum.LOCAL);
    }

    @Test
    void publishesFirstSnapshotAfterTemporaryAndFinalVerification() {
        when(snapshotMapper.selectPublishedByDateAndHash(SNAPSHOT_DATE, MANIFEST_HASH))
                .thenReturn(List.of());
        when(snapshotMapper.selectMaxVersion(SNAPSHOT_DATE)).thenReturn(null);
        reservePending(100L);
        SnapshotStagedObject staged = stagedObject(1);
        SnapshotStoredObject stored = storedObject(1);
        when(snapshotStorage.stage(any(), eq(MANIFEST_BYTES), eq(MANIFEST_HASH)))
                .thenReturn(staged);
        when(snapshotStorage.publish(staged)).thenReturn(stored);
        when(snapshotStorage.verify(stored.objectKey(), MANIFEST_HASH, MANIFEST_BYTES.length))
                .thenReturn(true);
        when(snapshotMapper.publishPending(
                eq(100L),
                eq(MANIFEST_HASH),
                eq("LOCAL"),
                eq(stored.objectKey()),
                eq(null),
                eq(stored.fileUrl()),
                eq("application/json"),
                eq((long) MANIFEST_BYTES.length)
        )).thenReturn(publishedSnapshot(100L, 1));

        var result = service.publish(SNAPSHOT_DATE);

        assertThat(result.snapshotId()).isEqualTo(100L);
        assertThat(result.snapshotVersion()).isEqualTo(1);
        assertThat(result.snapshotStatus()).isEqualTo(PredictionSnapshotStatusEnum.PUBLISHED);
        assertThat(result.snapshotHash()).isEqualTo(MANIFEST_HASH);
        assertThat(result.predictionCount()).isEqualTo(2);
        assertThat(result.reused()).isFalse();
        verify(snapshotMapper, never()).failPending(anyLong(), any());
        verify(lifecycleMetrics).recordSnapshotPublish("published");
    }

    @Test
    void reusesExistingPublishedSnapshotOnlyAfterObjectVerification() {
        PredictionSnapshot existing = publishedSnapshot(10L, 3);
        when(snapshotMapper.selectPublishedByDateAndHash(SNAPSHOT_DATE, MANIFEST_HASH))
                .thenReturn(List.of(existing));
        when(snapshotStorage.verify(
                existing.getObjectKey(),
                MANIFEST_HASH,
                existing.getContentLength()
        )).thenReturn(true);

        var result = service.publish(SNAPSHOT_DATE);

        assertThat(result.snapshotId()).isEqualTo(10L);
        assertThat(result.snapshotVersion()).isEqualTo(3);
        assertThat(result.reused()).isTrue();
        verify(snapshotMapper, never()).selectMaxVersion(any());
        verify(snapshotStorage, never()).stage(any(), any(), any());
        verify(lifecycleMetrics).recordSnapshotPublish("reused");
    }

    @Test
    void damagedPublishedObjectCreatesNextRepairVersion() {
        PredictionSnapshot damaged = publishedSnapshot(10L, 2);
        when(snapshotMapper.selectPublishedByDateAndHash(SNAPSHOT_DATE, MANIFEST_HASH))
                .thenReturn(List.of(damaged));
        when(snapshotStorage.verify(
                damaged.getObjectKey(),
                MANIFEST_HASH,
                damaged.getContentLength()
        )).thenReturn(false);
        when(snapshotMapper.selectMaxVersion(SNAPSHOT_DATE)).thenReturn(2);
        reservePending(11L);
        SnapshotStagedObject staged = stagedObject(3);
        SnapshotStoredObject stored = storedObject(3);
        when(snapshotStorage.stage(any(), eq(MANIFEST_BYTES), eq(MANIFEST_HASH)))
                .thenReturn(staged);
        when(snapshotStorage.publish(staged)).thenReturn(stored);
        when(snapshotStorage.verify(stored.objectKey(), MANIFEST_HASH, MANIFEST_BYTES.length))
                .thenReturn(true);
        when(snapshotMapper.publishPending(
                eq(11L),
                eq(MANIFEST_HASH),
                eq("LOCAL"),
                eq(stored.objectKey()),
                eq(null),
                eq(stored.fileUrl()),
                eq("application/json"),
                eq((long) MANIFEST_BYTES.length)
        )).thenReturn(publishedSnapshot(11L, 3));

        var result = service.publish(SNAPSHOT_DATE);

        assertThat(result.snapshotVersion()).isEqualTo(3);
        assertThat(result.reused()).isFalse();
        verify(lifecycleMetrics).recordSnapshotHashMismatch();
    }

    @Test
    void storageFailureMarksReservedVersionFailed() {
        when(snapshotMapper.selectPublishedByDateAndHash(SNAPSHOT_DATE, MANIFEST_HASH))
                .thenReturn(List.of());
        when(snapshotMapper.selectMaxVersion(SNAPSHOT_DATE)).thenReturn(4);
        reservePending(20L);
        SnapshotStorageException failure = new SnapshotStorageException("disk full");
        when(snapshotStorage.stage(any(), eq(MANIFEST_BYTES), eq(MANIFEST_HASH)))
                .thenThrow(failure);
        when(snapshotMapper.failPending(20L, "disk full")).thenReturn(1);

        var result = service.publish(SNAPSHOT_DATE);

        assertThat(result.snapshotVersion()).isEqualTo(5);
        assertThat(result.snapshotStatus()).isEqualTo(PredictionSnapshotStatusEnum.FAILED);
        assertThat(result.failureReason()).isEqualTo("disk full");
        assertThat(result.reused()).isFalse();
        verify(snapshotMapper, never()).publishPending(
                anyLong(), any(), any(), any(), any(), any(), any(), anyLong()
        );
        verify(lifecycleMetrics).recordSnapshotPublish("failed");
    }

    @Test
    void databaseFinalizeFailureNeverReturnsOrRecordsSuccess() {
        when(snapshotMapper.selectPublishedByDateAndHash(SNAPSHOT_DATE, MANIFEST_HASH))
                .thenReturn(List.of());
        when(snapshotMapper.selectMaxVersion(SNAPSHOT_DATE)).thenReturn(null);
        reservePending(30L);
        SnapshotStagedObject staged = stagedObject(1);
        SnapshotStoredObject stored = storedObject(1);
        when(snapshotStorage.stage(any(), eq(MANIFEST_BYTES), eq(MANIFEST_HASH)))
                .thenReturn(staged);
        when(snapshotStorage.publish(staged)).thenReturn(stored);
        when(snapshotStorage.verify(stored.objectKey(), MANIFEST_HASH, MANIFEST_BYTES.length))
                .thenReturn(true);
        RuntimeException databaseFailure = new IllegalStateException("database unavailable");
        when(snapshotMapper.publishPending(
                eq(30L),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                anyLong()
        )).thenThrow(databaseFailure);

        assertThatThrownBy(() -> service.publish(SNAPSHOT_DATE)).isSameAs(databaseFailure);

        verify(snapshotMapper, never()).failPending(anyLong(), any());
    }

    private SnapshotManifestContentDto manifest() {
        return new SnapshotManifestContentDto(MANIFEST_BYTES, MANIFEST_HASH, 2);
    }

    private void reservePending(long snapshotId) {
        when(snapshotMapper.insert(any(PredictionSnapshot.class))).thenAnswer(invocation -> {
            PredictionSnapshot pending = invocation.getArgument(0);
            pending.setId(snapshotId);
            return 1;
        });
    }

    private SnapshotStagedObject stagedObject(int version) {
        return new SnapshotStagedObject(
                objectKey(version),
                "temporary-" + version,
                MANIFEST_HASH,
                MANIFEST_BYTES.length
        );
    }

    private SnapshotStoredObject storedObject(int version) {
        return new SnapshotStoredObject(
                SnapshotStorageTypeEnum.LOCAL,
                objectKey(version),
                null,
                "file:///snapshots/" + version + ".json",
                "application/json",
                MANIFEST_BYTES.length,
                MANIFEST_HASH
        );
    }

    private PredictionSnapshot publishedSnapshot(long snapshotId, int version) {
        PredictionSnapshot snapshot = new PredictionSnapshot();
        snapshot.setId(snapshotId);
        snapshot.setSnapshotDate(SNAPSHOT_DATE);
        snapshot.setSnapshotVersion(version);
        snapshot.setSnapshotStatus(PredictionSnapshotStatusEnum.PUBLISHED);
        snapshot.setSnapshotHash(MANIFEST_HASH);
        snapshot.setStorageType("LOCAL");
        snapshot.setObjectKey(objectKey(version));
        snapshot.setFileUrl("file:///snapshots/" + version + ".json");
        snapshot.setContentType("application/json");
        snapshot.setContentLength((long) MANIFEST_BYTES.length);
        snapshot.setPublishedAt(Instant.parse("2026-07-26T09:00:00Z"));
        return snapshot;
    }

    private String objectKey(int version) {
        return "prediction-snapshots/2026-07-26/v"
                + String.format("%06d", version)
                + "-"
                + MANIFEST_HASH
                + ".json";
    }
}
