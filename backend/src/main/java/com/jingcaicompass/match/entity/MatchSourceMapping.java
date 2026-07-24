package com.jingcaicompass.match.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.jingcaicompass.match.enums.MappingStatusEnum;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

/** 供应商比赛到内部比赛的映射。 */
@Data
@TableName(value = "match_source_mappings", autoResultMap = true)
public class MatchSourceMapping {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 内部比赛 ID */
    private Long matchId;

    /** Provider 业务编码 */
    private String providerCode;

    /** 供应商侧比赛 ID */
    private String externalMatchId;

    /** 供应商侧联赛 ID */
    private String externalLeagueId;

    /** 供应商侧主队 ID */
    private String externalHomeTeamId;

    /** 供应商侧客队 ID */
    private String externalAwayTeamId;

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

    /** 可读映射解释 */
    private String mappingExplanation;

    /** 打分后的候选列表 */
    @TableField(typeHandler = JacksonTypeHandler.class, jdbcType = JdbcType.OTHER)
    private List<Map<String, Object>> mappingCandidates;

    /** 人工确认人 */
    private String confirmedBy;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
