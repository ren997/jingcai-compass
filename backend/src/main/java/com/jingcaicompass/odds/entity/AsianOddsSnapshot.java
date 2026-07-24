package com.jingcaicompass.odds.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jingcaicompass.odds.enums.OddsSnapshotTypeEnum;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Data;

/** 亚盘让球与大小球同存的只追加快照。 */
@Data
@TableName("asian_odds_snapshots")
public class AsianOddsSnapshot {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 内部比赛 ID */
    private Long matchId;

    /** Provider 业务编码 */
    private String providerCode;

    /** 博彩公司或盘口来源编码 */
    private String bookmakerCode;

    /** 亚洲让球盘口 */
    private BigDecimal handicapLine;

    /** 主队水位 */
    private BigDecimal homeOdds;

    /** 客队水位 */
    private BigDecimal awayOdds;

    /** 大小球盘口线；与 over/under 要么全空要么全有 */
    private BigDecimal totalLine;

    /** 大球水位 */
    private BigDecimal overOdds;

    /** 小球水位 */
    private BigDecimal underOdds;

    /**
     * 快照类型
     *
     * @see OddsSnapshotTypeEnum#DESC
     */
    private OddsSnapshotTypeEnum snapshotType;

    /** 采集时间 */
    private Instant capturedAt;

    /** 供应商侧更新时间 */
    private Instant providerUpdatedAt;

    /** 关联原始载荷 SHA-256 */
    private String rawPayloadHash;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
}
