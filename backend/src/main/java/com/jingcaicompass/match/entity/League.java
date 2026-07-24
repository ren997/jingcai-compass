package com.jingcaicompass.match.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Data;

/** 标准联赛字典实体。 */
@Data
@TableName("leagues")
public class League {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 中文显示名 */
    private String nameZh;

    /** 英文显示名 */
    private String nameEn;

    /** 国家或地区编码 */
    private String countryCode;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
