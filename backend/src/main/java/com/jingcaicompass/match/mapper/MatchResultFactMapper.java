package com.jingcaicompass.match.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jingcaicompass.match.entity.MatchResultFact;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 版本化官方赛果事实持久化接口。 */
@Mapper
public interface MatchResultFactMapper extends BaseMapper<MatchResultFact> {

    /** 查询当前权威事实；比赛主行锁已由调用方持有。 */
    @Select("""
            SELECT * FROM match_result_facts
            WHERE match_id = #{matchId}
              AND is_current = TRUE
            LIMIT 1
            """)
    MatchResultFact selectCurrentByMatchId(@Param("matchId") Long matchId);

    /** 查询比赛是否已有当前权威事实，供比赛池同步保护赛果投影。 */
    @Select("""
            SELECT EXISTS(
                SELECT 1 FROM match_result_facts
                WHERE match_id = #{matchId}
                  AND is_current = TRUE
            )
            """)
    boolean existsCurrentByMatchId(@Param("matchId") Long matchId);

    /** 唯一允许的事实更新：将 current 标记降为 false。 */
    @Update("""
            UPDATE match_result_facts
            SET is_current = FALSE
            WHERE id = #{factId}
              AND is_current = TRUE
            """)
    int markNotCurrent(@Param("factId") Long factId);

    /** 按比赛批量读取全部事实版本，供公开历史重建。 */
    @Select("""
            <script>
            SELECT * FROM match_result_facts
            WHERE match_id IN
            <foreach collection="matchIds" item="matchId" open="(" separator="," close=")">
                #{matchId}
            </foreach>
            ORDER BY match_id, fact_version
            </script>
            """)
    List<MatchResultFact> selectHistoryByMatchIds(@Param("matchIds") Collection<Long> matchIds);
}
