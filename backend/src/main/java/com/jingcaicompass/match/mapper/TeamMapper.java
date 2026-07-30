package com.jingcaicompass.match.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jingcaicompass.match.entity.Team;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TeamMapper extends BaseMapper<Team> {

    /** 仅返回已被竞彩比赛采用的内部球队，排除供应商临时身份。 */
    @Select("""
            <script>
            SELECT team.*
            FROM teams team
            WHERE EXISTS (
                SELECT 1 FROM matches match
                WHERE match.home_team_id = team.id OR match.away_team_id = team.id
            )
            <if test="keyword != null and keyword != ''">
              AND (team.name_zh LIKE CONCAT('%', #{keyword}, '%')
                   OR team.name_en LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            ORDER BY team.id ASC
            LIMIT 20
            </script>
            """)
    java.util.List<Team> selectTrustedNormalizationCandidates(@Param("keyword") String keyword);

    /** 校验目标球队属于竞彩内部标准基线。 */
    @Select("""
            SELECT EXISTS (
                SELECT 1 FROM matches
                WHERE home_team_id = #{teamId} OR away_team_id = #{teamId}
            )
            """)
    boolean isTrustedNormalizationEntity(@Param("teamId") Long teamId);
}
