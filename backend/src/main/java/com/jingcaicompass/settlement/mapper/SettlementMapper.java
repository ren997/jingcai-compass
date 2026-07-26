package com.jingcaicompass.settlement.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jingcaicompass.settlement.entity.Settlement;
import org.apache.ibatis.annotations.Mapper;

/** 版本化结算结果持久化接口。 */
@Mapper
public interface SettlementMapper extends BaseMapper<Settlement> {
}
