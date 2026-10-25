package com.jingcaicompass.data.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jingcaicompass.data.entity.DataSyncRun;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DataSyncRunMapper extends BaseMapper<DataSyncRun> {

    /**
     * 把超时仍停在 RUNNING 的运行记录收尾为 FAILED。
     *
     * <p>{@code WHERE sync_status = 'RUNNING'} 是幂等保护：多个实例同时启动时，只有第一个
     * 拿到锁的实例能改到行，后来者影响 0 行而不是覆盖已收尾的状态。</p>
     *
     * <p>{@code started_at < threshold} 是「不误伤正在跑的任务」的唯一防线。启动时扫描理论上
     * 可以把所有 RUNNING 都当孤儿（单实例部署），但多实例前提下刚创建的行必须放过。</p>
     */
    @Update("""
            UPDATE data_sync_runs
            SET sync_status = 'FAILED',
                finished_at = #{finishedAt},
                error_message = #{errorMessage}
            WHERE sync_status = 'RUNNING'
              AND started_at < #{threshold}
            """)
    int failStaleRunning(
            @Param("finishedAt") Instant finishedAt,
            @Param("errorMessage") String errorMessage,
            @Param("threshold") Instant threshold
    );
}
