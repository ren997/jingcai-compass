package com.jingcaicompass.backtest.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jingcaicompass.backtest.entity.OddsHistoryArchiveEntity;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OddsHistoryArchiveMapper extends BaseMapper<OddsHistoryArchiveEntity> {

    /**
     * 读取一场比赛「已终局」的归档结论（含负缓存）。
     *
     * <p>终局 = {@code SUCCESS} / {@code NO_HISTORY} / {@code NO_QUOTE_YET}；重采不会有不同结果。
     * {@code RETRYABLE_FAILURE} 与 {@code PERMANENT_FAILURE} 不在内：前者必须重采，
     * 后者要让人看见失败而不是被安静地复用。</p>
     */
    @Select("""
            SELECT *
            FROM odds_history_archive
            WHERE provider_code = #{providerCode}
              AND external_match_id = #{externalMatchId}
              AND fetch_status IN ('SUCCESS', 'NO_HISTORY', 'NO_QUOTE_YET')
            ORDER BY attempted_at DESC, id DESC
            LIMIT 1
            """)
    OddsHistoryArchiveEntity selectFinalByExternalMatchId(
            @Param("providerCode") String providerCode,
            @Param("externalMatchId") String externalMatchId
    );

    /**
     * 只取 jsonb 原文。
     *
     * <p><b>不能靠实体的 {@code payload} 字段。</b>该字段声明了
     * {@code @TableField(typeHandler = PostgresJsonbTypeHandler.class)}，但
     * {@code @TableName(autoResultMap = true)} 生成的 resultMap 只被 MyBatis-Plus 自己拼的
     * SQL 使用；{@code @Select} 注解查询走原生映射，{@code Map<String,Object>} 字段落到
     * 默认 handler 上，jsonb 读回来是空 Map。表现是切片一行都切不出来（{@code MISSING_ROWS}），
     * 而归档状态、点数、庄家数全部正常——看起来像「这段时间没开价」。</p>
     *
     * <p>返回 {@code text} 而不是让驱动反序列化：Jackson 解析结果与「归档时写进去的那份字节」
     * 完全一致，也不受 MyBatis 字段映射机制影响。</p>
     */
    @Select("SELECT payload::text FROM odds_history_archive WHERE id = #{id}")
    String selectPayloadTextById(@Param("id") Long id);

    /**
     * 批量读取一批比赛已终局的归档结论。
     *
     * <p>回测准备阶段一次性判定「哪些场次可以省掉 API 调用」，逐场查询会把这块变成 N 次往返；
     * 更重要的是逐场查询与「循环内发 API」写法几乎一样，很容易在后续改动里退化成 N+1。</p>
     */
    @Select("""
            <script>
            SELECT DISTINCT ON (external_match_id) *
            FROM odds_history_archive
            WHERE provider_code = #{providerCode}
              AND fetch_status IN ('SUCCESS', 'NO_HISTORY', 'NO_QUOTE_YET')
              AND external_match_id IN
            <foreach collection="externalMatchIds" item="externalMatchId" open="(" separator="," close=")">
              #{externalMatchId}
            </foreach>
            ORDER BY external_match_id, attempted_at DESC, id DESC
            </script>
            """)
    List<OddsHistoryArchiveEntity> selectFinalByExternalMatchIds(
            @Param("providerCode") String providerCode,
            @Param("externalMatchIds") Collection<String> externalMatchIds
    );

    /**
     * 幂等写入一份 SUCCESS 归档。
     *
     * <p>冲突目标是<b>部分</b>唯一索引 {@code uk_odds_history_archive_success}
     * （{@code WHERE fetch_status = 'SUCCESS'}），因此必须把索引谓词一并写出：
     * 不写谓词时 PG 无法推断用哪个索引，会直接报「no unique or exclusion constraint matching」。</p>
     *
     * <p>{@code payload} 以 JSON 文本 + {@code CAST(... AS jsonb)} 绑定，而不是走 MyBatis 的
     * typeHandler：这条语句手写列清单，显式 cast 能让「写进去的一定是 jsonb」这件事在 SQL 里可见，
     * 也避免依赖 MP 在注解语句里解析 typeHandler 的隐式行为。</p>
     *
     * @return 1 表示本次真的落库；0 表示同一场已有 SUCCESS 归档（被复用）
     */
    @Insert("""
            INSERT INTO odds_history_archive (
                provider_code, external_match_id, match_id, lottery_date, league_name,
                home_team_name, away_team_name, kickoff_time, fetch_status, http_status,
                upstream_code, payload, payload_hash, bookmaker_count, point_count,
                series_start_at, series_end_at, trimmed, failure_message, attempted_at
            ) VALUES (
                #{item.providerCode}, #{item.externalMatchId}, #{item.matchId}, #{item.lotteryDate},
                #{item.leagueName}, #{item.homeTeamName}, #{item.awayTeamName}, #{item.kickoffTime},
                'SUCCESS', #{item.httpStatus}, #{item.upstreamCode}, CAST(#{payloadJson} AS jsonb),
                #{item.payloadHash}, #{item.bookmakerCount}, #{item.pointCount},
                #{item.seriesStartAt}, #{item.seriesEndAt}, COALESCE(#{item.trimmed}, TRUE),
                NULL, #{item.attemptedAt}
            )
            ON CONFLICT (provider_code, external_match_id) WHERE fetch_status = 'SUCCESS'
            DO NOTHING
            """)
    int insertSuccessIgnore(
            @Param("item") OddsHistoryArchiveEntity entity,
            @Param("payloadJson") String payloadJson
    );

    /**
     * 写入一份失败结论（含两条负缓存）。
     *
     * <p>没有 ON CONFLICT：失败行不受部分唯一索引约束，同一场同一天重采两次就该留两条痕迹——
     * 那是「上游持续 404」的唯一证据，覆盖掉就看不出来了。载荷相关列一律写 NULL，
     * 由 {@code ck_odds_history_archive_payload_scope} 强制。</p>
     */
    @Insert("""
            INSERT INTO odds_history_archive (
                provider_code, external_match_id, match_id, lottery_date, league_name,
                home_team_name, away_team_name, kickoff_time, fetch_status, http_status,
                upstream_code, payload, payload_hash, bookmaker_count, point_count,
                series_start_at, series_end_at, trimmed, failure_message, attempted_at
            ) VALUES (
                #{item.providerCode}, #{item.externalMatchId}, #{item.matchId}, #{item.lotteryDate},
                #{item.leagueName}, #{item.homeTeamName}, #{item.awayTeamName}, #{item.kickoffTime},
                #{item.fetchStatus}, #{item.httpStatus}, #{item.upstreamCode},
                NULL, NULL, NULL, NULL, NULL, NULL, COALESCE(#{item.trimmed}, FALSE),
                #{item.failureMessage}, #{item.attemptedAt}
            )
            """)
    int insertFailure(@Param("item") OddsHistoryArchiveEntity entity);

    /** 统计某个 run 覆盖的日期区间内已可复用的归档场次，用于额度预估。 */
    @Select("""
            SELECT count(*)
            FROM odds_history_archive
            WHERE provider_code = #{providerCode}
              AND fetch_status = 'SUCCESS'
              AND lottery_date BETWEEN #{dateFrom} AND #{dateTo}
            """)
    long countReusableSuccess(
            @Param("providerCode") String providerCode,
            @Param("dateFrom") java.time.LocalDate dateFrom,
            @Param("dateTo") java.time.LocalDate dateTo
    );
}
