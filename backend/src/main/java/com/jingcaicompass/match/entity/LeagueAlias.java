package com.jingcaicompass.match.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Data;

/** 联赛人工确认别名。 */
@Data
@TableName("league_aliases")
public class LeagueAlias {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 标准联赛 ID */
    private Long leagueId;

    /** 原始别名展示文本 */
    private String aliasRaw;

    /** 规范化后的别名 key */
    private String aliasNormalized;

    /** 别名来源说明 */
    private String source;

    /** 确认人 */
    private String confirmedBy;

    /** 确认时间 */
    private Instant confirmedAt;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
}
