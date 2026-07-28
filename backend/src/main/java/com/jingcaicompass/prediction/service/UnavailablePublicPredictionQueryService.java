package com.jingcaicompass.prediction.service;

import com.jingcaicompass.prediction.dto.PredictionDetailQueryDto;
import com.jingcaicompass.prediction.vo.PredictionDetailVo;
import com.jingcaicompass.prediction.vo.PredictionSnapshotVerificationVo;
import com.jingcaicompass.snapshot.dto.PublicPredictionSnapshotDownloadDto;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;

/** 无数据库配置下保留统一错误语义的公开预测查询占位。 */
public class UnavailablePublicPredictionQueryService implements PublicPredictionQueryService {

    @Override
    public PredictionDetailVo detail(PredictionDetailQueryDto query) {
        throw unavailable();
    }

    @Override
    public PublicPredictionSnapshotDownloadDto openSnapshot(Long snapshotId) {
        throw unavailable();
    }

    @Override
    public PredictionSnapshotVerificationVo verifySnapshot(Long snapshotId) {
        throw unavailable();
    }

    private BusinessException unavailable() {
        return new BusinessException(
                ErrorCode.DATA_SOURCE_UNAVAILABLE,
                "public prediction query requires a database"
        );
    }
}
