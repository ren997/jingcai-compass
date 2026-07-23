package com.jingcaicompass.data.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jingcaicompass.data.enums.ProviderDataTypeEnum;
import com.jingcaicompass.data.enums.SyncStatusEnum;
import java.time.Instant;
import lombok.Data;

/** Provider 同步运行记录实体。 */
@Data
@TableName("data_sync_runs")
public class DataSyncRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** Provider 业务编码 */
    private String providerCode;

    /** 同步数据类型
     * @see ProviderDataTypeEnum#DESC
     */
    private ProviderDataTypeEnum dataType;

    /** 同步状态
     * @see SyncStatusEnum#DESC
     */
    private SyncStatusEnum syncStatus;

    /** 开始时间 */
    private Instant startedAt;

    /** 结束时间 */
    private Instant finishedAt;

    /** 拉取数量 */
    private Integer fetchedCount;

    /** 成功数量 */
    private Integer successCount;

    /** 失败数量 */
    private Integer failureCount;

    /** 重试次数 */
    private Integer retryCount;

    /** 本轮额度消耗 */
    private Integer quotaCost;

    /** 错误摘要 */
    private String errorMessage;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
