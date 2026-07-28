package com.jingcaicompass.match.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jingcaicompass.match.entity.SportteryPoolSnapshot;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
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

    /** 为多场比赛一次性读取各自最新体彩池快照。 */
    @Select("""
            <script>
            SELECT DISTINCT ON (match_id) *
            FROM sporttery_pool_snapshots
            WHERE match_id IN
            <foreach collection="matchIds" item="matchId" open="(" separator="," close=")">
              #{matchId}
            </foreach>
            ORDER BY match_id, captured_at DESC, id DESC
            </script>
            """)
    List<SportteryPoolSnapshot> selectLatestByMatchIds(@Param("matchIds") Collection<Long> matchIds);
}
