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

    /** 在当前事务内按生成批次持有 PostgreSQL advisory lock，串行化同批次导入。 */
    @Select("""
            SELECT 1
            FROM (
                SELECT pg_advisory_xact_lock(hashtextextended(#{generationBatchId}, 0))
            ) AS generation_batch_lock
            """)
    int lockGenerationBatch(@Param("generationBatchId") String generationBatchId);

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

    /** 按当前分页比赛批量读取每个模型的当前公开版本，草稿永不进入结果。 */
    @Select("""
            <script>
            SELECT DISTINCT ON (p.match_id, p.model_version) p.*
            FROM predictions p
            WHERE p.match_id IN
            <foreach collection="matchIds" item="matchId" open="(" separator="," close=")">
              #{matchId}
            </foreach>
              AND p.prediction_status IN ('PUBLISHED', 'LOCKED')
            ORDER BY p.match_id,
                     p.model_version ASC,
                     p.prediction_version DESC,
                     p.id DESC
            </script>
            """)
    List<Prediction> selectCurrentPublishedByMatchIds(
            @Param("matchIds") Collection<Long> matchIds
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

    /** 使用数据库当前时间统计超过锁定宽限期仍未锁定的公开预测。 */
    @Select("""
            SELECT COUNT(*)
            FROM predictions
            WHERE prediction_status = 'PUBLISHED'
              AND lock_time <= #{overdueBefore}
            """)
    long countOverduePublishedPredictions(@Param("overdueBefore") Instant overdueBefore);

    /** 从 PostgreSQL 取得生命周期监测使用的统一时间。 */
    @Select("SELECT CURRENT_TIMESTAMP")
    Instant selectDatabaseTime();

    /**
     * 统计当天还没产生任何公开预测的比赛数。
     *
     * <p>这是自动发布的唯一短路判据：结果为 0 时整轮连生成都不做。生成侧不幂等
     * （{@code generationBatchHash} 含 {@code generatedAt}，重跑必然插新批次），
     * 没有这个判据就等于每次调度都往库里灌一批新 DRAFT。</p>
     *
     * <p>注意它统计的是「比赛是否已发布」而不是「还有多少条 DRAFT」：被
     * {@code AsianPublishGate} 拒掉的场次压根没有 DRAFT，它们会让计数长期大于 0，
     * 那是有意的——赔率刷新后重新评估同一场才有意义，节流交给生成间隔控制。</p>
     */
    @Select("""
            SELECT COUNT(*)
            FROM matches m
            WHERE m.lottery_date = #{lotteryDate}
              AND m.kickoff_time > #{now}
              AND NOT EXISTS (
                  SELECT 1
                  FROM predictions p
                  WHERE p.match_id = m.id
                    AND p.prediction_status IN ('PUBLISHED', 'LOCKED')
              )
            """)
    long countUnpublishedMatchesByLotteryDate(
            @Param("lotteryDate") LocalDate lotteryDate,
            @Param("now") Instant now
    );

    /** 当天最近一次生成时刻，供自动发布限制重新生成的频率。 */
    @Select("""
            SELECT MAX(p.generated_at)
            FROM predictions p
            INNER JOIN matches m ON m.id = p.match_id
            WHERE m.lottery_date = #{lotteryDate}
            """)
    Instant selectLatestGeneratedAtByLotteryDate(@Param("lotteryDate") LocalDate lotteryDate);

    /**
     * 自动发布候选：每场每模型只取<b>版本号最小</b>的那条 DRAFT，且该组合必须还没有公开版本。
     *
     * <p>两个条件缺一不可。只取最小版本是为了不把赔率抖动逐轮翻成公开版本；
     * 排除已有公开版本是因为一旦某场已经 PUBLISHED，后续新批次只应作为诊断痕迹留在库里，
     * 再次发布要么撞版本冲突、要么把已公开内容顶掉。</p>
     */
    @Select("""
            SELECT DISTINCT ON (p.match_id, p.model_version) p.*
            FROM predictions p
            INNER JOIN matches m ON m.id = p.match_id
            WHERE m.lottery_date = #{lotteryDate}
              AND m.kickoff_time > #{now}
              AND p.prediction_status = 'DRAFT'
              AND NOT EXISTS (
                  SELECT 1
                  FROM predictions q
                  WHERE q.match_id = p.match_id
                    AND q.model_version = p.model_version
                    AND q.prediction_status IN ('PUBLISHED', 'LOCKED')
              )
            ORDER BY p.match_id,
                     p.model_version ASC,
                     p.prediction_version ASC,
                     p.id ASC
            """)
    List<Prediction> selectAutoPublishCandidateDrafts(
            @Param("lotteryDate") LocalDate lotteryDate,
            @Param("now") Instant now
    );

    /**
     * 追加一次自动发布失败痕迹。
     *
     * <p>只写 {@code publish_attempt_count} / {@code last_publish_error} / {@code updated_at}，
     * 三者都不在 {@code protect_published_prediction()} 的不可变列里，因此已发布行也能更新。
     * 反过来，任何触碰内容列的写法都会在触发器上直接失败。</p>
     */
    @Update("""
            UPDATE predictions
            SET publish_attempt_count = publish_attempt_count + 1,
                last_publish_error = #{error},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{predictionId}
            """)
    int recordPublishFailure(
            @Param("predictionId") Long predictionId,
            @Param("error") String error
    );
}
