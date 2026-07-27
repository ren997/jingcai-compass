package com.jingcaicompass.history.mapper;

import com.jingcaicompass.history.dto.HistoryQueryCriteriaDto;
import com.jingcaicompass.statistics.dto.StatisticsQueryCriteriaDto;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 公开历史与统计共用的预测 ID 筛选 SQL。 */
@Mapper
public interface HistoryQueryMapper {

    @Select("""
            <script>
            SELECT p.id
            FROM predictions p
            INNER JOIN matches m ON m.id = p.match_id
            WHERE p.prediction_status IN ('PUBLISHED', 'LOCKED')
            <if test="criteria.lockedOnly">
              AND p.prediction_status = 'LOCKED'
            </if>
            <if test="criteria.startDate != null">
              AND m.lottery_date &gt;= #{criteria.startDate}
            </if>
            <if test="criteria.endDate != null">
              AND m.lottery_date &lt;= #{criteria.endDate}
            </if>
            <if test="criteria.leagueId != null">
              AND m.league_id = #{criteria.leagueId}
            </if>
            <if test="criteria.modelVersion != null">
              AND p.model_version = #{criteria.modelVersion}
            </if>
            <if test="criteria.hasSettlementStatusFilter">
              AND (
                <if test="criteria.pendingStatusRequested">
                  NOT EXISTS (
                    SELECT 1 FROM settlements pending_settlement
                    WHERE pending_settlement.prediction_id = p.id
                      AND pending_settlement.market_type = #{criteria.settlementMarket}
                      AND pending_settlement.is_current = TRUE
                  )
                </if>
                <if test="criteria.pendingStatusRequested and criteria.persistedSettlementStatuses != null and !criteria.persistedSettlementStatuses.isEmpty()">
                  OR
                </if>
                <if test="criteria.persistedSettlementStatuses != null and !criteria.persistedSettlementStatuses.isEmpty()">
                  EXISTS (
                    SELECT 1 FROM settlements current_settlement
                    WHERE current_settlement.prediction_id = p.id
                      AND current_settlement.market_type = #{criteria.settlementMarket}
                      AND current_settlement.is_current = TRUE
                      AND current_settlement.settlement_status IN
                      <foreach collection="criteria.persistedSettlementStatuses" item="status" open="(" separator="," close=")">
                        #{status}
                      </foreach>
                  )
                </if>
              )
            </if>
            ORDER BY m.lottery_date DESC, m.kickoff_time DESC, p.publish_time DESC, p.id DESC
            LIMIT #{criteria.pageSize} OFFSET #{criteria.offset}
            </script>
            """)
    List<Long> selectPagePredictionIds(@Param("criteria") HistoryQueryCriteriaDto criteria);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM predictions p
            INNER JOIN matches m ON m.id = p.match_id
            WHERE p.prediction_status IN ('PUBLISHED', 'LOCKED')
            <if test="criteria.lockedOnly">
              AND p.prediction_status = 'LOCKED'
            </if>
            <if test="criteria.startDate != null">
              AND m.lottery_date &gt;= #{criteria.startDate}
            </if>
            <if test="criteria.endDate != null">
              AND m.lottery_date &lt;= #{criteria.endDate}
            </if>
            <if test="criteria.leagueId != null">
              AND m.league_id = #{criteria.leagueId}
            </if>
            <if test="criteria.modelVersion != null">
              AND p.model_version = #{criteria.modelVersion}
            </if>
            <if test="criteria.hasSettlementStatusFilter">
              AND (
                <if test="criteria.pendingStatusRequested">
                  NOT EXISTS (
                    SELECT 1 FROM settlements pending_settlement
                    WHERE pending_settlement.prediction_id = p.id
                      AND pending_settlement.market_type = #{criteria.settlementMarket}
                      AND pending_settlement.is_current = TRUE
                  )
                </if>
                <if test="criteria.pendingStatusRequested and criteria.persistedSettlementStatuses != null and !criteria.persistedSettlementStatuses.isEmpty()">
                  OR
                </if>
                <if test="criteria.persistedSettlementStatuses != null and !criteria.persistedSettlementStatuses.isEmpty()">
                  EXISTS (
                    SELECT 1 FROM settlements current_settlement
                    WHERE current_settlement.prediction_id = p.id
                      AND current_settlement.market_type = #{criteria.settlementMarket}
                      AND current_settlement.is_current = TRUE
                      AND current_settlement.settlement_status IN
                      <foreach collection="criteria.persistedSettlementStatuses" item="status" open="(" separator="," close=")">
                        #{status}
                      </foreach>
                  )
                </if>
              )
            </if>
            </script>
            """)
    long countPredictionIds(@Param("criteria") HistoryQueryCriteriaDto criteria);

    @Select("""
            <script>
            SELECT p.id
            FROM predictions p
            INNER JOIN matches m ON m.id = p.match_id
            WHERE p.prediction_status = 'LOCKED'
            <if test="criteria.startDate != null">
              AND m.lottery_date &gt;= #{criteria.startDate}
            </if>
            <if test="criteria.endDate != null">
              AND m.lottery_date &lt;= #{criteria.endDate}
            </if>
            <if test="criteria.leagueId != null">
              AND m.league_id = #{criteria.leagueId}
            </if>
            <if test="criteria.modelVersion != null">
              AND p.model_version = #{criteria.modelVersion}
            </if>
            ORDER BY p.id
            </script>
            """)
    List<Long> selectLockedPredictionIds(@Param("criteria") StatisticsQueryCriteriaDto criteria);
}
