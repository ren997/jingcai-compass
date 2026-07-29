package com.jingcaicompass.admin.mapper;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 管理员预测锁定和结算状态的分页 ID 查询，不读取外部数据源。 */
@Mapper
public interface AdminPredictionStatusMapper {

    /** 从 PostgreSQL 取得本次锁定诊断使用的统一时间。 */
    @Select("SELECT CURRENT_TIMESTAMP")
    Instant selectDatabaseTime();

    /** 读取已发布或已锁定预测的稳定分页顺序。 */
    @Select("""
            <script>
            SELECT p.id
            FROM predictions p
            INNER JOIN matches m ON m.id = p.match_id
            WHERE p.prediction_status IN
            <foreach collection="criteria.predictionStatuses" item="status" open="(" separator="," close=")">
                #{status}
            </foreach>
            <if test="criteria.lotteryDate != null">AND m.lottery_date = #{criteria.lotteryDate}</if>
            <if test="criteria.modelVersion != null and criteria.modelVersion != ''">AND p.model_version = #{criteria.modelVersion}</if>
            <if test="criteria.lockDiagnostics != null and !criteria.lockDiagnostics.isEmpty()">
              AND (
              <foreach collection="criteria.lockDiagnostics" item="diagnostic" separator=" OR ">
                <choose>
                  <when test="diagnostic.name() == 'OVERDUE'">(p.prediction_status = 'PUBLISHED' AND p.lock_time &lt;= #{criteria.referenceTime})</when>
                  <when test="diagnostic.name() == 'SCHEDULED'">(p.prediction_status = 'PUBLISHED' AND p.lock_time &gt; #{criteria.referenceTime})</when>
                  <otherwise>p.prediction_status = 'LOCKED'</otherwise>
                </choose>
              </foreach>
              )
            </if>
            ORDER BY CASE WHEN p.prediction_status = 'PUBLISHED' AND p.lock_time &lt;= #{criteria.referenceTime} THEN 0
                          WHEN p.prediction_status = 'PUBLISHED' THEN 1 ELSE 2 END,
                     p.lock_time ASC NULLS LAST, p.id ASC
            LIMIT #{criteria.pageSize} OFFSET #{criteria.offset}
            </script>
            """)
    List<Long> selectLockPredictionIds(@Param("criteria") AdminPredictionStatusCriteria criteria);

    /** 统计锁定筛选命中的预测。 */
    @Select("""
            <script>
            SELECT COUNT(*)
            FROM predictions p
            INNER JOIN matches m ON m.id = p.match_id
            WHERE p.prediction_status IN
            <foreach collection="criteria.predictionStatuses" item="status" open="(" separator="," close=")">
                #{status}
            </foreach>
            <if test="criteria.lotteryDate != null">AND m.lottery_date = #{criteria.lotteryDate}</if>
            <if test="criteria.modelVersion != null and criteria.modelVersion != ''">AND p.model_version = #{criteria.modelVersion}</if>
            <if test="criteria.lockDiagnostics != null and !criteria.lockDiagnostics.isEmpty()">
              AND (
              <foreach collection="criteria.lockDiagnostics" item="diagnostic" separator=" OR ">
                <choose>
                  <when test="diagnostic.name() == 'OVERDUE'">(p.prediction_status = 'PUBLISHED' AND p.lock_time &lt;= #{criteria.referenceTime})</when>
                  <when test="diagnostic.name() == 'SCHEDULED'">(p.prediction_status = 'PUBLISHED' AND p.lock_time &gt; #{criteria.referenceTime})</when>
                  <otherwise>p.prediction_status = 'LOCKED'</otherwise>
                </choose>
              </foreach>
              )
            </if>
            </script>
            """)
    long countLockPredictions(@Param("criteria") AdminPredictionStatusCriteria criteria);

