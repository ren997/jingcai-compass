package com.jingcaicompass.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jingcaicompass.admin.entity.AdminAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AdminAccountMapper extends BaseMapper<AdminAccount> {

    /** 在登录状态更新事务中锁定单个管理员账号。 */
    @Select("SELECT * FROM admin_accounts WHERE id = #{id} FOR UPDATE")
    AdminAccount selectByIdForUpdate(@Param("id") Long id);
}
