package com.jingcaicompass.backtest.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jingcaicompass.backtest.entity.BacktestMetricEntity;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface BacktestMetricMapper extends BaseMapper<BacktestMetricEntity> {

    /**
     * 重算前清空该 run 的派生指标。
     *
     * <p>必须 DELETE 而不是 UPDATE：{@code trg_backtest_metrics_no_update} 会拒绝一切 UPDATE，
     * 这条路径是「派生表只能重算」这个约定的唯一实现方式。</p>
     */
    @Delete("DELETE FROM backtest_metrics WHERE run_id = #{runId}")
    int deleteByRunId(@Param("runId") Long runId);

    /** 前端一次查询拿到对比所需全部行，不在前端做加权。 */
    @Select("""
            SELECT *
            FROM backtest_metrics
            WHERE run_id = #{runId}
            ORDER BY group_key,
                     CASE market_scope
                         WHEN 'ALL' THEN 0
                         WHEN 'ASIAN_HANDICAP' THEN 1
                         ELSE 2
                     END
            """)
    List<BacktestMetricEntity> selectByRunId(@Param("runId") Long runId);
}
