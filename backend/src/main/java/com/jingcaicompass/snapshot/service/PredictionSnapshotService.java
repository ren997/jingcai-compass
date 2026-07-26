package com.jingcaicompass.snapshot.service;

import com.jingcaicompass.snapshot.dto.PredictionSnapshotResultDto;
import java.time.LocalDate;

/** 公开预测快照的按业务日发布入口。 */
public interface PredictionSnapshotService {

    /**
     * 生成并发布指定竞彩业务日的当前公开预测快照。
     *
     * @param snapshotDate 竞彩业务日
     * @return 新发布、失败或复用的快照结果
     */
    PredictionSnapshotResultDto publish(LocalDate snapshotDate);
}
