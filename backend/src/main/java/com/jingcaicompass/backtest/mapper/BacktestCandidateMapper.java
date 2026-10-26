package com.jingcaicompass.backtest.mapper;

import com.jingcaicompass.backtest.dto.BacktestCandidateDto;
import com.jingcaicompass.backtest.vo.BacktestLeagueVo;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 回测候选场次与联赛候选。
 *
 * <p>单独一个 mapper 而不是塞进 {@code BacktestCaseMapper}：这两个查询读的是
 * {@code matches} / {@code teams} / {@code leagues}，一张回测表都不碰。
 * 混进去会让「回测不写线上表」这条边界在代码里变得模糊。</p>
 */
@Mapper
public interface BacktestCandidateMapper {

    /**
     * 某业务日区间内的候选场次。
     *
     * <p>三个过滤条件都不是可选的：{@code external_match_id} 为空就无法查历史赔率；
     * {@code kickoff_time} 为空就算不出决策截面；{@code home/away_team_id} 为空则队名缺失，
     * 而归档表要存队名（事后核对「这场是不是我以为的那场」靠它）。</p>
     *
     * <p>不用 {@code match_status} 过滤：{@code FINISHED} 只由赛果写入方设置，
     * 历史上有大量已结束但状态没推进的场次。结算阶段按 {@code match_result_facts} 判定，
     * 那里才是权威。</p>
     */
    @Select("""
            <script>
            SELECT m.id                      AS matchId,
                   m.external_match_id       AS externalMatchId,
                   m.lottery_date            AS lotteryDate,
                   m.lottery_match_no        AS lotteryMatchNo,
                   l.name_zh                 AS leagueName,
                   h.name_zh                 AS homeTeamName,
                   a.name_zh                 AS awayTeamName,
                   m.kickoff_time            AS kickoffTime,
                   m.match_status            AS matchStatus
            FROM matches m
            JOIN teams h ON h.id = m.home_team_id
            JOIN teams a ON a.id = m.away_team_id
            LEFT JOIN leagues l ON l.id = m.league_id
            WHERE m.lottery_date BETWEEN #{dateFrom} AND #{dateTo}
              AND m.external_match_id IS NOT NULL
              AND m.external_match_id &lt;&gt; ''
              AND m.kickoff_time IS NOT NULL
              <if test="leagueNames != null and leagueNames.size() > 0">
                AND l.name_zh IN
                <foreach collection="leagueNames" item="leagueName" open="(" separator="," close=")">
                  #{leagueName}
                </foreach>
              </if>
            ORDER BY m.lottery_date, m.id
            </script>
            """)
    List<BacktestCandidateDto> selectCandidates(
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("leagueNames") Collection<String> leagueNames
    );

    /**
     * 日期区间内的联赛候选，附带已归档成功场次数。
     *
     * <p>{@code archivedCount} 按 (联赛, 日期区间) 统计而不是全表：用户看到的候选是
     * 「这段时间里能跑多少」，一个联赛在别的月份采过并不说明这次能跑。</p>
     */
    @Select("""
            SELECT l.name_zh AS leagueName,
                   count(DISTINCT m.id)::int AS matchCount,
                   count(DISTINCT CASE WHEN ar.id IS NOT NULL THEN m.id END)::int AS archivedCount
            FROM matches m
            LEFT JOIN leagues l ON l.id = m.league_id
            LEFT JOIN odds_history_archive ar
                   ON ar.external_match_id = m.external_match_id
                  AND ar.provider_code = #{providerCode}
                  AND ar.fetch_status = 'SUCCESS'
            WHERE m.lottery_date BETWEEN #{dateFrom} AND #{dateTo}
              AND m.external_match_id IS NOT NULL
              AND l.name_zh IS NOT NULL
            GROUP BY l.name_zh
            ORDER BY count(DISTINCT m.id) DESC, l.name_zh
            """)
    List<BacktestLeagueVo> selectLeagues(
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("providerCode") String providerCode
    );
}
