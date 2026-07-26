package com.jingcaicompass.admin.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jingcaicompass.admin.enums.AdminAccountStatusEnum;
import com.jingcaicompass.admin.enums.AdminRoleEnum;
import java.time.Instant;
import lombok.Data;

/** 管理员账号、登录锁定状态和 JWT 撤销版本实体。 */
@Data
@TableName("admin_accounts")
public class AdminAccount {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 规范化小写登录名 */
    private String username;

    /** BCrypt 密码哈希，禁止返回或记录 */
    private String passwordHash;

    /**
     * 管理授权角色
     *
     * @see AdminRoleEnum#DESC
     */
    private AdminRoleEnum roleCode;

    /**
     * 账号生命周期状态
     *
     * @see AdminAccountStatusEnum#DESC
     */
    private AdminAccountStatusEnum accountStatus;

    /** 连续登录失败次数 */
    private Integer failedLoginCount;

    /** 临时登录锁定截止时间 */
    private Instant lockedUntil;

    /** JWT 即时撤销版本 */
    private Long tokenVersion;

    /** 最近成功登录时间 */
    private Instant lastLoginAt;

    /** 最近密码哈希更新时间 */
    private Instant passwordUpdatedAt;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
