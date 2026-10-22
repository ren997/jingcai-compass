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
     * 为公开列表读取单场比赛「两市场字段齐全」的最新一条亚盘。
     *
     * <p>单源之后这里不再 JOIN {@code match_source_mappings}：JOIN 的唯一语义是
     * 「只取已确认映射的场次」，而单源下 {@code match_id} 本身就是唯一权威键，该条件恒真。
     * 取而代之的是按 {@code provider_code} 过滤——这是必要的，不是可选优化：
     * 快照表里可能同时存在历史 STUB 行与新 provider 行，混在一起会让首页展示的
     * 「最新一条」突然变成另一个来源的报价。</p>
     */
    @Select("""
            <script>
            SELECT DISTINCT ON (snapshot.match_id) snapshot.*
            FROM asian_odds_snapshots snapshot
            WHERE snapshot.match_id IN
            <foreach collection="matchIds" item="matchId" open="(" separator="," close=")">
              #{matchId}
            </foreach>
              AND snapshot.provider_code = #{providerCode}
              AND snapshot.handicap_line IS NOT NULL
              AND snapshot.home_odds IS NOT NULL
              AND snapshot.away_odds IS NOT NULL
              AND snapshot.total_line IS NOT NULL
              AND snapshot.over_odds IS NOT NULL
              AND snapshot.under_odds IS NOT NULL
            ORDER BY snapshot.match_id, snapshot.captured_at DESC, snapshot.id DESC
            </script>
            """)
    List<AsianOddsSnapshot> selectLatestCompleteLinesByMatchIds(
            @Param("matchIds") Collection<Long> matchIds,
            @Param("providerCode") String providerCode
    );

    /**
     * 为跨庄聚合读取「每家每档让球线」的最新报价。
     *
     * <p>与按场比赛取一条主盘不同，这里必须保留全部庄家与全部盘口线：主盘由后续聚合选出，
     * 取数阶段一旦收敛到单条就无法判断哪条线最平衡。</p>
     *
     * <p>{@code provider_code} 过滤同样不可省：聚合器的去重键是 {@code (bookmaker, line)}，
     * <b>不含 provider</b>，跨来源的同名庄会互相覆盖并一起参与中位数，结果是主盘悄悄偏移。</p>
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
            WHERE snapshot.match_id IN
            <foreach collection="matchIds" item="matchId" open="(" separator="," close=")">
              #{matchId}
            </foreach>
              AND snapshot.provider_code = #{providerCode}
              AND snapshot.handicap_line IS NOT NULL
              AND snapshot.home_odds > 0
              AND snapshot.away_odds > 0
            ORDER BY snapshot.match_id, snapshot.provider_code, snapshot.bookmaker_code,
                     snapshot.handicap_line, snapshot.captured_at DESC, snapshot.id DESC
            </script>
            """)
    List<AsianOddsSnapshot> selectLatestHandicapLinesByMatchIds(
            @Param("matchIds") Collection<Long> matchIds,
            @Param("providerCode") String providerCode
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
            WHERE snapshot.match_id IN
            <foreach collection="matchIds" item="matchId" open="(" separator="," close=")">
              #{matchId}
            </foreach>
              AND snapshot.provider_code = #{providerCode}
              AND snapshot.total_line IS NOT NULL
              AND snapshot.over_odds > 0
              AND snapshot.under_odds > 0
            ORDER BY snapshot.match_id, snapshot.provider_code, snapshot.bookmaker_code,
                     snapshot.total_line, snapshot.captured_at DESC, snapshot.id DESC
            </script>
            """)
    List<AsianOddsSnapshot> selectLatestTotalsLinesByMatchIds(
            @Param("matchIds") Collection<Long> matchIds,
            @Param("providerCode") String providerCode
    );
}
