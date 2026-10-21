package com.jingcaicompass.settlement.service;

import com.jingcaicompass.odds.entity.AsianOddsSnapshot;
import com.jingcaicompass.odds.mapper.AsianOddsSnapshotMapper;
import com.jingcaicompass.prediction.entity.Prediction;
import com.jingcaicompass.prediction.enums.AsianHandicapPickEnum;
import com.jingcaicompass.prediction.enums.TotalGoalsPickEnum;
import com.jingcaicompass.settlement.enums.MarketTypeEnum;
import com.jingcaicompass.settlement.exception.SettlementManualReviewException;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * 从预测生成时绑定的亚盘快照解析每个亚盘市场的结算输入。
 *
 * <p>只读取 {@code predictions.asian_odds_snapshot_id} 指向的冻结快照，绝不借用当前主盘：
 * 盘口线与水位必须在锁定预测时就已固定，否则结算结果会随行情漂移而无法复核。</p>
 */
@Component
@ConditionalOnBean(DataSource.class)
public class AsianMarketTermsResolver {

    private final AsianOddsSnapshotMapper snapshotMapper;

    public AsianMarketTermsResolver(AsianOddsSnapshotMapper snapshotMapper) {
        this.snapshotMapper = snapshotMapper;
    }

    /**
     * 解析一条亚盘预测可结算的市场条件。
     *
     * <p>方向为空的市场不会出现在结果中，调用方据此跳过该市场而不是报错：
     * 亚盘预测允许只给出让球方向或只给出大小球方向。</p>
     *
     * @param prediction 已锁定的亚盘预测
     * @return 以市场为键的结算条件，可能为空 Map
     */
    public Map<MarketTypeEnum, Terms> resolve(Prediction prediction) {
        Long snapshotId = prediction.getAsianOddsSnapshotId();
        if (snapshotId == null) {
            throw new SettlementManualReviewException(
                    "asian prediction has no asian odds snapshot: " + prediction.getId()
            );
        }
        AsianOddsSnapshot snapshot = snapshotMapper.selectById(snapshotId);
        if (snapshot == null) {
            throw new SettlementManualReviewException(
                    "asian prediction references missing asian odds snapshot: " + snapshotId
            );
        }
        if (!Objects.equals(snapshot.getMatchId(), prediction.getMatchId())) {
            throw new SettlementManualReviewException(
                    "asian odds snapshot " + snapshotId + " does not belong to match " + prediction.getMatchId()
            );
        }

        EnumMap<MarketTypeEnum, Terms> terms = new EnumMap<>(MarketTypeEnum.class);
        if (prediction.getAsianHandicapPick() != null) {
            AsianHandicapPickEnum pick = prediction.getAsianHandicapPick();
            terms.put(MarketTypeEnum.ASIAN_HANDICAP, new Terms(
                    pick,
                    null,
                    requireCondition(snapshot.getHandicapLine(), "handicapLine", prediction),
                    requireCondition(
                            pick == AsianHandicapPickEnum.HOME_COVER ? snapshot.getHomeOdds() : snapshot.getAwayOdds(),
                            "handicap odds of " + pick.getCode(),
                            prediction
                    )
            ));
        }
        if (prediction.getTotalGoalsPick() != null) {
            TotalGoalsPickEnum pick = prediction.getTotalGoalsPick();
            terms.put(MarketTypeEnum.ASIAN_TOTAL_GOALS, new Terms(
                    null,
                    pick,
                    requireCondition(snapshot.getTotalLine(), "totalLine", prediction),
                    requireCondition(
                            pick == TotalGoalsPickEnum.OVER ? snapshot.getOverOdds() : snapshot.getUnderOdds(),
                            "total goals odds of " + pick.getCode(),
                            prediction
                    )
            ));
        }
        return Map.copyOf(terms);
    }

    private BigDecimal requireCondition(BigDecimal value, String fieldName, Prediction prediction) {
        if (value == null) {
            throw new SettlementManualReviewException(
                    "locked asian odds snapshot lacks " + fieldName + " for prediction: " + prediction.getId()
            );
        }
        return value;
    }

    /**
     * 一个亚盘市场的结算条件：方向、盘口线与该方向在锁定时刻的水位。
     *
     * @param asianHandicapPick 让球方向，大小球市场为空
     * @param totalGoalsPick    大小球方向，让球市场为空
     * @param line              盘口线，步长 0.25
     * @param price             所选方向的欧式赔率，用于换算净收益
     */
    public record Terms(
            AsianHandicapPickEnum asianHandicapPick,
            TotalGoalsPickEnum totalGoalsPick,
            BigDecimal line,
            BigDecimal price
    ) {
    }
}
