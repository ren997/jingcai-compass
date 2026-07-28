package com.jingcaicompass.odds.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jingcaicompass.odds.entity.AsianOddsSnapshot;
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
}
