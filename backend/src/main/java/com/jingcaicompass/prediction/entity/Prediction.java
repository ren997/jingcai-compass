package com.jingcaicompass.prediction.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jingcaicompass.prediction.enums.ConfidenceLevelEnum;
import com.jingcaicompass.prediction.enums.HandicapPickEnum;
import com.jingcaicompass.prediction.enums.PredictionStatusEnum;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Data;

/** 单场比赛的版本化模型预测实体。 */
@Data
@TableName("predictions")
public class Prediction {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 内部比赛 ID */
    private Long matchId;

    /** 可追溯模型版本 */
    private String modelVersion;

    /** 可追溯特征版本 */
    private String featureVersion;

    /** 模型生成批次标识 */
    private String generationBatchId;

    /** 生成输入批次 SHA-256 */
    private String generationBatchHash;

    /** 同比赛模型下的历史版本号 */
    private Integer predictionVersion;

    /** 主胜概率，范围 0～1 */
    private BigDecimal homeWinProb;

    /** 平局概率，范围 0～1 */
    private BigDecimal drawProb;

    /** 客胜概率，范围 0～1 */
    private BigDecimal awayWinProb;

    /**
     * 让球胜平负倾向
     *
     * @see HandicapPickEnum#DESC
     */
    private HandicapPickEnum handicapPick;

    /** 预期总进球 */
    private BigDecimal expectedTotalGoals;

    /**
     * 模型置信等级
     *
     * @see ConfidenceLevelEnum#DESC
     */
    private ConfidenceLevelEnum confidenceLevel;

    /** 面向用户的简短分析摘要 */
    private String analysisSummary;

    /** 模型生成该预测的时间 */
    private Instant generatedAt;

    /**
     * 预测发布生命周期状态
     *
     * @see PredictionStatusEnum#DESC
     */
    private PredictionStatusEnum predictionStatus;

    /** 当前版本首次公开时间 */
    private Instant publishTime;

    /** 当前版本锁定时间 */
    private Instant lockTime;

    /** 规范化发布内容 SHA-256 */
    private String predictionHash;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
