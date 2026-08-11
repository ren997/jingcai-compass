package com.jingcaicompass.admin.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 只读取 DRAFT 预测及其竞彩比赛复核信息的管理员查询。 */
@Mapper
public interface AdminDraftPredictionMapper {

    /** 按开赛时间和草稿 ID 稳定分页读取草稿。 */
    @Select("""
            <script>
            SELECT p.id AS prediction_id,
                   p.model_version,
                   p.feature_version,
                   p.generation_batch_id,
                   p.generation_batch_hash,
                   p.prediction_version,
                   p.home_win_prob,
                   p.draw_prob,
                   p.away_win_prob,
                   p.handicap_pick,
                   p.expected_total_goals,
                   p.confidence_level,
                   p.analysis_summary,
                   p.generated_at,
                   m.id AS match_id,
                   m.lottery_date,
                   m.lottery_match_no,
                   m.league_name,
                   m.home_team_name,
                   m.away_team_name,
                   m.kickoff_time
            FROM predictions p
            INNER JOIN matches m ON m.id = p.match_id
            WHERE p.prediction_status = 'DRAFT'
            <if test="criteria.lotteryDate != null">AND m.lottery_date = #{criteria.lotteryDate}</if>
            <if test="criteria.modelVersion != null">AND p.model_version = #{criteria.modelVersion}</if>
            ORDER BY m.kickoff_time ASC, p.id ASC
            LIMIT #{criteria.pageSize} OFFSET #{criteria.offset}
            </script>
            """)
    List<AdminDraftPredictionRow> selectDraftPredictions(@Param("criteria") AdminDraftPredictionCriteria criteria);

    /** 统计当前筛选命中的草稿，公共与运营状态查询均不使用本方法。 */
    @Select("""
            <script>
            SELECT COUNT(*)
            FROM predictions p
            INNER JOIN matches m ON m.id = p.match_id
            WHERE p.prediction_status = 'DRAFT'
            <if test="criteria.lotteryDate != null">AND m.lottery_date = #{criteria.lotteryDate}</if>
            <if test="criteria.modelVersion != null">AND p.model_version = #{criteria.modelVersion}</if>
            </script>
            """)
    long countDraftPredictions(@Param("criteria") AdminDraftPredictionCriteria criteria);
}
