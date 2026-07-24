package com.jingcaicompass.match.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Data;

/** 体彩比赛池与 SP 只追加快照。 */
@Data
@TableName("sporttery_pool_snapshots")
public class SportteryPoolSnapshot {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 内部比赛 ID */
    private Long matchId;

    /** 体彩官方比赛编号 */
    private String lotteryMatchNo;

    /** 竞彩业务日期 */
    private LocalDate lotteryDate;

    /** 官方让球 */
    private BigDecimal officialHandicap;

    /** 胜平负主胜 SP */
    private BigDecimal hadHomeSp;

    /** 胜平负平局 SP */
    private BigDecimal hadDrawSp;

    /** 胜平负客胜 SP */
    private BigDecimal hadAwaySp;

    /** 让球胜平负主胜 SP */
    private BigDecimal hhadHomeSp;

    /** 让球胜平负平局 SP */
    private BigDecimal hhadDrawSp;

    /** 让球胜平负客胜 SP */
    private BigDecimal hhadAwaySp;

    /** 采集时销售状态 */
    private String sellStatus;

    /** 采集时间 */
    private Instant capturedAt;

    /** 供应商侧更新时间 */
    private Instant providerUpdatedAt;

    /** 关联原始载荷 SHA-256 */
    private String rawPayloadHash;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
}
