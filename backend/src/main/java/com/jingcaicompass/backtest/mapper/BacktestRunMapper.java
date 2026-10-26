package com.jingcaicompass.backtest.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jingcaicompass.backtest.entity.BacktestRunEntity;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface BacktestRunMapper extends BaseMapper<BacktestRunEntity> {

    /**
     * 抢一个待跑的 run。
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} 是这里唯一正确的写法：先 SELECT 再由应用侧判断
     * 「没人跑吧」在多实例下必然出现两处同时判断为真。{@code SKIP LOCKED} 让第二个实例直接跳过
     * 已被锁住的行，而不是排队等锁——排队会把「本应空闲」的实例变成另一个跑同一个 run 的实例。</p>
     *
     * <p>必须在事务内调用；本方法只负责加锁，状态推进由调用方在同一事务里完成。</p>
     */
    @Select("""
            SELECT *
            FROM backtest_runs
            WHERE run_status = 'QUEUED'
            ORDER BY created_at, id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """)
    BacktestRunEntity lockNextQueued();

    /** 启动自愈的输入：所有还在非终态的行。 */
    @Select("""
            SELECT *
            FROM backtest_runs
            WHERE run_status IN ('QUEUED', 'ARCHIVING', 'EVALUATING', 'SETTLING', 'AGGREGATING')
            ORDER BY id
            """)
    List<BacktestRunEntity> selectInFlight();

    /**
     * 心跳 + 进度。
     *
     * <p>用自增而不是「读出来改完写回」：进度是每场都刷新的高频写，读改写会在两个 worker
     * 交替时报丢失更新，而丢失的恰好是「已经处理了多少场」——恢复时据此判断断点，错一位就会重跑。</p>
     */
    @Update("""
            UPDATE backtest_runs
            SET processed_match_count = processed_match_count + #{processedDelta},
                archive_reused_count = archive_reused_count + #{reusedDelta},
                archive_fetched_count = archive_fetched_count + #{fetchedDelta},
                archive_failed_count = archive_failed_count + #{failedDelta},
                case_count = case_count + #{caseDelta},
                quota_cost = quota_cost + #{quotaDelta},
                run_stage = #{stage},
                last_heartbeat_at = #{heartbeatAt},
                updated_at = #{heartbeatAt}
            WHERE id = #{runId}
            """)
    int addProgress(
            @Param("runId") Long runId,
            @Param("processedDelta") int processedDelta,
            @Param("reusedDelta") int reusedDelta,
            @Param("fetchedDelta") int fetchedDelta,
            @Param("failedDelta") int failedDelta,
            @Param("caseDelta") int caseDelta,
            @Param("quotaDelta") int quotaDelta,
            @Param("stage") String stage,
            @Param("heartbeatAt") Instant heartbeatAt
    );

    /**
     * 只续心跳，不改任何计数。
     *
     * <p>为什么需要它：判定阶段某一批可能**一条样本都没插入**（归档不可用的场次会整场跳过），
     * 这时 {@code addProgress...if (全部增量为 0) return} 就不写库了，心跳跟着停。
     * 而心跳是「这个 run 还活着」的唯一证据——心跳一停，重启自愈会把还在跑的 run 判成
     * {@code INTERRUPTED}。所以「没有可汇报的数字」不等于「不发心跳」。</p>
     */
    @Update("""
            UPDATE backtest_runs
            SET last_heartbeat_at = #{heartbeatAt},
                updated_at = #{heartbeatAt}
            WHERE id = #{runId}
            """)
    int touchHeartbeat(@Param("runId") Long runId, @Param("heartbeatAt") Instant heartbeatAt);

    /**
     * 推进状态与阶段。
     *
     * <p>状态机的合法性由 DB 触发器把关（非法流转直接抛错），Java 侧不做重复校验：
     * 两份白名单一定会分叉，而分叉的表现是「某些路径能跳步」——那是事后最难查的一类缺陷。</p>
     */
    @Update("""
            UPDATE backtest_runs
            SET run_status = #{status},
                run_stage = #{stage},
                started_at = COALESCE(started_at, #{startedAt}),
                finished_at = CASE WHEN #{terminal} THEN #{finishedAt} ELSE finished_at END,
                error_code = #{errorCode},
                error_message = #{errorMessage},
                last_heartbeat_at = #{finishedAt},
                updated_at = #{finishedAt}
            WHERE id = #{runId}
            """)
    int transition(
            @Param("runId") Long runId,
            @Param("status") String status,
            @Param("stage") String stage,
            @Param("terminal") boolean terminal,
            @Param("startedAt") Instant startedAt,
            @Param("finishedAt") Instant finishedAt,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage
    );

    /** 取消：只置标志，不删已写数据。worker 每场开始前重读。 */
    @Update("""
            UPDATE backtest_runs
            SET cancel_requested = TRUE, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{runId} AND run_status IN ('QUEUED', 'ARCHIVING', 'EVALUATING', 'SETTLING', 'AGGREGATING')
            """)
    int requestCancel(@Param("runId") Long runId);

    /** 续跑：{@code INTERRUPTED → QUEUED}，必须同时清空 {@code finished_at}。 */
    @Update("""
            UPDATE backtest_runs
            SET run_status = 'QUEUED', resume_count = resume_count + 1,
                finished_at = NULL, last_heartbeat_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{runId} AND run_status = 'INTERRUPTED'
            """)
    int resumeInterrupted(@Param("runId") Long runId);
}
