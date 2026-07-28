package com.jingcaicompass.prediction.service;

import com.jingcaicompass.prediction.dto.PredictionDetailQueryDto;
import com.jingcaicompass.prediction.vo.PredictionDetailVo;
import com.jingcaicompass.prediction.vo.PredictionSnapshotVerificationVo;
import com.jingcaicompass.snapshot.dto.PublicPredictionSnapshotDownloadDto;

/** 面向公开比赛详情的预测版本与已验证快照查询。 */
public interface PublicPredictionQueryService {

    /** 读取单场每个模型的当前公开版本及其替代历史。 */
    PredictionDetailVo detail(PredictionDetailQueryDto query);

    /** 打开已校验的已发布快照，供 Controller 流式下载。 */
    PublicPredictionSnapshotDownloadDto openSnapshot(Long snapshotId);

    /** 校验已发布快照当前存储对象的哈希与长度。 */
    PredictionSnapshotVerificationVo verifySnapshot(Long snapshotId);
}
