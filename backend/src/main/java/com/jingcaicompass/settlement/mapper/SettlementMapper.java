package com.jingcaicompass.settlement.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jingcaicompass.settlement.entity.Settlement;
import com.jingcaicompass.settlement.enums.MarketTypeEnum;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 版本化结算结果持久化接口。 */
@Mapper
public interface SettlementMapper extends BaseMapper<Settlement> {

    /** 查询一条预测市场的当前有效结算。 */
    @Select("""
            SELECT * FROM settlements
            WHERE prediction_id = #{predictionId}
              AND market_type = #{marketType}
              AND is_current = TRUE
            LIMIT 1
            """)
    Settlement selectCurrentByPredictionIdAndMarket(
            @Param("predictionId") Long predictionId,
            @Param("marketType") MarketTypeEnum marketType
    );

    /** 查询已锁定、拥有当前确认事实且至少一个市场未结算的预测 ID。 */
    @Select("""
            SELECT prediction.id
            FROM predictions prediction
            INNER JOIN match_result_facts fact
                ON fact.match_id = prediction.match_id
               AND fact.is_current = TRUE
            WHERE prediction.prediction_status = 'LOCKED'
              AND fact.fact_status IN ('FINAL', 'VOID')
              AND (
                    NOT EXISTS (
                        SELECT 1 FROM settlements settlement
                        WHERE settlement.prediction_id = prediction.id
                          AND settlement.market_type = 'HAD'
                          AND settlement.is_current = TRUE
                    )
                    OR NOT EXISTS (
                        SELECT 1 FROM settlements settlement
                        WHERE settlement.prediction_id = prediction.id
                          AND settlement.market_type = 'HHAD'
                          AND settlement.is_current = TRUE
                    )
              )
            ORDER BY prediction.id
            LIMIT #{batchSize}
            """)
    List<Long> selectPendingLockedPredictionIds(@Param("batchSize") int batchSize);

    /** 查询已锁定、当前结算仍引用被替代官方事实的预测 ID。 */
    @Select("""
            SELECT DISTINCT prediction.id
            FROM predictions prediction
            INNER JOIN match_result_facts fact
                ON fact.match_id = prediction.match_id
               AND fact.is_current = TRUE
            INNER JOIN settlements settlement
                ON settlement.prediction_id = prediction.id
               AND settlement.is_current = TRUE
               AND settlement.match_fact_id <> fact.id
            WHERE prediction.prediction_status = 'LOCKED'
              AND fact.fact_status IN ('FINAL', 'VOID')
            ORDER BY prediction.id
            LIMIT #{batchSize}
            """)
    List<Long> selectOutdatedLockedPredictionIds(@Param("batchSize") int batchSize);

    /** 唯一允许的结算更新：将 current 标记降为 false。 */
    @Update("""
            UPDATE settlements
            SET is_current = FALSE
            WHERE id = #{settlementId}
              AND is_current = TRUE
            """)
    int markNotCurrent(@Param("settlementId") Long settlementId);

    /** 按预测批量读取全部市场结算版本，供公开历史重建。 */
    @Select("""
            <script>
            SELECT * FROM settlements
            WHERE prediction_id IN
            <foreach collection="predictionIds" item="predictionId" open="(" separator="," close=")">
                #{predictionId}
            </foreach>
            ORDER BY prediction_id, market_type, settlement_version
            </script>
            """)
    List<Settlement> selectHistoryByPredictionIds(@Param("predictionIds") Collection<Long> predictionIds);
}
