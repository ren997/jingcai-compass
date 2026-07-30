package com.jingcaicompass.match.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jingcaicompass.match.entity.League;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface LeagueMapper extends BaseMapper<League> {

    /** 仅返回已被竞彩比赛采用的内部联赛，排除供应商临时身份。 */
    @Select("""
            <script>
            SELECT league.*
            FROM leagues league
            WHERE EXISTS (SELECT 1 FROM matches match WHERE match.league_id = league.id)
            <if test="keyword != null and keyword != ''">
              AND (league.name_zh LIKE CONCAT('%', #{keyword}, '%')
                   OR league.name_en LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            ORDER BY league.id ASC
            LIMIT 20
            </script>
            """)
    java.util.List<League> selectTrustedNormalizationCandidates(@Param("keyword") String keyword);

    /** 校验目标联赛属于竞彩内部标准基线。 */
    @Select("SELECT EXISTS (SELECT 1 FROM matches WHERE league_id = #{leagueId})")
    boolean isTrustedNormalizationEntity(@Param("leagueId") Long leagueId);
}
