package com.jingcaicompass.match.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Data;

/** 内部比赛实体，以体彩编号与竞彩日期锚定。 */
@Data
@TableName("matches")
public class MatchEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 体彩官方比赛编号 */
    private String lotteryMatchNo;

    /** 竞彩业务日期（Asia/Shanghai） */
    private LocalDate lotteryDate;

    /** 标准联赛 ID，标准化前可为空 */
    private Long leagueId;

    /** 标准主队 ID，标准化前可为空 */
    private Long homeTeamId;

    /** 标准客队 ID，标准化前可为空 */
    private Long awayTeamId;

    /** 联赛展示名 */
    private String leagueName;

    /** 主队展示名 */
    private String homeTeamName;

    /** 客队展示名 */
    private String awayTeamName;

    /** 开赛时间 */
    private Instant kickoffTime;

    /**
     * 比赛状态
     *
     * @see MatchStatusEnum#DESC
     */
    private MatchStatusEnum matchStatus;

    /** 主队比分 */
    private Integer homeScore;

    /** 客队比分 */
    private Integer awayScore;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
