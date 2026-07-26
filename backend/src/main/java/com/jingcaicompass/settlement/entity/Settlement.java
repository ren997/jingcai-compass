package com.jingcaicompass.settlement.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jingcaicompass.settlement.enums.MarketTypeEnum;
import com.jingcaicompass.settlement.enums.SettlementStatusEnum;
import java.time.Instant;
import lombok.Data;

/** 一条不可变的预测市场结算版本。 */
@Data
@TableName("settlements")
public class Settlement {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 已锁定预测 ID。 */
    private Long predictionId;

    /** 体彩结算市场。 */
    private MarketTypeEnum marketType;

    /** 同一预测市场内递增的结算版本号。 */
    private Integer settlementVersion;

    /** 被当前版本直接替代的上一结算版本；首版为空。 */
    private Integer supersedesSettlementVersion;

    /** 已落库结算结果；PENDING 仅由查询层在无当前结算时派生。 */
    private SettlementStatusEnum settlementStatus;

    /** 结算引用的权威赛果事实 ID。 */
    private Long matchFactId;

    /** 确定性结算器规则版本。 */
    private String ruleVersion;

    /** 是否为当前有效结算；数据库只允许 true 降为 false。 */
    private Boolean isCurrent;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
}
