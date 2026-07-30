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
            SELECT * FROM matches
            <where>
              <if test="criteria.lotteryDate != null">
                lottery_date = #{criteria.lotteryDate}
              </if>
              <if test="criteria.leagueId != null">
                AND league_id = #{criteria.leagueId}
              </if>
              <if test="criteria.matchStatuses != null and !criteria.matchStatuses.isEmpty()">
                AND match_status IN
                <foreach collection="criteria.matchStatuses" item="status" open="(" separator="," close=")">
                  #{status}
                </foreach>
              </if>
            </where>
            ORDER BY
            <choose>
              <when test="criteria.sort.name() == 'KICKOFF_DESC'">kickoff_time DESC, id DESC</when>
              <when test="criteria.sort.name() == 'LOTTERY_MATCH_NO_ASC'">lottery_match_no ASC, id ASC</when>
              <when test="criteria.sort.name() == 'LOTTERY_MATCH_NO_DESC'">lottery_match_no DESC, id DESC</when>
              <otherwise>kickoff_time ASC, id ASC</otherwise>
            </choose>
            LIMIT #{criteria.pageSize} OFFSET #{criteria.offset}
            </script>
            """)
    List<MatchEntity> selectPublicPage(@Param("criteria") MatchListCriteriaDto criteria);

    /** 统计公开比赛分页筛选结果。 */
    @Select("""
            <script>
            SELECT COUNT(*) FROM matches
            <where>
              <if test="criteria.lotteryDate != null">
                lottery_date = #{criteria.lotteryDate}
              </if>
              <if test="criteria.leagueId != null">
                AND league_id = #{criteria.leagueId}
              </if>
              <if test="criteria.matchStatuses != null and !criteria.matchStatuses.isEmpty()">
                AND match_status IN
                <foreach collection="criteria.matchStatuses" item="status" open="(" separator="," close=")">
                  #{status}
                </foreach>
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

    /** 按映射候选关系分页读取待复核的竞彩比赛。 */
    @Select("""
            <script>
            SELECT DISTINCT m.*
            FROM matches m
            INNER JOIN match_source_mappings mapping
              ON mapping.match_id = m.id
                 OR EXISTS (
                    SELECT 1
                    FROM jsonb_array_elements(COALESCE(mapping.mapping_candidates, '[]'::jsonb)) candidate
                    WHERE candidate -&gt;&gt; 'matchId' = m.id::text
                 )
            WHERE mapping.mapping_status = #{mappingStatus}
            <choose>
              <when test="reviewScope == 'HISTORY'">
                AND m.kickoff_time IS NOT NULL AND m.kickoff_time &lt;= CURRENT_TIMESTAMP
              </when>
              <otherwise>
                AND (m.kickoff_time IS NULL OR m.kickoff_time &gt; CURRENT_TIMESTAMP)
              </otherwise>
            </choose>
            <if test="providerCode != null and providerCode != ''">
              AND mapping.provider_code = #{providerCode}
            </if>
            ORDER BY m.lottery_date DESC, m.kickoff_time ASC NULLS LAST, m.id ASC
            LIMIT #{pageSize} OFFSET #{offset}
            </script>
            """)
    List<MatchEntity> selectMappingReviewMatchPage(
            @Param("providerCode") String providerCode,
            @Param("mappingStatus") String mappingStatus,
            @Param("reviewScope") String reviewScope,
            @Param("offset") long offset,
            @Param("pageSize") long pageSize
    );

    /** 统计至少拥有一个该状态外部候选的竞彩比赛数。 */
    @Select("""
            <script>
            SELECT COUNT(DISTINCT m.id)
            FROM matches m
            INNER JOIN match_source_mappings mapping
              ON mapping.match_id = m.id
                 OR EXISTS (
                    SELECT 1
                    FROM jsonb_array_elements(COALESCE(mapping.mapping_candidates, '[]'::jsonb)) candidate
                    WHERE candidate -&gt;&gt; 'matchId' = m.id::text
                 )
            WHERE mapping.mapping_status = #{mappingStatus}
            <choose>
              <when test="reviewScope == 'HISTORY'">
                AND m.kickoff_time IS NOT NULL AND m.kickoff_time &lt;= CURRENT_TIMESTAMP
              </when>
              <otherwise>
                AND (m.kickoff_time IS NULL OR m.kickoff_time &gt; CURRENT_TIMESTAMP)
              </otherwise>
            </choose>
            <if test="providerCode != null and providerCode != ''">
              AND mapping.provider_code = #{providerCode}
            </if>
            </script>
            """)
    long countMappingReviewMatches(
            @Param("providerCode") String providerCode,
            @Param("mappingStatus") String mappingStatus,
            @Param("reviewScope") String reviewScope
    );
}
