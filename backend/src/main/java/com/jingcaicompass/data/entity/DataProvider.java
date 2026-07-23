package com.jingcaicompass.data.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jingcaicompass.data.enums.DataProviderCategoryEnum;
import java.time.Instant;
import lombok.Data;

/** 外部 Provider 注册实体，不保存明文密钥。 */
@Data
@TableName("data_providers")
public class DataProvider {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** Provider 业务编码 */
    private String providerCode;

    /** Provider 显示名称 */
    private String providerName;

    /** Provider 分类
     * @see DataProviderCategoryEnum#DESC
     */
    private DataProviderCategoryEnum category;

    /** 是否启用 */
    private Boolean enabled;

    /** 不含密钥的基础地址 */
    private String baseUrl;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
