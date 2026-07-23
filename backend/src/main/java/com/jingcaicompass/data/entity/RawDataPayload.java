package com.jingcaicompass.data.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.jingcaicompass.data.enums.ParseStatusEnum;
import com.jingcaicompass.data.enums.ProviderDataTypeEnum;
import java.time.Instant;
import java.util.Map;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

/** Provider 原始响应载荷实体。 */
@Data
@TableName(value = "raw_data_payloads", autoResultMap = true)
public class RawDataPayload {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** Provider 业务编码 */
    private String providerCode;

    /** 原始数据类型
     * @see ProviderDataTypeEnum#DESC
     */
    private ProviderDataTypeEnum dataType;

    /** 请求幂等键，例如日期或联赛 ID */
    private String requestKey;

    /** 发起请求时间 */
    private Instant requestedAt;

    /** 供应商侧更新时间 */
    private Instant providerUpdatedAt;

    /** HTTP 状态码 */
    private Integer httpStatus;

    /** 原始 JSON 载荷 */
    @TableField(typeHandler = JacksonTypeHandler.class, jdbcType = JdbcType.OTHER)
    private Map<String, Object> payload;

    /** SHA-256 十六进制摘要 */
    private String payloadHash;

    /** 解析状态
     * @see ParseStatusEnum#DESC
     */
    private ParseStatusEnum parseStatus;

    /** 解析失败原因 */
    private String parseError;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
}
