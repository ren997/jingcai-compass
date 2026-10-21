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
import com.jingcaicompass.prediction.enums.PredictionTypeEnum;
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
import java.util.Map;
import java.util.Set;
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

    /** 亚盘让球与大小球规则的标识；与竞彩规则分开，便于独立演进和重算校验。 */
    static final String ASIAN_RULE_VERSION = "t613-v1";

    /** 允许参与重算的规则版本集合。 */
    static final Set<String> SUPPORTED_RULE_VERSIONS = Set.of(RULE_VERSION, ASIAN_RULE_VERSION);

    static final String SYSTEM_OPERATOR = "system:settlement-job";

    private final PredictionMapper predictionMapper;
    private final MatchMapper matchMapper;
    private final MatchResultFactMapper factMapper;
    private final SportteryPoolSnapshotMapper poolSnapshotMapper;
    private final SettlementMapper settlementMapper;
    private final MarketSettlementCalculatorRouter calculatorRouter;
    private final AsianMarketTermsResolver asianMarketTermsResolver;
    private final AuditLogService auditLogService;

    public SettlementWriter(
            PredictionMapper predictionMapper,
            MatchMapper matchMapper,
            MatchResultFactMapper factMapper,
            SportteryPoolSnapshotMapper poolSnapshotMapper,
            SettlementMapper settlementMapper,
            MarketSettlementCalculatorRouter calculatorRouter,
            AsianMarketTermsResolver asianMarketTermsResolver,
            AuditLogService auditLogService
    ) {
        this.predictionMapper = predictionMapper;
        this.matchMapper = matchMapper;
        this.factMapper = factMapper;
        this.poolSnapshotMapper = poolSnapshotMapper;
        this.settlementMapper = settlementMapper;
        this.calculatorRouter = calculatorRouter;
        this.asianMarketTermsResolver = asianMarketTermsResolver;
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
        PredictionTypeEnum predictionType = prediction.getPredictionType() == null
                ? PredictionTypeEnum.SPORTTERY
                : prediction.getPredictionType();
        MatchEntity match = matchMapper.selectByIdForUpdate(prediction.getMatchId());
        if (match == null) {
            throw new IllegalStateException("locked prediction references missing match: " + predictionId);
        }
        MatchResultFact fact = factMapper.selectCurrentByMatchId(match.getId());
        if (!isConfirmed(fact)) {
            return SettlementWriteResult.skipped();
        }

        // 2) 只重新检查该类预测负责的市场，避免并发重试创建第二条当前结算。
        List<MarketTypeEnum> pendingMarkets = pendingMarkets(predictionId, predictionType);
        if (pendingMarkets.isEmpty()) {
            return SettlementWriteResult.skipped();
        }
        if (predictionType == PredictionTypeEnum.SPORTTERY && prediction.getHandicapPick() == null) {
            throw new SettlementManualReviewException("prediction has no explicit three-way selection: " + predictionId);
        }

        // 3) 竞彩市场只消费锁定之前已保存的官方让球；亚盘市场只消费预测绑定快照。
        //    FINAL 缺失对应输入时整条预测回滚待补数，绝不用另一类市场的输入顶替。
        BigDecimal officialHandicap = officialHandicapFor(prediction, fact, pendingMarkets);
        Map<MarketTypeEnum, AsianMarketTermsResolver.Terms> asianTerms =
                predictionType == PredictionTypeEnum.ASIAN
                        ? asianMarketTermsResolver.resolve(prediction)
                        : Map.of();

        // 4) 调用冻结纯函数、追加不可变结算和审计；任何写入失败都会回滚该预测的全部市场。
        int settledMarketCount = 0;
        for (MarketTypeEnum marketType : pendingMarkets) {
            BigDecimal marketLine = null;
            BigDecimal price = null;
            MarketSettlementInputDto input;
            if (predictionType == PredictionTypeEnum.ASIAN) {
                AsianMarketTermsResolver.Terms terms = asianTerms.get(marketType);
                if (terms == null) {
                    // 亚盘预测允许只给出一侧方向，缺失方向的市场不结算且不阻断另一侧。
                    continue;
                }
                marketLine = terms.line();
                price = terms.price();
                input = new MarketSettlementInputDto(
                        marketType,
                        null,
                        fact,
                        null,
                        terms.asianHandicapPick(),
                        terms.totalGoalsPick(),
                        marketLine
                );
            } else {
                input = new MarketSettlementInputDto(
                        marketType,
                        prediction.getHandicapPick(),
                        fact,
                        marketType == MarketTypeEnum.HHAD ? officialHandicap : null
                );
            }

            SettlementStatusEnum status = calculatorRouter.calculate(input);
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
            settlement.setRuleVersion(predictionType == PredictionTypeEnum.ASIAN ? ASIAN_RULE_VERSION : RULE_VERSION);
            settlement.setProfit(predictionType == PredictionTypeEnum.ASIAN
                    ? SettlementMarginSupport.netProfit(status, price)
                    : null);
            settlement.setIsCurrent(true);
            settlementMapper.insert(settlement);
            auditLogService.append(
                    SYSTEM_OPERATOR,
                    AuditTargetTypeEnum.SETTLEMENT,
                    String.valueOf(settlement.getId()),
                    AuditActionTypeEnum.SETTLE,
                    "settlement",
                    null,
                    snapshot(settlement, fact, marketLine)
            );
            settledMarketCount++;
        }
        return settledMarketCount == 0
                ? SettlementWriteResult.skipped()
                : SettlementWriteResult.settled(settledMarketCount);
    }

    private boolean isConfirmed(MatchResultFact fact) {
        return fact != null && (fact.getFactStatus() == MatchResultFactStatusEnum.FINAL
                || fact.getFactStatus() == MatchResultFactStatusEnum.VOID);
    }

    private List<MarketTypeEnum> pendingMarkets(Long predictionId, PredictionTypeEnum predictionType) {
        List<MarketTypeEnum> result = new ArrayList<>();
        for (MarketTypeEnum marketType : MarketTypeEnum.marketsFor(predictionType)) {
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

    private String snapshot(Settlement settlement, MatchResultFact fact, BigDecimal marketLine) {
        return "predictionId=" + settlement.getPredictionId()
                + ";marketType=" + settlement.getMarketType()
                + ";settlementVersion=" + settlement.getSettlementVersion()
                + ";status=" + settlement.getSettlementStatus()
                + ";profit=" + settlement.getProfit()
                + ";marketLine=" + marketLine
                + ";matchFactId=" + fact.getId()
                + ";factVersion=" + fact.getFactVersion()
                + ";ruleVersion=" + settlement.getRuleVersion();
    }
}
