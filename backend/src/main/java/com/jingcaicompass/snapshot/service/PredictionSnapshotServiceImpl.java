package com.jingcaicompass.snapshot.service;

import com.jingcaicompass.prediction.entity.Prediction;
import com.jingcaicompass.prediction.mapper.PredictionMapper;
import com.jingcaicompass.snapshot.dto.PredictionSnapshotResultDto;
import com.jingcaicompass.snapshot.dto.SnapshotManifestContentDto;
import com.jingcaicompass.snapshot.entity.PredictionSnapshot;
import com.jingcaicompass.snapshot.enums.PredictionSnapshotStatusEnum;
import com.jingcaicompass.snapshot.mapper.PredictionSnapshotMapper;
import com.jingcaicompass.snapshot.storage.SnapshotStagedObject;
import com.jingcaicompass.snapshot.storage.SnapshotStorage;
import com.jingcaicompass.snapshot.storage.SnapshotStorageException;
import com.jingcaicompass.snapshot.storage.SnapshotStoredObject;
import com.jingcaicompass.system.observability.MdcScope;
import com.jingcaicompass.system.observability.PredictionLifecycleMetrics;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 串行化同日发布，协调确定性 manifest、本地对象和快照状态。 */
@Service
@ConditionalOnBean(DataSource.class)
public class PredictionSnapshotServiceImpl implements PredictionSnapshotService {

    private static final int ADVISORY_LOCK_NAMESPACE = 130305;
    private static final int FAILURE_REASON_LIMIT = 1000;
    private static final Logger log = LoggerFactory.getLogger(PredictionSnapshotServiceImpl.class);

    private final PredictionMapper predictionMapper;
    private final PredictionSnapshotMapper snapshotMapper;
    private final SnapshotManifestGenerator manifestGenerator;
    private final SnapshotStorage snapshotStorage;
    private final JdbcTemplate jdbcTemplate;
    private final PredictionLifecycleMetrics lifecycleMetrics;

    public PredictionSnapshotServiceImpl(
            PredictionMapper predictionMapper,
            PredictionSnapshotMapper snapshotMapper,
            SnapshotManifestGenerator manifestGenerator,
            SnapshotStorage snapshotStorage,
            JdbcTemplate jdbcTemplate,
            PredictionLifecycleMetrics lifecycleMetrics
    ) {
        this.predictionMapper = predictionMapper;
        this.snapshotMapper = snapshotMapper;
        this.manifestGenerator = manifestGenerator;
        this.snapshotStorage = snapshotStorage;
        this.jdbcTemplate = jdbcTemplate;
        this.lifecycleMetrics = lifecycleMetrics;
    }

    @Override
    @Transactional
    public PredictionSnapshotResultDto publish(LocalDate snapshotDate) {
        // 1) 校验竞彩业务日，并以事务级 advisory lock 串行化同日多实例发布
        LocalDate businessDate = Objects.requireNonNull(
                snapshotDate,
                "snapshotDate must not be null"
        );
        acquireBusinessDateLock(businessDate);

        // 2) 单条 SQL 读取每个比赛和模型的最高公开版本，生成确定性 manifest
        List<Prediction> predictions =
                predictionMapper.selectCurrentPublishedByLotteryDate(businessDate);
        SnapshotManifestContentDto manifest;
        try {
            manifest = manifestGenerator.generate(businessDate, predictions);
        } catch (IllegalArgumentException exception) {
            lifecycleMetrics.recordSnapshotHashMismatch();
            throw exception;
        }

        // 3) 相同事实已有完整对象时直接复用，不增加数据库版本
        PredictionSnapshot reusable = findReusableSnapshot(businessDate, manifest);
        if (reusable != null) {
            lifecycleMetrics.recordSnapshotPublish("reused");
            return toResult(reusable, manifest.predictionCount(), true);
        }

        // 4) 所有历史状态都占用版本号，创建新的 PENDING 元数据
        int snapshotVersion = nextVersion(
                snapshotMapper.selectMaxVersion(businessDate),
                businessDate
        );
        PredictionSnapshot pending = new PredictionSnapshot();
        pending.setSnapshotDate(businessDate);
        pending.setSnapshotVersion(snapshotVersion);
        pending.setSnapshotStatus(PredictionSnapshotStatusEnum.PENDING);
        if (snapshotMapper.insert(pending) != 1 || pending.getId() == null) {
            throw new IllegalStateException("failed to reserve prediction snapshot version");
        }

        String objectKey = buildObjectKey(
                businessDate,
                snapshotVersion,
                manifest.sha256()
        );
        SnapshotStagedObject stagedObject = null;
        try {
            // 5) 先写临时对象并回读校验，再原子发布到不可覆盖对象键
            stagedObject = snapshotStorage.stage(
                    objectKey,
                    manifest.bytes(),
                    manifest.sha256()
            );
            SnapshotStoredObject storedObject = snapshotStorage.publish(stagedObject);
            validateStoredObject(storedObject, manifest);

            // 6) 文件发布成功后才允许 PENDING 条件更新为 PUBLISHED
            PredictionSnapshot published = snapshotMapper.publishPending(
                    pending.getId(),
                    manifest.sha256(),
                    storedObject.storageType().getCode(),
                    storedObject.objectKey(),
                    storedObject.objectVersion(),
                    storedObject.fileUrl(),
                    storedObject.contentType(),
                    storedObject.contentLength()
            );
            if (published == null) {
                throw new IllegalStateException(
                        "prediction snapshot state changed before publication"
                );
            }
            lifecycleMetrics.recordSnapshotPublish("published");
            return toResult(published, manifest.predictionCount(), false);
        } catch (SnapshotStorageException exception) {
            // 7) 存储失败保留 FAILED 版本，禁止被后续运行静默覆盖
            snapshotStorage.discard(stagedObject);
            String failureReason = truncateFailure(exception.getMessage());
            lifecycleMetrics.recordSnapshotPublish("failed");
            if (snapshotMapper.failPending(pending.getId(), failureReason) != 1) {
                throw new IllegalStateException(
                        "failed to record prediction snapshot storage failure",
                        exception
                );
            }
            pending.setSnapshotStatus(PredictionSnapshotStatusEnum.FAILED);
            pending.setFailureReason(failureReason);
            return toResult(pending, manifest.predictionCount(), false);
        }
    }

