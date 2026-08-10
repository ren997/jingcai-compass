package com.jingcaicompass.odds.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jingcaicompass.odds.entity.AsianOddsSnapshot;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AsianOddsSnapshotMapper extends BaseMapper<AsianOddsSnapshot> {

    /** 读取一场比赛按来源、公司和让球线分组后的最新亚盘。 */
    @Select("""
            SELECT DISTINCT ON (provider_code, bookmaker_code, handicap_line) *
            FROM asian_odds_snapshots
            WHERE match_id = #{matchId}
            ORDER BY provider_code, bookmaker_code, handicap_line, captured_at DESC, id DESC
            """)
    List<AsianOddsSnapshot> selectLatestPublicLinesByMatchId(@Param("matchId") Long matchId);

    /**
     * 为预测基线读取已确认赛事映射下、让球与大小球字段完整的最新亚盘。
     *
     * <p>仅依赖已经落库的快照与人工/自动确认映射，不在预测请求中访问 Provider。</p>
     */
    @Select("""
            <script>
            SELECT DISTINCT ON (snapshot.match_id) snapshot.*
            FROM asian_odds_snapshots snapshot
            INNER JOIN match_source_mappings mapping
              ON mapping.match_id = snapshot.match_id
             AND mapping.provider_code = snapshot.provider_code
             AND mapping.mapping_status IN ('AUTO_CONFIRMED', 'MANUAL_CONFIRMED')
            WHERE snapshot.match_id IN
            <foreach collection="matchIds" item="matchId" open="(" separator="," close=")">
              #{matchId}
            </foreach>
              AND snapshot.handicap_line IS NOT NULL
              AND snapshot.home_odds IS NOT NULL
              AND snapshot.away_odds IS NOT NULL
              AND snapshot.total_line IS NOT NULL
              AND snapshot.over_odds IS NOT NULL
              AND snapshot.under_odds IS NOT NULL
            ORDER BY snapshot.match_id, snapshot.captured_at DESC, snapshot.id DESC
            </script>
            """)
    List<AsianOddsSnapshot> selectLatestCompleteConfirmedLinesByMatchIds(
            @Param("matchIds") Collection<Long> matchIds
    );
}
