package com.jingcaicompass.match.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jingcaicompass.match.entity.MatchSourceMapping;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface MatchSourceMappingMapper extends BaseMapper<MatchSourceMapping> {

    /** 读取当前页竞彩比赛对应的映射行 ID；实体由 BaseMapper 统一反序列化。 */
    @Select("""
            <script>
            SELECT mapping.id
            FROM match_source_mappings mapping
            WHERE mapping.mapping_status = #{mappingStatus}
            <if test="providerCode != null and providerCode != ''">
              AND mapping.provider_code = #{providerCode}
            </if>
            AND (
              mapping.match_id IN
              <foreach collection="matchIds" item="matchId" open="(" separator="," close=")">
                #{matchId}
              </foreach>
              OR EXISTS (
                SELECT 1
                FROM jsonb_array_elements(COALESCE(mapping.mapping_candidates, '[]'::jsonb)) candidate
                WHERE (candidate -&gt;&gt; 'matchId')::BIGINT IN
                <foreach collection="matchIds" item="matchId" open="(" separator="," close=")">
                  #{matchId}
                </foreach>
              )
            )
            ORDER BY mapping.updated_at DESC, mapping.id DESC
            </script>
            """)
    List<Long> selectReviewMappingIdsForMatches(
            @Param("providerCode") String providerCode,
            @Param("mappingStatus") String mappingStatus,
            @Param("matchIds") List<Long> matchIds
    );
}
