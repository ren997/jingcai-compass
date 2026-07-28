package com.jingcaicompass.prediction.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jingcaicompass.prediction.dto.PredictionLockCandidateDto;
import com.jingcaicompass.prediction.entity.Prediction;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PredictionMapper extends BaseMapper<Prediction> {

    /** 在发布事务中锁定单条预测。 */
    @Select("SELECT * FROM predictions WHERE id = #{id} FOR UPDATE")
    Prediction selectByIdForUpdate(@Param("id") Long id);

    /** 查询同比赛模型当前已发布或已锁定的最高版本。 */
    @Select("""
            SELECT MAX(prediction_version)
            FROM predictions
            WHERE match_id = #{matchId}
              AND model_version = #{modelVersion}
              AND prediction_status IN ('PUBLISHED', 'LOCKED')
            """)
    Integer selectLatestPublishedVersion(
            @Param("matchId") Long matchId,
            @Param("modelVersion") String modelVersion
    );

    /** 查询竞彩业务日内每个比赛和模型的最高公开版本。 */
    @Select("""
            SELECT DISTINCT ON (p.match_id, p.model_version) p.*
            FROM predictions p
            INNER JOIN matches m ON m.id = p.match_id
            WHERE m.lottery_date = #{snapshotDate}
              AND p.prediction_status IN ('PUBLISHED', 'LOCKED')
            ORDER BY p.match_id,
                     p.model_version,
                     p.prediction_version DESC,
                     p.id DESC
            """)
    List<Prediction> selectCurrentPublishedByLotteryDate(
            @Param("snapshotDate") LocalDate snapshotDate
    );

    /** 按模型和版本顺序读取单场全部公开预测，草稿不会进入公共查询。 */
    @Select("""
            SELECT *
            FROM predictions
            WHERE match_id = #{matchId}
              AND prediction_status IN ('PUBLISHED', 'LOCKED')
            ORDER BY model_version ASC, prediction_version ASC, id ASC
            """)
    List<Prediction> selectPublicByMatchId(@Param("matchId") Long matchId);

    /** 仅允许完整 DRAFT 原子进入 PUBLISHED。 */
    @Update("""
            UPDATE predictions
            SET prediction_status = 'PUBLISHED',
                publish_time = #{publishTime},
                lock_time = #{lockTime},
                prediction_hash = #{predictionHash},
                updated_at = #{publishTime}
            WHERE id = #{predictionId}
              AND prediction_status = 'DRAFT'
              AND publish_time IS NULL
              AND lock_time IS NULL
              AND prediction_hash IS NULL
            """)
    int publishDraft(
            @Param("predictionId") Long predictionId,
            @Param("publishTime") Instant publishTime,
            @Param("lockTime") Instant lockTime,
            @Param("predictionHash") String predictionHash
    );

    /** 使用数据库时间抢占下一条到期预测，跳过其他事务已经持有的记录。 */
    @Select("""
            <script>
            SELECT id AS prediction_id,
                   lock_time,
                   CURRENT_TIMESTAMP AS database_time
            FROM predictions
            WHERE prediction_status = 'PUBLISHED'
              AND lock_time &lt;= CURRENT_TIMESTAMP
            <if test="excludedPredictionIds != null and !excludedPredictionIds.isEmpty()">
              AND id NOT IN
              <foreach collection="excludedPredictionIds"
                       item="excludedId"
                       open="("
                       separator=","
                       close=")">
                #{excludedId}
              </foreach>
            </if>
            ORDER BY lock_time, id
            FOR UPDATE SKIP LOCKED
            LIMIT 1
            </script>
            """)
    PredictionLockCandidateDto selectNextDueForUpdate(
            @Param("excludedPredictionIds") Collection<Long> excludedPredictionIds
    );

    /** 仅允许已到 PostgreSQL 锁定时间的 PUBLISHED 原子进入 LOCKED。 */
    @Update("""
            UPDATE predictions
            SET prediction_status = 'LOCKED',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{predictionId}
              AND prediction_status = 'PUBLISHED'
              AND lock_time <= CURRENT_TIMESTAMP
            """)
    int lockPublishedPrediction(@Param("predictionId") Long predictionId);
}
