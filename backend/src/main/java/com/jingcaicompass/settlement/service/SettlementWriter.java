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
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 单条预测结算事务：锁定输入、追加两个市场结算并写入审计。 */
@Component
@ConditionalOnBean(DataSource.class)
public class SettlementWriter {

    /** T403 已冻结的纯函数规则标识；规则变更由 T405 以新结算版本处理。 */
    static final String RULE_VERSION = "t403-v1";

    static final String SYSTEM_OPERATOR = "system:settlement-job";

    private final PredictionMapper predictionMapper;
    private final MatchMapper matchMapper;
    private final MatchResultFactMapper factMapper;
    private final SportteryPoolSnapshotMapper poolSnapshotMapper;
    private final SettlementMapper settlementMapper;
    private final MarketSettlementCalculatorRouter calculatorRouter;
    private final AuditLogService auditLogService;

    public SettlementWriter(
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
     * 处理一条预测的所有未结算市场；方法独立提交，异常会回滚该预测的所有新增结算。
     *
     * @param predictionId 已锁定预测 ID
     * @return 已写入或因并发/资格变化跳过的结果
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SettlementWriteResult settlePrediction(Long predictionId) {
        if (predictionId == null) {
            throw new IllegalArgumentException("predictionId must not be null");
        }

        // 1) 依次锁定预测与比赛，串行化重复 Job 和赛果事实变更。
        Prediction prediction = predictionMapper.selectByIdForUpdate(predictionId);
        if (prediction == null || prediction.getPredictionStatus() != PredictionStatusEnum.LOCKED) {
            return SettlementWriteResult.skipped();
        }
        MatchEntity match = matchMapper.selectByIdForUpdate(prediction.getMatchId());
        if (match == null) {
            throw new IllegalStateException("locked prediction references missing match: " + predictionId);
        }
        MatchResultFact fact = factMapper.selectCurrentByMatchId(match.getId());
        if (!isConfirmed(fact)) {
            return SettlementWriteResult.skipped();
        }

        // 2) 重新检查两个市场，避免并发重试创建第二条当前结算。
        List<MarketTypeEnum> pendingMarkets = pendingMarkets(predictionId);
        if (pendingMarkets.isEmpty()) {
            return SettlementWriteResult.skipped();
        }
        if (prediction.getHandicapPick() == null) {
            throw new SettlementManualReviewException("prediction has no explicit three-way selection: " + predictionId);
        }

        // 3) HHAD 只消费锁定之前已保存的官方让球，FINAL 缺失该输入时整条预测回滚待补数。
        BigDecimal officialHandicap = officialHandicapFor(prediction, fact, pendingMarkets);

        // 4) 调用冻结纯函数、追加不可变结算和审计；任何写入失败都会回滚该预测的两个市场。
        for (MarketTypeEnum marketType : pendingMarkets) {
            SettlementStatusEnum status = calculatorRouter.calculate(new MarketSettlementInputDto(
                    marketType,
                    prediction.getHandicapPick(),
                    fact,
                    marketType == MarketTypeEnum.HHAD ? officialHandicap : null
            ));
            if (status == SettlementStatusEnum.PENDING) {
                throw new IllegalStateException("confirmed fact produced pending settlement: " + fact.getId());
            }

            Settlement settlement = new Settlement();
            settlement.setPredictionId(prediction.getId());
            settlement.setMarketType(marketType);
            settlement.setSettlementVersion(1);
            settlement.setSupersedesSettlementVersion(null);
            settlement.setSettlementStatus(status);
            settlement.setMatchFactId(fact.getId());
            settlement.setRuleVersion(RULE_VERSION);
            settlement.setIsCurrent(true);
            settlementMapper.insert(settlement);
            auditLogService.append(
                    SYSTEM_OPERATOR,
                    AuditTargetTypeEnum.SETTLEMENT,
                    String.valueOf(settlement.getId()),
                    AuditActionTypeEnum.SETTLE,
                    "settlement",
                    null,
                    snapshot(settlement, fact)
            );
        }
        return SettlementWriteResult.settled(pendingMarkets.size());
    }

    private boolean isConfirmed(MatchResultFact fact) {
        return fact != null && (fact.getFactStatus() == MatchResultFactStatusEnum.FINAL
                || fact.getFactStatus() == MatchResultFactStatusEnum.VOID);
    }

    private List<MarketTypeEnum> pendingMarkets(Long predictionId) {
        List<MarketTypeEnum> result = new ArrayList<>();
        for (MarketTypeEnum marketType : MarketTypeEnum.values()) {
            if (settlementMapper.selectCurrentByPredictionIdAndMarket(predictionId, marketType) == null) {
                result.add(marketType);
            }
        }
        return result;
    }

    private BigDecimal officialHandicapFor(
            Prediction prediction,
            MatchResultFact fact,
            List<MarketTypeEnum> pendingMarkets
    ) {
        if (!pendingMarkets.contains(MarketTypeEnum.HHAD) || fact.getFactStatus() == MatchResultFactStatusEnum.VOID) {
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

    private String snapshot(Settlement settlement, MatchResultFact fact) {
        return "predictionId=" + settlement.getPredictionId()
                + ";marketType=" + settlement.getMarketType()
                + ";settlementVersion=" + settlement.getSettlementVersion()
                + ";status=" + settlement.getSettlementStatus()
                + ";matchFactId=" + fact.getId()
                + ";factVersion=" + fact.getFactVersion()
                + ";ruleVersion=" + settlement.getRuleVersion();
    }
}
