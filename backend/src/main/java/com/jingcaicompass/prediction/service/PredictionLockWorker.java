package com.jingcaicompass.prediction.service;

import com.jingcaicompass.audit.enums.AuditActionTypeEnum;
import com.jingcaicompass.audit.enums.AuditTargetTypeEnum;
import com.jingcaicompass.audit.service.AuditLogService;
import com.jingcaicompass.prediction.dto.PredictionLockCandidateDto;
import com.jingcaicompass.prediction.mapper.PredictionMapper;
import java.time.Instant;
import java.util.Collection;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 在独立事务内抢占、锁定并审计一条到期预测。 */
@Component
@ConditionalOnBean(DataSource.class)
public class PredictionLockWorker {

    static final String SYSTEM_OPERATOR = "system:prediction-lock-job";

    private final PredictionMapper predictionMapper;
    private final AuditLogService auditLogService;

    public PredictionLockWorker(
            PredictionMapper predictionMapper,
            AuditLogService auditLogService
    ) {
        this.predictionMapper = predictionMapper;
        this.auditLogService = auditLogService;
    }

    /**
     * 抢占下一条到期预测；当前没有可处理记录时返回 null。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public LockResult lockNext(Collection<Long> excludedPredictionIds) {
        // 1) 使用数据库时间和 SKIP LOCKED 抢占一条未被其他 Worker 持有的记录
        PredictionLockCandidateDto candidate =
                predictionMapper.selectNextDueForUpdate(excludedPredictionIds);
        if (candidate == null) {
            return null;
        }

        // 2) 再次使用状态与数据库时间条件完成 PUBLISHED 到 LOCKED 的唯一更新
        int rows;
        try {
            rows = predictionMapper.lockPublishedPrediction(candidate.getPredictionId());
        } catch (RuntimeException exception) {
            throw itemFailure(candidate, "update", exception);
        }
        if (rows != 1) {
            throw itemFailure(
                    candidate,
                    "update",
                    new IllegalStateException("conditional prediction lock updated " + rows + " rows")
            );
        }

        // 3) 在同一事务逐条追加状态变化审计，失败时回滚本条预测
        try {
            auditLogService.append(
                    SYSTEM_OPERATOR,
                    AuditTargetTypeEnum.PREDICTION,
                    String.valueOf(candidate.getPredictionId()),
                    AuditActionTypeEnum.LOCK,
                    "predictionStatus",
                    auditSnapshot("PUBLISHED", candidate.getLockTime()),
                    auditSnapshot("LOCKED", candidate.getLockTime())
            );
        } catch (RuntimeException exception) {
            throw itemFailure(candidate, "audit", exception);
        }

        return new LockResult(
                candidate.getPredictionId(),
                candidate.getLockTime(),
                candidate.getDatabaseTime()
        );
    }

    private PredictionLockItemException itemFailure(
            PredictionLockCandidateDto candidate,
            String stage,
            RuntimeException cause
    ) {
        return new PredictionLockItemException(
                candidate.getPredictionId(),
                stage,
                "prediction lock " + stage + " failed: " + candidate.getPredictionId(),
                cause
        );
    }

    private String auditSnapshot(String status, Instant lockTime) {
        return "status=" + status + ";lockTime=" + lockTime;
    }

    /** 单条预测实际锁定结果。 */
    public record LockResult(
            Long predictionId,
            Instant lockTime,
            Instant lockedAt
    ) {
    }
}
