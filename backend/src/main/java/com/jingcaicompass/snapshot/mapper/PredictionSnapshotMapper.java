package com.jingcaicompass.snapshot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jingcaicompass.snapshot.entity.PredictionSnapshot;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 公开预测快照元数据及发布状态条件更新。 */
@Mapper
public interface PredictionSnapshotMapper extends BaseMapper<PredictionSnapshot> {

    /** 查询同一业务日和 manifest 哈希的已发布快照，优先校验最新版本。 */
    @Select("""
            SELECT *
            FROM prediction_snapshots
            WHERE snapshot_date = #{snapshotDate}
              AND snapshot_hash = #{snapshotHash}
              AND snapshot_status = 'PUBLISHED'
            ORDER BY snapshot_version DESC, id DESC
            """)
    List<PredictionSnapshot> selectPublishedByDateAndHash(
            @Param("snapshotDate") LocalDate snapshotDate,
            @Param("snapshotHash") String snapshotHash
    );

    /** 查询业务日所有状态已占用的最高快照版本。 */
    @Select("""
            SELECT MAX(snapshot_version)
            FROM prediction_snapshots
            WHERE snapshot_date = #{snapshotDate}
            """)
    Integer selectMaxVersion(@Param("snapshotDate") LocalDate snapshotDate);

    /** 仅允许完整 PENDING 原子进入 PUBLISHED，并返回数据库最终元数据。 */
    @Select("""
            UPDATE prediction_snapshots
            SET snapshot_status = 'PUBLISHED',
                snapshot_hash = #{snapshotHash},
                storage_type = #{storageType},
                object_key = #{objectKey},
                object_version = #{objectVersion},
                file_url = #{fileUrl},
                content_type = #{contentType},
                content_length = #{contentLength},
                published_at = CURRENT_TIMESTAMP,
                failure_reason = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{snapshotId}
              AND snapshot_status = 'PENDING'
            RETURNING *
            """)
    PredictionSnapshot publishPending(
            @Param("snapshotId") Long snapshotId,
            @Param("snapshotHash") String snapshotHash,
            @Param("storageType") String storageType,
            @Param("objectKey") String objectKey,
            @Param("objectVersion") String objectVersion,
            @Param("fileUrl") String fileUrl,
            @Param("contentType") String contentType,
            @Param("contentLength") long contentLength
    );

    /** 存储失败时保留占用版本，并仅写入失败摘要。 */
    @Update("""
            UPDATE prediction_snapshots
            SET snapshot_status = 'FAILED',
                failure_reason = #{failureReason},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{snapshotId}
              AND snapshot_status = 'PENDING'
            """)
    int failPending(
            @Param("snapshotId") Long snapshotId,
            @Param("failureReason") String failureReason
    );
}
