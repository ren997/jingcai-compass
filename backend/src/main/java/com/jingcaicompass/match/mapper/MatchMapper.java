package com.jingcaicompass.match.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jingcaicompass.match.entity.MatchEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MatchMapper extends BaseMapper<MatchEntity> {

    /** 在预测发布等状态事务中锁定单场比赛。 */
    @Select("SELECT * FROM matches WHERE id = #{id} FOR UPDATE")
    MatchEntity selectByIdForUpdate(@Param("id") Long id);
}
