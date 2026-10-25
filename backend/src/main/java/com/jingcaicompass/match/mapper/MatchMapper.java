package com.jingcaicompass.match.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jingcaicompass.match.dto.MatchListCriteriaDto;
import com.jingcaicompass.match.entity.MatchEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.time.LocalDate;
import java.util.List;

@Mapper
public interface MatchMapper extends BaseMapper<MatchEntity> {

    /** 在预测发布等状态事务中锁定单场比赛。 */
    @Select("SELECT * FROM matches WHERE id = #{id} FOR UPDATE")
    MatchEntity selectByIdForUpdate(@Param("id") Long id);

    /** 按供应商比赛 ID 锁定赛果投影，供单源赛果写入使用。 */
    @Select("""
            SELECT * FROM matches
            WHERE external_match_id = #{externalMatchId}
            FOR UPDATE
            """)
    MatchEntity selectByExternalMatchIdForUpdate(
            @Param("externalMatchId") String externalMatchId
    );

    /** 按体彩自然键锁定赛果投影，串行化同场事实版本写入。 */
    @Select("""
            SELECT * FROM matches
            WHERE lottery_date = #{lotteryDate}
              AND lottery_match_no = #{lotteryMatchNo}
            FOR UPDATE
            """)
    MatchEntity selectByLotteryIdentityForUpdate(
            @Param("lotteryDate") LocalDate lotteryDate,
            @Param("lotteryMatchNo") String lotteryMatchNo
    );

    /** 按固定白名单排序分页查询公开比赛。 */
    @Select("""
            <script>
            SELECT m.* FROM matches m
            <where>
              <if test="criteria.lotteryDate != null">
                m.lottery_date = #{criteria.lotteryDate}
              </if>
              <if test="criteria.leagueId != null">
                AND m.league_id = #{criteria.leagueId}
              </if>
              <if test="criteria.matchStatuses != null and !criteria.matchStatuses.isEmpty()">
                AND m.match_status IN
                <foreach collection="criteria.matchStatuses" item="status" open="(" separator="," close=")">
                  #{status}
                </foreach>
              </if>
              <if test="criteria.hasPublishedPrediction">
                AND EXISTS (
                  SELECT 1 FROM predictions p
                  WHERE p.match_id = m.id
                    AND p.prediction_status IN ('PUBLISHED', 'LOCKED')
                    AND p.prediction_type = 'ASIAN'
                )
              </if>
            </where>
            ORDER BY
            <choose>
              <when test="criteria.sort.name() == 'KICKOFF_DESC'">m.kickoff_time DESC, m.id DESC</when>
              <when test="criteria.sort.name() == 'LOTTERY_MATCH_NO_ASC'">m.lottery_match_no ASC, m.id ASC</when>
              <when test="criteria.sort.name() == 'LOTTERY_MATCH_NO_DESC'">m.lottery_match_no DESC, m.id DESC</when>
              <otherwise>m.kickoff_time ASC, m.id ASC</otherwise>
            </choose>
            LIMIT #{criteria.pageSize} OFFSET #{criteria.offset}
            </script>
            """)
    List<MatchEntity> selectPublicPage(@Param("criteria") MatchListCriteriaDto criteria);

    /** 统计公开比赛分页筛选结果。 */
    @Select("""
            <script>
            SELECT COUNT(*) FROM matches m
            <where>
              <if test="criteria.lotteryDate != null">
                m.lottery_date = #{criteria.lotteryDate}
              </if>
              <if test="criteria.leagueId != null">
                AND m.league_id = #{criteria.leagueId}
              </if>
              <if test="criteria.matchStatuses != null and !criteria.matchStatuses.isEmpty()">
                AND m.match_status IN
                <foreach collection="criteria.matchStatuses" item="status" open="(" separator="," close=")">
                  #{status}
                </foreach>
              </if>
              <if test="criteria.hasPublishedPrediction">
                AND EXISTS (
                  SELECT 1 FROM predictions p
                  WHERE p.match_id = m.id
                    AND p.prediction_status IN ('PUBLISHED', 'LOCKED')
                    AND p.prediction_type = 'ASIAN'
                )
              </if>
            </where>
            </script>
            """)
    long countPublicPage(@Param("criteria") MatchListCriteriaDto criteria);

    /** 兼容按竞彩业务日返回全部比赛。 */
    @Select("""
            SELECT * FROM matches
            WHERE lottery_date = #{lotteryDate}
            ORDER BY kickoff_time ASC, id ASC
            """)
    List<MatchEntity> selectPublicDailyMatches(@Param("lotteryDate") LocalDate lotteryDate);

}