    /** 统计筛选范围内到期但未锁定的预测。 */
    @Select("""
            <script>
            SELECT COUNT(*)
            FROM predictions p
            INNER JOIN matches m ON m.id = p.match_id
            WHERE p.prediction_status IN
            <foreach collection="criteria.predictionStatuses" item="status" open="(" separator="," close=")">
                #{status}
            </foreach>
            <if test="criteria.lotteryDate != null">AND m.lottery_date = #{criteria.lotteryDate}</if>
            <if test="criteria.modelVersion != null and criteria.modelVersion != ''">AND p.model_version = #{criteria.modelVersion}</if>
            <if test="criteria.lockDiagnostics != null and !criteria.lockDiagnostics.isEmpty()">
              AND (
              <foreach collection="criteria.lockDiagnostics" item="diagnostic" separator=" OR ">
                <choose>
                  <when test="diagnostic.name() == 'OVERDUE'">(p.prediction_status = 'PUBLISHED' AND p.lock_time &lt;= #{criteria.referenceTime})</when>
                  <when test="diagnostic.name() == 'SCHEDULED'">(p.prediction_status = 'PUBLISHED' AND p.lock_time &gt; #{criteria.referenceTime})</when>
                  <otherwise>p.prediction_status = 'LOCKED'</otherwise>
                </choose>
              </foreach>
              )
            </if>
            AND p.prediction_status = 'PUBLISHED'
            AND p.lock_time &lt;= #{criteria.referenceTime}
            </script>
            """)
    long countOverdueLocks(@Param("criteria") AdminPredictionStatusCriteria criteria);

    /** 读取待赛果、待结算或需重算的已锁定预测。 */
    @Select("""
            <script>
            SELECT p.id
            FROM predictions p
            INNER JOIN matches m ON m.id = p.match_id
            LEFT JOIN match_result_facts fact ON fact.match_id = p.match_id AND fact.is_current = TRUE
            LEFT JOIN settlements had ON had.prediction_id = p.id AND had.market_type = 'HAD' AND had.is_current = TRUE
            LEFT JOIN settlements hhad ON hhad.prediction_id = p.id AND hhad.market_type = 'HHAD' AND hhad.is_current = TRUE
            WHERE p.prediction_status = 'LOCKED'
            <if test="criteria.lotteryDate != null">AND m.lottery_date = #{criteria.lotteryDate}</if>
            <if test="criteria.modelVersion != null and criteria.modelVersion != ''">AND p.model_version = #{criteria.modelVersion}</if>
            <if test="criteria.settlementDiagnostics != null and !criteria.settlementDiagnostics.isEmpty()">
              AND (
              <foreach collection="criteria.settlementDiagnostics" item="diagnostic" separator=" OR ">
                <choose>
                  <when test="diagnostic.name() == 'AWAITING_RESULT'">(fact.id IS NULL OR fact.fact_status = 'PENDING')</when>
                  <when test="diagnostic.name() == 'SETTLEMENT_MISSING_HAD'">(fact.fact_status IN ('FINAL', 'VOID') AND had.id IS NULL)</when>
                  <when test="diagnostic.name() == 'SETTLEMENT_MISSING_HHAD'">(fact.fact_status IN ('FINAL', 'VOID') AND hhad.id IS NULL)</when>
                  <when test="diagnostic.name() == 'SETTLEMENT_STALE_HAD'">(fact.fact_status IN ('FINAL', 'VOID') AND had.id IS NOT NULL AND had.match_fact_id &lt;&gt; fact.id)</when>
                  <otherwise>(fact.fact_status IN ('FINAL', 'VOID') AND hhad.id IS NOT NULL AND hhad.match_fact_id &lt;&gt; fact.id)</otherwise>
                </choose>
              </foreach>
              )
            </if>
            ORDER BY CASE WHEN fact.fact_status IN ('FINAL', 'VOID')
                                AND (had.id IS NULL OR hhad.id IS NULL
                                     OR had.match_fact_id &lt;&gt; fact.id OR hhad.match_fact_id &lt;&gt; fact.id) THEN 0 ELSE 1 END,
                     p.lock_time ASC, p.id ASC
            LIMIT #{criteria.pageSize} OFFSET #{criteria.offset}
            </script>
            """)
    List<Long> selectSettlementPredictionIds(@Param("criteria") AdminPredictionStatusCriteria criteria);

