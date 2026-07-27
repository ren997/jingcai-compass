package com.jingcaicompass.settlement.service;

import com.jingcaicompass.audit.enums.AuditActionTypeEnum;
import com.jingcaicompass.audit.enums.AuditTargetTypeEnum;
import com.jingcaicompass.audit.service.AuditLogService;
import com.jingcaicompass.match.entity.MatchEntity;
import com.jingcaicompass.match.entity.MatchResultFact;
import com.jingcaicompass.match.entity.SportteryPoolSnapshot;
import com.jingcaicompass.match.enums.MatchResultFactStatusEnum;
import com.jingcaicompass.match.mapper.MatchMapper;
import com.jingcaicompass.match.mapper.MatchResultFactMapper;
import com.jingcaicompass.match.mapper.SportteryPoolSnapshotMapper;
import com.jingcaicompass.prediction.entity.Prediction;
import com.jingcaicompass.prediction.enums.PredictionStatusEnum;
import com.jingcaicompass.prediction.mapper.PredictionMapper;
import com.jingcaicompass.settlement.dto.MarketSettlementInputDto;
import com.jingcaicompass.settlement.entity.Settlement;
import com.jingcaicompass.settlement.enums.MarketTypeEnum;
import com.jingcaicompass.settlement.enums.SettlementStatusEnum;
import com.jingcaicompass.settlement.exception.SettlementManualReviewException;
import com.jingcaicompass.settlement.mapper.SettlementMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 单条预测重算事务：以新官方事实追加结算版本并保留完整审计链。 */
@Component
@ConditionalOnBean(DataSource.class)
public class SettlementRecalculationWriter {

    static final String SYSTEM_OPERATOR = "system:settlement-recalculation-job";
    static final String OFFICIAL_FACT_SUPERSEDED = "OFFICIAL_FACT_SUPERSEDED";

    private final PredictionMapper predictionMapper;
    private final MatchMapper matchMapper;
    private final MatchResultFactMapper factMapper;
    private final SportteryPoolSnapshotMapper poolSnapshotMapper;
    private final SettlementMapper settlementMapper;
    private final MarketSettlementCalculatorRouter calculatorRouter;
    private final AuditLogService auditLogService;

    public SettlementRecalculationWriter(
            PredictionMapper predictionMapper,
            MatchMapper matchMapper,
            MatchResultFactMapper factMapper,
            SportteryPoolSnapshotMapper poolSnapshotMapper,
            SettlementMapper settlementMapper,
            MarketSettlementCalculatorRouter calculatorRouter,
            AuditLogService auditLogService
    ) {
        this.predictionMapper = predictionMapper;
        this.matchMapper = matchMapper;
        this.factMapper = factMapper;
        this.poolSnapshotMapper = poolSnapshotMapper;
        this.settlementMapper = settlementMapper;
        this.calculatorRouter = calculatorRouter;
        this.auditLogService = auditLogService;
    }

