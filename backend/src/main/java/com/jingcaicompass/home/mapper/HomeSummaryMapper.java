package com.jingcaicompass.home.mapper;

import java.time.Instant;
import java.time.LocalDate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 首页去重场次、数据采集和公开快照元数据查询。 */
@Mapper
public interface HomeSummaryMapper {

    /** 统计上海竞彩业务日的持久化比赛数。 */
    @Select("""
            SELECT COUNT(*)
            FROM matches
            WHERE lottery_date = #{lotteryDate}
            """)
    long countMatchesByLotteryDate(@Param("lotteryDate") LocalDate lotteryDate);

    /** 同一比赛的多模型和历史公开版本只按一场计数。 */
    @Select("""
            SELECT COUNT(DISTINCT p.match_id)
            FROM predictions p
            INNER JOIN matches m ON m.id = p.match_id
            WHERE m.lottery_date = #{lotteryDate}
              AND p.prediction_status IN ('PUBLISHED', 'LOCKED')
            """)
    long countPublishedPredictionMatchesByLotteryDate(@Param("lotteryDate") LocalDate lotteryDate);

    /** 全历史公开预测按比赛去重，草稿不会进入公开指标。 */
    @Select("""
            SELECT COUNT(DISTINCT match_id)
            FROM predictions
            WHERE prediction_status IN ('PUBLISHED', 'LOCKED')
            """)
    long countHistoricalPublishedPredictionMatches();

    /**
     * 任一锁定预测的「期望市场」当前结算未达到终态时，该比赛仍进入待结算数量。
     *
     * <p>期望市场按预测类型分派，不能用同一套市场常量判定：
     * <ul>
     *   <li>体彩只看 HAD（与既有口径一致，两个市场通常同时结算）；</li>
     *   <li>亚盘只要求「已经给出方向的市场」终态，且终态必须包含 {@code PUSH} 与半赢半输——
     *       漏掉这三种状态会让所有走盘场次永久停留在「待结算」。</li>
     * </ul>
     *
     * <p>未生成 current 结算和持久化为 PENDING 都不会被误计为已结算。</p>
     */
    @Select("""
            SELECT COUNT(DISTINCT p.match_id)
            FROM predictions p
            WHERE p.prediction_status = 'LOCKED'
              AND (
                  (
                      p.prediction_type = 'SPORTTERY'
                      AND NOT EXISTS (
                          SELECT 1
                          FROM settlements s
                          WHERE s.prediction_id = p.id
                            AND s.market_type = 'HAD'
                            AND s.is_current = TRUE
                            AND s.settlement_status IN ('HIT', 'MISS', 'VOID')
                      )
                  )
                  OR (
                      p.prediction_type = 'ASIAN'
                      AND (
                          (
                              p.asian_handicap_pick IS NOT NULL
                              AND NOT EXISTS (
                                  SELECT 1
                                  FROM settlements s
                                  WHERE s.prediction_id = p.id
                                    AND s.market_type = 'ASIAN_HANDICAP'
                                    AND s.is_current = TRUE
                                    AND s.settlement_status IN (
                                        'HIT', 'MISS', 'VOID', 'PUSH', 'HALF_WIN', 'HALF_LOSS'
                                    )
                              )
                          )
                          OR (
                              p.total_goals_pick IS NOT NULL
                              AND NOT EXISTS (
                                  SELECT 1
                                  FROM settlements s
                                  WHERE s.prediction_id = p.id
                                    AND s.market_type = 'ASIAN_TOTAL_GOALS'
                                    AND s.is_current = TRUE
                                    AND s.settlement_status IN (
                                        'HIT', 'MISS', 'VOID', 'PUSH', 'HALF_WIN', 'HALF_LOSS'
                                    )
                              )
                          )
                      )
                  )
              )
            """)
    long countPendingSettlementMatches();

    /** 只读取当天比赛池的最新采集时刻，旧业务日数据不会被当作当天新鲜数据。 */
    @Select("""
            SELECT MAX(s.captured_at)
            FROM sporttery_pool_snapshots s
            INNER JOIN matches m ON m.id = s.match_id
            WHERE m.lottery_date = #{lotteryDate}
            """)
    Instant selectLatestSportteryCapturedAtByLotteryDate(@Param("lotteryDate") LocalDate lotteryDate);

    /** 只公开最近一次成功发布快照的时间，不返回内部存储位置。 */
    @Select("""
            SELECT published_at
            FROM prediction_snapshots
            WHERE snapshot_status = 'PUBLISHED'
            ORDER BY published_at DESC, id DESC
            LIMIT 1
            """)
    Instant selectLatestPublishedSnapshotAt();
}
