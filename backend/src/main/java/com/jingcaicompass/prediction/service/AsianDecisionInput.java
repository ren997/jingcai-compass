package com.jingcaicompass.prediction.service;

import com.jingcaicompass.match.enums.MatchStatusEnum;
import com.jingcaicompass.odds.entity.AsianOddsSnapshot;
import com.jingcaicompass.odds.service.AsianMarketConsensusAggregator;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 一次亚盘决策的全部输入。
 *
 * <p>刻意不传 {@code MatchEntity}：判定只用到「状态 + 开赛时刻 + id」，回测没有真实比赛行，
 * 传实体等于逼回测去造一条假记录。调用方从实体取出这三个字段即可。</p>
 *
 * <p>三组行分开传，与生产取数口径一一对应：</p>
 * <ul>
 *   <li>{@code handicapRows}：每家每档让球线的最新报价，用于跨庄共识；</li>
 *   <li>{@code totalsQuotes}：每家每档大小球线的最新报价，用于第二个约束与主盘线；</li>
 *   <li>{@code freezeRows}：完整交叉积（每家 × 让球线 × 大小球线），用于挑冻结行——
 *       同一档让球线下要能选到大小球主盘那一档，单看让球行选不出来。</li>
 * </ul>
 *
 * <p>**这里不校验 {@code decisionAt < kickoffTime}**：生产线按业务日遍历全部场次，已开赛的场次
 * 必须走出 {@code KICKOFF_PASSED} 这个计数分支，不能在构造期抛异常。回测侧的无泄漏保证由
 * DB CHECK 与切片器负责。</p>
 */
public record AsianDecisionInput(
        Long matchId,
        MatchStatusEnum matchStatus,
        Instant kickoffTime,
        Instant decisionAt,
        List<AsianOddsSnapshot> handicapRows,
        List<AsianMarketConsensusAggregator.Quote> totalsQuotes,
        List<AsianOddsSnapshot> freezeRows
) {

    public AsianDecisionInput {
        matchId = Objects.requireNonNull(matchId, "matchId must not be null");
        decisionAt = Objects.requireNonNull(decisionAt, "decisionAt must not be null");
        handicapRows = handicapRows == null ? List.of() : List.copyOf(handicapRows);
        totalsQuotes = totalsQuotes == null ? List.of() : List.copyOf(totalsQuotes);
        freezeRows = freezeRows == null ? List.of() : List.copyOf(freezeRows);
    }
}
