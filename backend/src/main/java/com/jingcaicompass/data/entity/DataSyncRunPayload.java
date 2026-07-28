package com.jingcaicompass.data.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Data;

/** 同步运行与原始载荷的精确关联，允许同一去重载荷服务多次运行。 */
@Data
@TableName("data_sync_run_payloads")
public class DataSyncRunPayload {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 同步运行 ID */
    private Long syncRunId;

    /** 原始载荷 ID */
    private Long rawDataPayloadId;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
}
