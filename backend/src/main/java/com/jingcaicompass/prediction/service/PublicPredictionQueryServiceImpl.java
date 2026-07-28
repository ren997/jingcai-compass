package com.jingcaicompass.prediction.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.match.entity.MatchEntity;
import com.jingcaicompass.match.mapper.MatchMapper;
import com.jingcaicompass.prediction.dto.PredictionDetailQueryDto;
import com.jingcaicompass.prediction.entity.Prediction;
import com.jingcaicompass.prediction.mapper.PredictionMapper;
import com.jingcaicompass.prediction.vo.PredictionDetailVo;
import com.jingcaicompass.prediction.vo.PredictionModelDetailVo;
import com.jingcaicompass.prediction.vo.PredictionSnapshotVerificationVo;
import com.jingcaicompass.prediction.vo.PredictionSnapshotVo;
import com.jingcaicompass.prediction.vo.PredictionVersionVo;
import com.jingcaicompass.snapshot.dto.PredictionSnapshotManifestDto;
import com.jingcaicompass.snapshot.dto.PredictionSnapshotManifestItemDto;
import com.jingcaicompass.snapshot.dto.PublicPredictionSnapshotDownloadDto;
import com.jingcaicompass.snapshot.entity.PredictionSnapshot;
import com.jingcaicompass.snapshot.enums.PublicSnapshotAvailabilityEnum;
import com.jingcaicompass.snapshot.mapper.PredictionSnapshotMapper;
import com.jingcaicompass.snapshot.storage.SnapshotStorage;
import com.jingcaicompass.snapshot.storage.SnapshotStorageException;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

/** 查询公开预测链，并仅关联经过 manifest 和存储双重校验的快照。 */
@Service
@ConditionalOnBean(DataSource.class)
public class PublicPredictionQueryServiceImpl implements PublicPredictionQueryService {

    private final MatchMapper matchMapper;
    private final PredictionMapper predictionMapper;
    private final PredictionSnapshotMapper snapshotMapper;
    private final SnapshotStorage snapshotStorage;
    private final ObjectMapper objectMapper;

    public PublicPredictionQueryServiceImpl(
            MatchMapper matchMapper,
            PredictionMapper predictionMapper,
            PredictionSnapshotMapper snapshotMapper,
            SnapshotStorage snapshotStorage,
            ObjectMapper objectMapper
    ) {
        this.matchMapper = matchMapper;
        this.predictionMapper = predictionMapper;
        this.snapshotMapper = snapshotMapper;
        this.snapshotStorage = snapshotStorage;
        this.objectMapper = objectMapper;
    }

    @Override
    public PredictionDetailVo detail(PredictionDetailQueryDto query) {
        // 1) 确认比赛存在，再查询仅限 PUBLISHED/LOCKED 的版本链。
        Long matchId = requireMatchId(query);
        MatchEntity match = matchMapper.selectById(matchId);
        if (match == null) {
            throw new BusinessException(ErrorCode.MATCH_NOT_FOUND);
        }
        List<Prediction> publicPredictions = predictionMapper.selectPublicByMatchId(matchId);
        if (publicPredictions.isEmpty()) {
            return new PredictionDetailVo(matchId, List.of());
        }

        // 2) 每个模型最后一个公开版本为当前版本，按精确 ID 和哈希查询可关联快照。
        Map<String, List<Prediction>> predictionsByModel = groupByModel(publicPredictions);
        Set<PredictionIdentity> currentIdentities = currentIdentities(predictionsByModel);
        Map<PredictionIdentity, PredictionSnapshotVo> snapshots = findVerifiedSnapshots(
                match.getLotteryDate(),
                currentIdentities
        );

        // 3) 保留公开版本替代链，并且只给当前版本返回已验证快照信息。
        List<PredictionModelDetailVo> models = new ArrayList<>();
        for (Map.Entry<String, List<Prediction>> entry : predictionsByModel.entrySet()) {
            List<Prediction> versions = entry.getValue();
            Prediction current = versions.getLast();
            Long currentReplacesId = versions.size() > 1
                    ? versions.get(versions.size() - 2).getId()
                    : null;
            PredictionSnapshotVo snapshot = snapshots.get(identityOf(current));
            List<PredictionVersionVo> history = new ArrayList<>();
            for (int index = 0; index < versions.size() - 1; index++) {
                Prediction version = versions.get(index);
                Long replacesId = index == 0 ? null : versions.get(index - 1).getId();
                history.add(toVersionVo(
                        version,
                        replacesId,
                        PublicSnapshotAvailabilityEnum.UNAVAILABLE,
                        null
                ));
            }
            models.add(new PredictionModelDetailVo(
                    entry.getKey(),
                    toVersionVo(
                            current,
                            currentReplacesId,
                            snapshot == null
                                    ? PublicSnapshotAvailabilityEnum.UNAVAILABLE
                                    : PublicSnapshotAvailabilityEnum.AVAILABLE,
                            snapshot
                    ),
                    history
            ));
        }
        return new PredictionDetailVo(matchId, models);
    }

    @Override
    public PublicPredictionSnapshotDownloadDto openSnapshot(Long snapshotId) {
        // 1) 只允许已成功发布且元数据完整的快照进入公开下载。
        PredictionSnapshot snapshot = requirePublishedSnapshot(snapshotId);
        if (!isReadable(snapshot)) {
            throw new BusinessException(ErrorCode.PREDICTION_SNAPSHOT_UNAVAILABLE);
        }

        // 2) 打开经过校验的不可覆盖对象，流由 Controller 在响应结束后关闭。
        try {
            return new PublicPredictionSnapshotDownloadDto(
                    snapshot.getId(),
                    snapshot.getSnapshotDate(),
                    snapshot.getSnapshotVersion(),
                    snapshot.getContentType(),
                    snapshot.getContentLength(),
                    snapshotStorage.open(snapshot.getObjectKey())
            );
        } catch (SnapshotStorageException exception) {
            throw new BusinessException(
                    ErrorCode.PREDICTION_SNAPSHOT_UNAVAILABLE,
                    "published prediction snapshot is unavailable",
                    exception
            );
        }
    }