    /**
     * 将一条预测所有过期市场替代为基于当前官方事实的新版结算。
     *
     * @param predictionId 已锁定预测 ID
     * @return 已写入或因并发/资格变化跳过的结果
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SettlementRecalculationWriteResult recalculatePrediction(Long predictionId) {
        if (predictionId == null) {
            throw new IllegalArgumentException("predictionId must not be null");
        }

        // 1) 按普通结算相同顺序锁定预测与比赛，串行化赛果修正和重复 Job 扫描。
        Prediction prediction = predictionMapper.selectByIdForUpdate(predictionId);
        if (prediction == null || prediction.getPredictionStatus() != PredictionStatusEnum.LOCKED) {
            return SettlementRecalculationWriteResult.skipped();
        }
        MatchEntity match = matchMapper.selectByIdForUpdate(prediction.getMatchId());
        if (match == null) {
            throw new IllegalStateException("locked prediction references missing match: " + predictionId);
        }
        MatchResultFact currentFact = factMapper.selectCurrentByMatchId(match.getId());
        if (!isConfirmed(currentFact)) {
            return SettlementRecalculationWriteResult.skipped();
        }

        // 2) 锁后再次识别过期市场，并在写入前完整验证规则和锁定时刻的让球输入。
        List<Settlement> staleSettlements = staleSettlements(prediction.getId(), currentFact.getId());
        if (staleSettlements.isEmpty()) {
            return SettlementRecalculationWriteResult.skipped();
        }
        if (prediction.getHandicapPick() == null) {
            throw new SettlementManualReviewException("prediction has no explicit three-way selection: " + predictionId);
        }
        validateRuleVersions(staleSettlements, predictionId);
        BigDecimal officialHandicap = officialHandicapFor(prediction, currentFact, staleSettlements);
        List<RecalculationPlan> plans = calculatePlans(staleSettlements, currentFact, prediction, officialHandicap);

        // 3) 在一个事务中降级全部旧 current、插入替代版本并追加前后快照审计。
        for (RecalculationPlan plan : plans) {
            if (settlementMapper.markNotCurrent(plan.oldSettlement().getId()) != 1) {
                throw new IllegalStateException("current settlement changed concurrently: " + plan.oldSettlement().getId());
            }

            Settlement replacement = new Settlement();
            replacement.setPredictionId(prediction.getId());
            replacement.setMarketType(plan.oldSettlement().getMarketType());
            replacement.setSettlementVersion(plan.oldSettlement().getSettlementVersion() + 1);
            replacement.setSupersedesSettlementVersion(plan.oldSettlement().getSettlementVersion());
            replacement.setSettlementStatus(plan.status());
            replacement.setMatchFactId(currentFact.getId());
            replacement.setRuleVersion(plan.oldSettlement().getRuleVersion());
            replacement.setIsCurrent(true);
            settlementMapper.insert(replacement);

            MatchResultFact oldFact = factMapper.selectById(plan.oldSettlement().getMatchFactId());
            if (oldFact == null) {
                throw new IllegalStateException("settlement references missing match fact: " + plan.oldSettlement().getId());
            }
            auditLogService.append(
                    SYSTEM_OPERATOR,
                    AuditTargetTypeEnum.SETTLEMENT,
                    String.valueOf(replacement.getId()),
                    AuditActionTypeEnum.SUPERSEDE,
                    "settlementRecalculation",
                    snapshot(plan.oldSettlement(), oldFact),
                    snapshot(replacement, currentFact)
            );
        }
        return SettlementRecalculationWriteResult.recalculated(plans.size());
    }

    private boolean isConfirmed(MatchResultFact fact) {
        return fact != null && (fact.getFactStatus() == MatchResultFactStatusEnum.FINAL
                || fact.getFactStatus() == MatchResultFactStatusEnum.VOID);
    }

    private List<Settlement> staleSettlements(Long predictionId, Long currentFactId) {
        List<Settlement> result = new ArrayList<>();
        for (MarketTypeEnum marketType : MarketTypeEnum.values()) {
            Settlement settlement = settlementMapper.selectCurrentByPredictionIdAndMarket(predictionId, marketType);
            if (settlement != null && !Objects.equals(settlement.getMatchFactId(), currentFactId)) {
                result.add(settlement);
            }
        }
        return result;
    }

    private void validateRuleVersions(List<Settlement> staleSettlements, Long predictionId) {
        for (Settlement settlement : staleSettlements) {
            if (!SettlementWriter.RULE_VERSION.equals(settlement.getRuleVersion())) {
                throw new SettlementManualReviewException(
                        "unsupported settlement ruleVersion for prediction " + predictionId + ": "
                                + settlement.getRuleVersion()
                );
            }
        }
    }

    private BigDecimal officialHandicapFor(
            Prediction prediction,
            MatchResultFact fact,
            List<Settlement> staleSettlements
    ) {
        boolean requiresHandicap = staleSettlements.stream()
                .anyMatch(settlement -> settlement.getMarketType() == MarketTypeEnum.HHAD);
        if (!requiresHandicap || fact.getFactStatus() == MatchResultFactStatusEnum.VOID) {
            return null;
        }
        if (prediction.getLockTime() == null) {
            throw new SettlementManualReviewException("locked prediction has no lockTime: " + prediction.getId());
        }
        SportteryPoolSnapshot snapshot = poolSnapshotMapper.selectLatestOfficialHandicapAtOrBefore(
                prediction.getMatchId(),
                prediction.getLockTime()
        );
        if (snapshot == null || snapshot.getOfficialHandicap() == null) {
            throw new SettlementManualReviewException(
                    "no official handicap snapshot at or before lock time for prediction: " + prediction.getId()
            );
        }
        return snapshot.getOfficialHandicap();
    }

    private List<RecalculationPlan> calculatePlans(
            List<Settlement> staleSettlements,
            MatchResultFact currentFact,
            Prediction prediction,
            BigDecimal officialHandicap
    ) {
        List<RecalculationPlan> plans = new ArrayList<>();
        for (Settlement oldSettlement : staleSettlements) {
            SettlementStatusEnum status = calculatorRouter.calculate(new MarketSettlementInputDto(
                    oldSettlement.getMarketType(),
                    prediction.getHandicapPick(),
                    currentFact,
                    oldSettlement.getMarketType() == MarketTypeEnum.HHAD ? officialHandicap : null
            ));
            if (status == SettlementStatusEnum.PENDING) {
                throw new IllegalStateException("confirmed fact produced pending settlement: " + currentFact.getId());
            }
            plans.add(new RecalculationPlan(oldSettlement, status));
        }
        return plans;
    }

    private String snapshot(Settlement settlement, MatchResultFact fact) {
        return "reason=" + OFFICIAL_FACT_SUPERSEDED
                + ";settlementId=" + settlement.getId()
                + ";settlementVersion=" + settlement.getSettlementVersion()
                + ";supersedesSettlementVersion=" + settlement.getSupersedesSettlementVersion()
                + ";marketType=" + settlement.getMarketType()
                + ";status=" + settlement.getSettlementStatus()
                + ";matchFactId=" + settlement.getMatchFactId()
                + ";factVersion=" + fact.getFactVersion()
                + ";ruleVersion=" + settlement.getRuleVersion();
    }

    private record RecalculationPlan(Settlement oldSettlement, SettlementStatusEnum status) {
    }
}
