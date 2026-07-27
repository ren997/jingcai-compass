package com.jingcaicompass.match.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jingcaicompass.match.entity.SportteryPoolSnapshot;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SportteryPoolSnapshotMapper extends BaseMapper<SportteryPoolSnapshot> {

    /** 查询预测锁定时刻可追溯的最新官方让球快照。 */
    @Select("""
            SELECT * FROM sporttery_pool_snapshots
            WHERE match_id = #{matchId}
              AND captured_at <= #{lockedAt}
              AND official_handicap IS NOT NULL
            ORDER BY captured_at DESC, id DESC
            LIMIT 1
            """)
    SportteryPoolSnapshot selectLatestOfficialHandicapAtOrBefore(
            @Param("matchId") Long matchId,
            @Param("lockedAt") Instant lockedAt
    );
}