    @Override
    public PredictionSnapshotVerificationVo verifySnapshot(Long snapshotId) {
        PredictionSnapshot snapshot = requirePublishedSnapshot(snapshotId);
        return new PredictionSnapshotVerificationVo(
                snapshot.getId(),
                snapshot.getSnapshotHash(),
                snapshot.getContentLength(),
                isReadable(snapshot)
        );
    }

    private Map<String, List<Prediction>> groupByModel(List<Prediction> predictions) {
        Map<String, List<Prediction>> grouped = new LinkedHashMap<>();
        for (Prediction prediction : predictions) {
            grouped.computeIfAbsent(prediction.getModelVersion(), ignored -> new ArrayList<>())
                    .add(prediction);
        }
        return grouped;
    }

    private Set<PredictionIdentity> currentIdentities(Map<String, List<Prediction>> byModel) {
        Set<PredictionIdentity> identities = new LinkedHashSet<>();
        byModel.values().forEach(versions -> identities.add(identityOf(versions.getLast())));
        return identities;
    }

    private Map<PredictionIdentity, PredictionSnapshotVo> findVerifiedSnapshots(
            LocalDate snapshotDate,
            Set<PredictionIdentity> currentIdentities
    ) {
        Map<PredictionIdentity, PredictionSnapshotVo> result = new LinkedHashMap<>();
        for (PredictionSnapshot snapshot : snapshotMapper.selectPublishedByDate(snapshotDate)) {
            if (result.size() == currentIdentities.size() || !isReadable(snapshot)) {
                continue;
            }
            try (InputStream content = snapshotStorage.open(snapshot.getObjectKey())) {
                PredictionSnapshotManifestDto manifest = objectMapper.readValue(
                        content,
                        PredictionSnapshotManifestDto.class
                );
                if (!snapshotDate.toString().equals(manifest.snapshotDate())) {
                    continue;
                }
                for (PredictionSnapshotManifestItemDto item : manifest.predictions()) {
                    PredictionIdentity identity = new PredictionIdentity(
                            item.predictionId(),
                            item.predictionHash()
                    );
                    if (currentIdentities.contains(identity)) {
                        result.putIfAbsent(identity, toSnapshotVo(snapshot));
                    }
                }
            } catch (IOException | SnapshotStorageException ignored) {
                // 无法读取或解析的已发布记录不应作为公开快照关联展示。
            }
        }
        return result;
    }

    private PredictionSnapshot requirePublishedSnapshot(Long snapshotId) {
        if (snapshotId == null || snapshotId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "snapshotId must be positive");
        }
        PredictionSnapshot snapshot = snapshotMapper.selectPublishedById(snapshotId);
        if (snapshot == null) {
            throw new BusinessException(ErrorCode.PREDICTION_SNAPSHOT_NOT_FOUND);
        }
        return snapshot;
    }

    private boolean isReadable(PredictionSnapshot snapshot) {
        if (snapshot.getStorageType() == null
                || !snapshot.getStorageType().equals(snapshotStorage.storageType().getCode())
                || snapshot.getObjectKey() == null
                || snapshot.getSnapshotHash() == null
                || snapshot.getContentLength() == null) {
            return false;
        }
        try {
            return snapshotStorage.verify(
                    snapshot.getObjectKey(),
                    snapshot.getSnapshotHash(),
                    snapshot.getContentLength()
            );
        } catch (SnapshotStorageException exception) {
            return false;
        }
    }

    private Long requireMatchId(PredictionDetailQueryDto query) {
        if (query == null || query.matchId() == null || query.matchId() <= 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "matchId must be positive");
        }
        return query.matchId();
    }

    private PredictionVersionVo toVersionVo(
            Prediction prediction,
            Long replacesPredictionId,
            PublicSnapshotAvailabilityEnum snapshotAvailability,
            PredictionSnapshotVo snapshot
    ) {
        return new PredictionVersionVo(
                prediction.getId(),
                prediction.getPredictionVersion(),
                replacesPredictionId,
                prediction.getPredictionStatus(),
                prediction.getFeatureVersion(),
                prediction.getHomeWinProb(),
                prediction.getDrawProb(),
                prediction.getAwayWinProb(),
                prediction.getHandicapPick(),
                prediction.getExpectedTotalGoals(),
                prediction.getConfidenceLevel(),
                prediction.getAnalysisSummary(),
                prediction.getGeneratedAt(),
                prediction.getPublishTime(),
                prediction.getLockTime(),
                prediction.getPredictionHash(),
                snapshotAvailability,
                snapshot
        );
    }

    private PredictionSnapshotVo toSnapshotVo(PredictionSnapshot snapshot) {
        return new PredictionSnapshotVo(
                snapshot.getId(),
                snapshot.getSnapshotDate(),
                snapshot.getSnapshotVersion(),
                snapshot.getSnapshotHash(),
                snapshot.getContentType(),
                snapshot.getContentLength(),
                snapshot.getPublishedAt()
        );
    }

    private PredictionIdentity identityOf(Prediction prediction) {
        return new PredictionIdentity(prediction.getId(), prediction.getPredictionHash());
    }

    private record PredictionIdentity(Long predictionId, String predictionHash) {
    }
}
