package com.jingcaicompass.prediction.service;

import com.jingcaicompass.audit.enums.AuditActionTypeEnum;
import com.jingcaicompass.audit.enums.AuditTargetTypeEnum;
import com.jingcaicompass.audit.service.AuditLogService;
import com.jingcaicompass.match.entity.MatchEntity;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import com.jingcaicompass.match.mapper.MatchMapper;
import com.jingcaicompass.prediction.dto.PredictionPublishDto;
import com.jingcaicompass.prediction.entity.Prediction;
import com.jingcaicompass.prediction.enums.PredictionStatusEnum;
import com.jingcaicompass.prediction.mapper.PredictionMapper;
import com.jingcaicompass.prediction.vo.PredictionPublishResultVo;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 通过行锁、连续版本和条件更新完成预测发布。 */
@Service
@ConditionalOnBean(DataSource.class)
public class PredictionPublishServiceImpl implements PredictionPublishService {

    private static final Set<MatchStatusEnum> PUBLISHABLE_MATCH_STATUSES =
            Set.of(MatchStatusEnum.SCHEDULED, MatchStatusEnum.LOCKED);

    private final PredictionMapper predictionMapper;
    private final MatchMapper matchMapper;
    private final PredictionContentHasher contentHasher;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public PredictionPublishServiceImpl(
            PredictionMapper predictionMapper,
            MatchMapper matchMapper,
            PredictionContentHasher contentHasher,
            AuditLogService auditLogService,
            Clock clock
    ) {
        this.predictionMapper = predictionMapper;
        this.matchMapper = matchMapper;
        this.contentHasher = contentHasher;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    @Override
    @Transactional
    public PredictionPublishResultVo publish(
            PredictionPublishDto request,
            String operatorUsername
    ) {
        // 1) 校验调用契约并读取目标所属比赛，尚不执行写操作
        Long predictionId = requirePredictionId(request);
        String operator = requireOperator(operatorUsername);
        Prediction located = predictionMapper.selectById(predictionId);
        if (located == null) {
            throw conflict("prediction not found: " + predictionId);
        }

        // 2) 固定按比赛行、预测行顺序加锁，串行化同场并发发布
        MatchEntity match = matchMapper.selectByIdForUpdate(located.getMatchId());
        if (match == null) {
            throw conflict("prediction match not found: " + located.getMatchId());
        }
        Prediction prediction = predictionMapper.selectByIdForUpdate(predictionId);
        if (prediction == null || !Objects.equals(prediction.getMatchId(), match.getId())) {
            throw conflict("prediction changed during publish: " + predictionId);
        }

        // 3) 已发布或已锁定记录按原结果复用，不重复更新和审计
        if (prediction.getPredictionStatus() == PredictionStatusEnum.PUBLISHED
                || prediction.getPredictionStatus() == PredictionStatusEnum.LOCKED) {
            return toResult(prediction, true);
        }
        if (prediction.getPredictionStatus() != PredictionStatusEnum.DRAFT) {
            throw conflict("prediction status does not allow publish: "
                    + prediction.getPredictionStatus());
        }

        // 4) 仅允许尚未开赛的可发布比赛，锁定时间固定为开赛时间
        Instant publishTime = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant lockTime = match.getKickoffTime() == null
                ? null
                : match.getKickoffTime().truncatedTo(ChronoUnit.MICROS);
        if (!PUBLISHABLE_MATCH_STATUSES.contains(match.getMatchStatus())) {
            throw conflict("match status does not allow prediction publish: "
                    + match.getMatchStatus());
        }
        if (lockTime == null || !publishTime.isBefore(lockTime)) {
            throw conflict("prediction publish deadline has passed: " + predictionId);
        }

        // 5) 同比赛模型首次只允许 V1，之后必须严格发布下一版本
        Integer currentVersion = predictionMapper.selectLatestPublishedVersion(
                prediction.getMatchId(),
                prediction.getModelVersion()
        );
        int expectedVersion = nextExpectedVersion(currentVersion, predictionId);
        if (!Objects.equals(prediction.getPredictionVersion(), expectedVersion)) {
            throw conflict("prediction publish version conflict: expected "
                    + expectedVersion + " but was " + prediction.getPredictionVersion());
        }

        // 6) 对规范化发布内容计算稳定哈希
        String predictionHash;
        try {
            predictionHash = contentHasher.sha256Hex(prediction, publishTime, lockTime);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_ERROR,
                    "prediction draft content is invalid: " + predictionId,
                    exception
            );
        }

        // 7) 以 DRAFT 和空发布字段为前置条件完成唯一状态更新
        int rows = predictionMapper.publishDraft(
                predictionId,
                publishTime,
                lockTime,
                predictionHash
        );
        if (rows != 1) {
            throw conflict("prediction publish conflict: " + predictionId);
        }
        String oldAuditSnapshot = auditSnapshot(
                PredictionStatusEnum.DRAFT,
                prediction.getPredictionVersion(),
                null,
                null,
                null
        );
        prediction.setPredictionStatus(PredictionStatusEnum.PUBLISHED);
        prediction.setPublishTime(publishTime);
        prediction.setLockTime(lockTime);
        prediction.setPredictionHash(predictionHash);

        // 8) 同一事务追加发布审计，操作者只使用已认证 JWT 身份
        auditLogService.append(
                operator,
                AuditTargetTypeEnum.PREDICTION,
                String.valueOf(predictionId),
                AuditActionTypeEnum.PUBLISH,
                "predictionStatus",
                oldAuditSnapshot,
                auditSnapshot(
                        PredictionStatusEnum.PUBLISHED,
                        prediction.getPredictionVersion(),
                        publishTime,
                        lockTime,
                        predictionHash
                )
        );
        return toResult(prediction, false);
    }

    private Long requirePredictionId(PredictionPublishDto request) {
        if (request == null || request.predictionId() == null || request.predictionId() <= 0) {
            throw new BusinessException(
                    ErrorCode.INVALID_PARAMETER,
                    "predictionId must be positive"
            );
        }
        return request.predictionId();
    }

    private String requireOperator(String operatorUsername) {
        if (!StringUtils.hasText(operatorUsername)) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
        }
        return operatorUsername.trim();
    }

    private int nextExpectedVersion(Integer currentVersion, Long predictionId) {
        if (currentVersion == null) {
            return 1;
        }
        if (currentVersion == Integer.MAX_VALUE) {
            throw conflict("prediction version exhausted: " + predictionId);
        }
        return currentVersion + 1;
    }

    private PredictionPublishResultVo toResult(Prediction prediction, boolean reused) {
        return new PredictionPublishResultVo(
                prediction.getId(),
                prediction.getMatchId(),
                prediction.getModelVersion(),
                prediction.getPredictionVersion(),
                prediction.getPredictionStatus(),
                prediction.getPublishTime(),
                prediction.getLockTime(),
                prediction.getPredictionHash(),
                reused
        );
    }

    private String auditSnapshot(
            PredictionStatusEnum status,
            Integer predictionVersion,
            Instant publishTime,
            Instant lockTime,
            String hash
    ) {
        return "status=" + status.getCode()
                + ";version=" + predictionVersion
                + ";publishTime=" + publishTime
                + ";lockTime=" + lockTime
                + ";hash=" + hash;
    }

    private BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.BUSINESS_ERROR, message);
    }
}