    /** 统计结算筛选命中的预测。 */
    @Select("""
            <script>
            SELECT COUNT(*)
            FROM predictions p
            INNER JOIN matches m ON m.id = p.match_id
            LEFT JOIN match_result_facts fact ON fact.match_id = p.match_id AND fact.is_current = TRUE
            LEFT JOIN settlements had ON had.prediction_id = p.id AND had.market_type = 'HAD' AND had.is_current = TRUE
            LEFT JOIN settlements hhad ON hhad.prediction_id = p.id AND hhad.market_type = 'HHAD' AND hhad.is_current = TRUE
            WHERE p.prediction_status = 'LOCKED'
            <if test="criteria.lotteryDate != null">AND m.lottery_date = #{criteria.lotteryDate}</if>
            <if test="criteria.modelVersion != null and criteria.modelVersion != ''">AND p.model_version = #{criteria.modelVersion}</if>
            <if test="criteria.settlementDiagnostics != null and !criteria.settlementDiagnostics.isEmpty()">
              AND (
              <foreach collection="criteria.settlementDiagnostics" item="diagnostic" separator=" OR ">
                <choose>
                  <when test="diagnostic.name() == 'AWAITING_RESULT'">(fact.id IS NULL OR fact.fact_status = 'PENDING')</when>
                  <when test="diagnostic.name() == 'SETTLEMENT_MISSING_HAD'">(fact.fact_status IN ('FINAL', 'VOID') AND had.id IS NULL)</when>
                  <when test="diagnostic.name() == 'SETTLEMENT_MISSING_HHAD'">(fact.fact_status IN ('FINAL', 'VOID') AND hhad.id IS NULL)</when>
                  <when test="diagnostic.name() == 'SETTLEMENT_STALE_HAD'">(fact.fact_status IN ('FINAL', 'VOID') AND had.id IS NOT NULL AND had.match_fact_id &lt;&gt; fact.id)</when>
                  <otherwise>(fact.fact_status IN ('FINAL', 'VOID') AND hhad.id IS NOT NULL AND hhad.match_fact_id &lt;&gt; fact.id)</otherwise>
                </choose>
              </foreach>
              )
            </if>
            </script>
            """)
    long countSettlementPredictions(@Param("criteria") AdminPredictionStatusCriteria criteria);

    /** 统计确认赛果存在但结算缺失或引用过期事实的待人工处理数。 */
    @Select("""
            <script>
            SELECT COUNT(*)
            FROM predictions p
            INNER JOIN matches m ON m.id = p.match_id
            LEFT JOIN match_result_facts fact ON fact.match_id = p.match_id AND fact.is_current = TRUE
            LEFT JOIN settlements had ON had.prediction_id = p.id AND had.market_type = 'HAD' AND had.is_current = TRUE
            LEFT JOIN settlements hhad ON hhad.prediction_id = p.id AND hhad.market_type = 'HHAD' AND hhad.is_current = TRUE
            WHERE p.prediction_status = 'LOCKED'
            <if test="criteria.lotteryDate != null">AND m.lottery_date = #{criteria.lotteryDate}</if>
            <if test="criteria.modelVersion != null and criteria.modelVersion != ''">AND p.model_version = #{criteria.modelVersion}</if>
            <if test="criteria.settlementDiagnostics != null and !criteria.settlementDiagnostics.isEmpty()">
              AND (
              <foreach collection="criteria.settlementDiagnostics" item="diagnostic" separator=" OR ">
                <choose>
                  <when test="diagnostic.name() == 'AWAITING_RESULT'">(fact.id IS NULL OR fact.fact_status = 'PENDING')</when>
                  <when test="diagnostic.name() == 'SETTLEMENT_MISSING_HAD'">(fact.fact_status IN ('FINAL', 'VOID') AND had.id IS NULL)</when>
                  <when test="diagnostic.name() == 'SETTLEMENT_MISSING_HHAD'">(fact.fact_status IN ('FINAL', 'VOID') AND hhad.id IS NULL)</when>
                  <when test="diagnostic.name() == 'SETTLEMENT_STALE_HAD'">(fact.fact_status IN ('FINAL', 'VOID') AND had.id IS NOT NULL AND had.match_fact_id &lt;&gt; fact.id)</when>
                  <otherwise>(fact.fact_status IN ('FINAL', 'VOID') AND hhad.id IS NOT NULL AND hhad.match_fact_id &lt;&gt; fact.id)</otherwise>
                </choose>
              </foreach>
              )
            </if>
            AND fact.fact_status IN ('FINAL', 'VOID')
            AND (had.id IS NULL OR hhad.id IS NULL OR had.match_fact_id &lt;&gt; fact.id OR hhad.match_fact_id &lt;&gt; fact.id)
            </script>
            """)
    long countManualSettlementAttention(@Param("criteria") AdminPredictionStatusCriteria criteria);

    /** 确认详情目标属于管理员运营范围，草稿不会被读取。 */
    @Select("""
            SELECT id FROM predictions
            WHERE id = #{predictionId}
              AND prediction_status IN ('PUBLISHED', 'LOCKED')
            """)
    Long selectOperationalPredictionId(@Param("predictionId") Long predictionId);
}
