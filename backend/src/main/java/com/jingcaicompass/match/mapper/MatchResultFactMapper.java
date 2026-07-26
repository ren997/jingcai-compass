package com.jingcaicompass.match.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jingcaicompass.match.entity.MatchResultFact;
import org.apache.ibatis.annotations.Mapper;

/** 版本化官方赛果事实持久化接口。 */
@Mapper
public interface MatchResultFactMapper extends BaseMapper<MatchResultFact> {
}