    private void acquireBusinessDateLock(LocalDate businessDate) {
        int dateKey = Math.toIntExact(businessDate.toEpochDay());
        jdbcTemplate.execute(
                "SELECT pg_advisory_xact_lock(?, ?)",
                (PreparedStatementCallback<Void>) preparedStatement -> {
                    preparedStatement.setInt(1, ADVISORY_LOCK_NAMESPACE);
                    preparedStatement.setInt(2, dateKey);
                    preparedStatement.execute();
                    return null;
                }
        );
    }

    private PredictionSnapshot findReusableSnapshot(
            LocalDate businessDate,
            SnapshotManifestContentDto manifest
    ) {
        List<PredictionSnapshot> candidates = snapshotMapper.selectPublishedByDateAndHash(
                businessDate,
                manifest.sha256()
        );
        for (PredictionSnapshot candidate : candidates) {
            if (!snapshotStorage.storageType().getCode().equals(candidate.getStorageType())
                    || candidate.getContentLength() == null
                    || candidate.getObjectKey() == null) {
                continue;
            }
            if (snapshotStorage.verify(
                    candidate.getObjectKey(),
                    manifest.sha256(),
                    candidate.getContentLength()
            )) {
                return candidate;
            }
            try (MdcScope ignored = MdcScope.snapshot(candidate.getId())) {
                lifecycleMetrics.recordSnapshotHashMismatch();
                log.warn("event=prediction_snapshot_integrity_failed reason=HASH_MISMATCH");
            }
        }
        return null;
    }

    private int nextVersion(Integer currentVersion, LocalDate businessDate) {
        if (currentVersion == null) {
            return 1;
        }
        if (currentVersion == Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "prediction snapshot version exhausted: " + businessDate
            );
        }
        return currentVersion + 1;
    }

    private String buildObjectKey(
            LocalDate businessDate,
            int snapshotVersion,
            String snapshotHash
    ) {
        return "prediction-snapshots/"
                + businessDate
                + "/v"
                + String.format(java.util.Locale.ROOT, "%06d", snapshotVersion)
                + "-"
                + snapshotHash
                + ".json";
    }

    private void validateStoredObject(
            SnapshotStoredObject storedObject,
            SnapshotManifestContentDto manifest
    ) {
        if (storedObject == null
                || storedObject.storageType() != snapshotStorage.storageType()
                || !manifest.sha256().equals(storedObject.sha256())
                || storedObject.contentLength() != manifest.bytes().length) {
            lifecycleMetrics.recordSnapshotHashMismatch();
            throw new SnapshotStorageException(
                    "published snapshot metadata does not match manifest"
            );
        }
        if (!snapshotStorage.verify(
                storedObject.objectKey(),
                storedObject.sha256(),
                storedObject.contentLength()
        )) {
            lifecycleMetrics.recordSnapshotHashMismatch();
            throw new SnapshotStorageException("published snapshot object is not readable");
        }
    }

    private String truncateFailure(String message) {
        String value = message == null || message.isBlank()
                ? "snapshot storage failed"
                : message.trim();
        return value.length() <= FAILURE_REASON_LIMIT
                ? value
                : value.substring(0, FAILURE_REASON_LIMIT);
    }

    private PredictionSnapshotResultDto toResult(
            PredictionSnapshot snapshot,
            int predictionCount,
            boolean reused
    ) {
        return new PredictionSnapshotResultDto(
                snapshot.getId(),
                snapshot.getSnapshotDate(),
                snapshot.getSnapshotVersion(),
                snapshot.getSnapshotStatus(),
                snapshot.getSnapshotHash(),
                predictionCount,
                snapshot.getStorageType(),
                snapshot.getObjectKey(),
                snapshot.getObjectVersion(),
                snapshot.getFileUrl(),
                snapshot.getContentType(),
                snapshot.getContentLength(),
                snapshot.getPublishedAt(),
                snapshot.getFailureReason(),
                reused
        );
    }
}
