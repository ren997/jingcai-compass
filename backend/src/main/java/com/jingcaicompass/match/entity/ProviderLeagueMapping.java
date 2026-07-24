package com.jingcaicompass.match.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jingcaicompass.match.enums.MappingStatusEnum;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Data;

/** 供应商联赛到标准联赛的映射。 */
@Data
@TableName("provider_league_mappings")
public class ProviderLeagueMapping {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 标准联赛 ID */
    private Long leagueId;

    /** Provider 业务编码 */
    private String providerCode;

    /** 供应商侧联赛 ID */
    private String externalLeagueId;

    /**
     * 映射确认状态
     *
     * @see MappingStatusEnum#DESC
     */
    private MappingStatusEnum mappingStatus;

    /** 映射置信度 0～1 */
    private BigDecimal mappingConfidence;

    /** 映射方法说明 */
    private String mappingMethod;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
