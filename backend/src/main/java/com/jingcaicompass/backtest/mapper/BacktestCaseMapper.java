package com.jingcaicompass.backtest.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jingcaicompass.backtest.entity.BacktestCaseEntity;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface BacktestCaseMapper extends BaseMapper<BacktestCaseEntity> {

    /**
     * 幂等写入一条样本。
     *
     * <p>冲突目标 {@code (run_id, external_match_id, group_key, decision_at)} 是完整唯一约束，
     * 因此不需要像归档那样带谓词。断点续跑时同一场同一参数组会被重算一次，
     * 命中冲突即跳过——重算结果理论上相同，但没有理由让它覆盖第一次的结论。</p>
     *
     * <p>枚举列直接绑 {@code #{item.xxx}}：这里所有参与写入的枚举都满足「常量名 == 编码」，
     * 所以无论 MyBatis 用默认的 {@code name()} 还是 MP 的 {@code @EnumValue} 处理器，
     * 落库值都一样。新增枚举若两者不同，必须显式写 {@code #{item.xxx.code}}。</p>
     */
    @Insert("""
            INSERT INTO backtest_cases (
                run_id, group_key, external_match_id, match_id, archive_id,
                lottery_match_no, league_name, decision_at, kickoff_time,
                skip_reason, decision_stage,
                frozen_bookmaker_code, frozen_handicap_line, frozen_home_odds, frozen_away_odds,
                frozen_total_line, frozen_over_odds, frozen_under_odds, frozen_captured_at,
                handicap_pick, handicap_confidence, totals_pick, totals_confidence,
                handicap_dispersion, totals_dispersion, lambda_home, lambda_away,
                model_cover_prob, market_cover_prob, edge_cover,
                model_over_prob, market_over_prob, edge_over,
                neighbor_max_deviation, max_residual, handicap_gate_pass, totals_gate_pass,
                result_status
            ) VALUES (
                #{item.runId}, #{item.groupKey}, #{item.externalMatchId}, #{item.matchId}, #{item.archiveId},
                #{item.lotteryMatchNo}, #{item.leagueName}, #{item.decisionAt}, #{item.kickoffTime},
                #{item.skipReason}, #{item.decisionStage},
                #{item.frozenBookmakerCode}, #{item.frozenHandicapLine}, #{item.frozenHomeOdds},
                #{item.frozenAwayOdds}, #{item.frozenTotalLine}, #{item.frozenOverOdds},
                #{item.frozenUnderOdds}, #{item.frozenCapturedAt},
                #{item.handicapPick}, #{item.handicapConfidence}, #{item.totalsPick}, #{item.totalsConfidence},
                #{item.handicapDispersion}, #{item.totalsDispersion}, #{item.lambdaHome}, #{item.lambdaAway},
                #{item.modelCoverProb}, #{item.marketCoverProb}, #{item.edgeCover},
                #{item.modelOverProb}, #{item.marketOverProb}, #{item.edgeOver},
                #{item.neighborMaxDeviation}, #{item.maxResidual}, #{item.handicapGatePass},
                #{item.totalsGatePass}, 'PENDING'
            )
            ON CONFLICT (run_id, external_match_id, group_key, decision_at) DO NOTHING
            """)
    int insertIgnore(@Param("item") BacktestCaseEntity entity);

    /**
     * 写入结算结论（只写一次）。
     *
     * <p>{@code settled_at IS NULL} 既在 WHERE 里也在触发器里：这里保证「第二次不会静默生效」，
     * 触发器保证「绕过这里的写法也改不了」。两者不是冗余——WHERE 让调用方能知道返回 0 行，
     * 触发器保证任何路径都改不了已结算行。</p>
     */
    @Update("""
            UPDATE backtest_cases
            SET result_status = #{resultStatus},
                home_score = #{homeScore},
                away_score = #{awayScore},
                handicap_status = #{handicapStatus},
                handicap_profit = #{handicapProfit},
                totals_status = #{totalsStatus},
                totals_profit = #{totalsProfit},
                settled_at = #{settledAt}
            WHERE id = #{caseId} AND settled_at IS NULL
            """)
    int settleOnce(
            @Param("caseId") Long caseId,
            @Param("resultStatus") String resultStatus,
            @Param("homeScore") Integer homeScore,
            @Param("awayScore") Integer awayScore,
            @Param("handicapStatus") String handicapStatus,
            @Param("handicapProfit") BigDecimal handicapProfit,
            @Param("totalsStatus") String totalsStatus,
            @Param("totalsProfit") BigDecimal totalsProfit,
            @Param("settledAt") java.time.Instant settledAt
    );

    /** 按 run 与参数组读取全部样本（对比与聚合的唯一入口）。 */
    @Select("""
            SELECT *
            FROM backtest_cases
            WHERE run_id = #{runId}
            ORDER BY group_key, external_match_id
            """)
    List<BacktestCaseEntity> selectByRunId(@Param("runId") Long runId);

    /** 该 run 是否还有未结算的样本；标 SUCCEEDED 前必须为 0。 */
    @Select("""
            SELECT count(*)
            FROM backtest_cases
            WHERE run_id = #{runId} AND result_status = 'PENDING'
            """)
    long countPending(@Param("runId") Long runId);

    /** 已写入的样本数（用于断点续跑的「还剩多少场要算」）。 */
    @Select("""
            SELECT count(DISTINCT external_match_id)
            FROM backtest_cases
            WHERE run_id = #{runId}
            """)
    long countDistinctMatches(@Param("runId") Long runId);
}
