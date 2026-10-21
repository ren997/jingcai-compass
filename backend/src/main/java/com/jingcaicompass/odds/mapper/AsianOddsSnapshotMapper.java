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

    /**
     * 为跨庄聚合读取「每家每家每档让球线」的最新报价。
     *
     * <p>与按场比赛取一条主盘不同，这里必须保留全部庄家与全部盘口线：主盘由后续聚合选出，
     * 取数阶段一旦收敛到单条就无法判断哪条线最平衡。</p>
     */
    @Select("""
            <script>
            SELECT DISTINCT ON (
                       snapshot.match_id,
                       snapshot.provider_code,
                       snapshot.bookmaker_code,
                       snapshot.handicap_line
                   ) snapshot.*
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
              AND snapshot.home_odds > 0
              AND snapshot.away_odds > 0
            ORDER BY snapshot.match_id, snapshot.provider_code, snapshot.bookmaker_code,
                     snapshot.handicap_line, snapshot.captured_at DESC, snapshot.id DESC
            </script>
            """)
    List<AsianOddsSnapshot> selectLatestConfirmedHandicapLinesByMatchIds(
            @Param("matchIds") Collection<Long> matchIds
    );

    /**
     * 为跨庄聚合读取大小球的「每家每档 total_line」最新报价。
     *
     * <p>必须按 {@code total_line} 去重：同一家庄的同一档大小球会随不同让球线重复出现在多行里，
     * 若沿用让球维度的去重会把同一家庄计数多次，直接扭曲中位数。</p>
     */
    @Select("""
            <script>
            SELECT DISTINCT ON (
                       snapshot.match_id,
                       snapshot.provider_code,
                       snapshot.bookmaker_code,
                       snapshot.total_line
                   ) snapshot.*
            FROM asian_odds_snapshots snapshot
            INNER JOIN match_source_mappings mapping
              ON mapping.match_id = snapshot.match_id
             AND mapping.provider_code = snapshot.provider_code
             AND mapping.mapping_status IN ('AUTO_CONFIRMED', 'MANUAL_CONFIRMED')
            WHERE snapshot.match_id IN
            <foreach collection="matchIds" item="matchId" open="(" separator="," close=")">
              #{matchId}
            </foreach>
              AND snapshot.total_line IS NOT NULL
              AND snapshot.over_odds > 0
              AND snapshot.under_odds > 0
            ORDER BY snapshot.match_id, snapshot.provider_code, snapshot.bookmaker_code,
                     snapshot.total_line, snapshot.captured_at DESC, snapshot.id DESC
            </script>
            """)
    List<AsianOddsSnapshot> selectLatestConfirmedTotalsLinesByMatchIds(
            @Param("matchIds") Collection<Long> matchIds
    );
}
