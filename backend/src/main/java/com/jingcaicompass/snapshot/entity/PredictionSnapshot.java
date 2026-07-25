package com.jingcaicompass.snapshot.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jingcaicompass.snapshot.enums.PredictionSnapshotStatusEnum;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Data;

/** 公开预测快照的发布与存储元数据实体。 */
@Data
@TableName("prediction_snapshots")
public class PredictionSnapshot {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 快照业务日期 */
    private LocalDate snapshotDate;

    /** 同业务日期内的发布版本号 */
    private Integer snapshotVersion;

    /**
     * 快照发布生命周期状态
     *
     * @see PredictionSnapshotStatusEnum#DESC
     */
    private PredictionSnapshotStatusEnum snapshotStatus;

    /** 规范化 manifest 字节 SHA-256 */
    private String snapshotHash;

    /** 快照存储实现编码 */
    private String storageType;

    /** 不可覆盖存储对象键 */
    private String objectKey;

    /** 对象存储版本，本地存储时为空 */
    private String objectVersion;

    /** 已发布快照地址 */
    private String fileUrl;

    /** 存储对象内容类型 */
    private String contentType;

    /** 存储对象字节数 */
    private Long contentLength;

    /** 成功发布时间 */
    private Instant publishedAt;

    /** 最近发布失败摘要 */
    private String failureReason;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
