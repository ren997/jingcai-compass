package com.jingcaicompass.backtest.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jingcaicompass.backtest.entity.BacktestMetricEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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

    /**
     * 前端一次查询拿到对比所需全部行，不在前端做加权。
     *
     * <p><b>不要改回 {@code @Select}</b>。{@code @Select} 走的是 MyBatis 原生结果映射，
     * <b>不会</b>应用实体上的 {@code @TableField(typeHandler = PostgresJsonbTypeHandler.class)}：
     * {@code @TableName(autoResultMap = true)} 生成的那份 resultMap 只被 MyBatis-Plus 自己
     * 拼的 SQL 使用。改回去的结果是 {@code skip_reason_counts} 与 {@code metrics_json}
     * 两列读回 {@code null}，<b>而且不报任何错</b>——写库时明明是有的，直到前端
     * {@code Object.keys(null)} 把整页炸掉才会发现。payload 字段已经踩过同一个坑。</p>
     *
     * <p>排序原本写在 SQL 里（{@code group_key} 升序 + {@code ALL → 让球 → 大小球}）。
     * 行数上限是「参数组数 × 3」（最多 8 × 3 = 24 行），挪到内存里排更省事，
     * 也避免那段 {@code CASE} 表达式改动时两边不同步。</p>
     */
    default List<BacktestMetricEntity> selectByRunId(Long runId) {
        List<BacktestMetricEntity> rows = new ArrayList<>(selectList(
                Wrappers.<BacktestMetricEntity>lambdaQuery()
                        .eq(BacktestMetricEntity::getRunId, runId)));
        rows.sort(Comparator
                .comparing(BacktestMetricEntity::getGroupKey,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingInt(row -> scopeOrder(row.getMarketScope())));
        return rows;
    }

    /** 与 SQL 里那段 {@code CASE market_scope} 等价；未知值时排到最后而不是报错。 */
    private static int scopeOrder(String marketScope) {
        if (BacktestMetricEntity.SCOPE_ALL.equals(marketScope)) {
            return 0;
        }
        if (BacktestMetricEntity.SCOPE_HANDICAP.equals(marketScope)) {
            return 1;
        }
        return 2;
    }
}
