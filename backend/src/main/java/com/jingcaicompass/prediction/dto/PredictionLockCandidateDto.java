package com.jingcaicompass.prediction.dto;

import java.time.Instant;
import lombok.Data;

/** 使用数据库时间选出的单条到期预测。 */
@Data
public class PredictionLockCandidateDto {

    /** 预测 ID */
    private Long predictionId;

    /** 计划锁定时间 */
    private Instant lockTime;

    /** 当前 PostgreSQL 事务时间 */
    private Instant databaseTime;
}
