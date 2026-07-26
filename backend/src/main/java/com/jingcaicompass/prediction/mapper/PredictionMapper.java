package com.jingcaicompass.prediction.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jingcaicompass.prediction.entity.Prediction;
import java.time.Instant;
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
}
