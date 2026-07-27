package com.jingcaicompass.history.service;

import com.jingcaicompass.audit.mapper.AuditLogMapper;
import com.jingcaicompass.history.vo.HistoryListItemVo;
import com.jingcaicompass.history.vo.HistoryMatchVo;
import com.jingcaicompass.history.vo.MarketSettlementHistoryVo;
import com.jingcaicompass.history.vo.MatchResultFactHistoryVo;
import com.jingcaicompass.history.vo.SettlementVersionVo;
import com.jingcaicompass.match.entity.MatchEntity;
import com.jingcaicompass.match.entity.MatchResultFact;
import com.jingcaicompass.match.mapper.MatchMapper;
import com.jingcaicompass.match.mapper.MatchResultFactMapper;
import com.jingcaicompass.prediction.entity.Prediction;
import com.jingcaicompass.prediction.mapper.PredictionMapper;
import com.jingcaicompass.settlement.entity.Settlement;
import com.jingcaicompass.settlement.enums.MarketTypeEnum;
import com.jingcaicompass.settlement.enums.SettlementStatusEnum;
import com.jingcaicompass.settlement.mapper.SettlementMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/** 批量装配公开历史的预测、比赛、事实、结算和修正审计关系。 */
@Component
@ConditionalOnBean(DataSource.class)
public class HistoryRecordAssembler {

    private final PredictionMapper predictionMapper;
    private final MatchMapper matchMapper;
    private final MatchResultFactMapper matchResultFactMapper;
    private final SettlementMapper settlementMapper;
    private final AuditLogMapper auditLogMapper;

    public HistoryRecordAssembler(
            PredictionMapper predictionMapper,
            MatchMapper matchMapper,
            MatchResultFactMapper matchResultFactMapper,
            SettlementMapper settlementMapper,
            AuditLogMapper auditLogMapper
    ) {
        this.predictionMapper = predictionMapper;
        this.matchMapper = matchMapper;
        this.matchResultFactMapper = matchResultFactMapper;
        this.settlementMapper = settlementMapper;
        this.auditLogMapper = auditLogMapper;
    }

    /** 按调用方给定的预测 ID 顺序返回完整公开历史。 */
    public List<HistoryListItemVo> assemble(List<Long> orderedPredictionIds) {
        if (orderedPredictionIds == null || orderedPredictionIds.isEmpty()) {
            return List.of();
        }

        // 1) 批量读取预测和比赛，保留调用方已固定的分页排序。
        Map<Long, Prediction> predictions = indexPredictionsById(predictionMapper.selectBatchIds(orderedPredictionIds));
        List<Long> matchIds = orderedPredictionIds.stream()
                .map(predictions::get)
                .filter(Objects::nonNull)
                .map(Prediction::getMatchId)
                .distinct()
                .toList();
        Map<Long, MatchEntity> matches = indexMatchesById(matchMapper.selectBatchIds(matchIds));

        // 2) 批量读取全部事实、结算和对应 SUPERSEDE 审计，绝不只保留 current 行。
        Map<Long, List<MatchResultFact>> factsByMatch = groupByMatch(matchResultFactMapper.selectHistoryByMatchIds(matchIds));
        List<Settlement> settlements = settlementMapper.selectHistoryByPredictionIds(orderedPredictionIds);
        Map<Long, List<Settlement>> settlementsByPrediction = settlements.stream()
                .collect(Collectors.groupingBy(Settlement::getPredictionId));
        Set<Long> supersededSettlementIds = supersededSettlementIds(settlements);

        List<HistoryListItemVo> result = new ArrayList<>(orderedPredictionIds.size());
        for (Long predictionId : orderedPredictionIds) {
            Prediction prediction = predictions.get(predictionId);
            if (prediction == null) {
                continue;
            }
            MatchEntity match = matches.get(prediction.getMatchId());
            if (match == null) {
                throw new IllegalStateException("public prediction references missing match: " + predictionId);
            }
            List<MarketSettlementHistoryVo> markets = marketHistories(
                    settlementsByPrediction.getOrDefault(predictionId, List.of()),
                    supersededSettlementIds
            );
            result.add(new HistoryListItemVo(
                    prediction.getId(),
                    prediction.getPredictionVersion(),
                    prediction.getModelVersion(),
                    prediction.getFeatureVersion(),
                    prediction.getPredictionStatus(),
                    prediction.getHomeWinProb(),
                    prediction.getDrawProb(),
                    prediction.getAwayWinProb(),
                    prediction.getHandicapPick(),
                    prediction.getExpectedTotalGoals(),
                    prediction.getConfidenceLevel(),
                    prediction.getAnalysisSummary(),
                    prediction.getPredictionHash(),
                    prediction.getGeneratedAt(),
                    prediction.getPublishTime(),
                    prediction.getLockTime(),
                    toMatch(match),
                    factsByMatch.getOrDefault(match.getId(), List.of()).stream().map(this::toFact).toList(),
                    markets,
                    markets.stream().anyMatch(MarketSettlementHistoryVo::recalculatedAfterFactCorrection)
            ));
        }
        return List.copyOf(result);
    }

    private Set<Long> supersededSettlementIds(List<Settlement> settlements) {
        if (settlements.isEmpty()) {
            return Set.of();
        }
        Set<Long> knownIds = settlements.stream().map(Settlement::getId).collect(Collectors.toSet());
        return auditLogMapper.selectSupersededSettlementTargetIds(knownIds).stream()
                .map(this::parseSettlementId)
                .filter(knownIds::contains)
                .collect(Collectors.toUnmodifiableSet());
    }

    private Long parseSettlementId(String targetId) {
        try {
            return Long.valueOf(targetId);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("settlement audit target id is not numeric: " + targetId, exception);
        }
    }

    private List<MarketSettlementHistoryVo> marketHistories(
            List<Settlement> settlements,
            Set<Long> supersededSettlementIds
    ) {
        List<MarketSettlementHistoryVo> result = new ArrayList<>(MarketTypeEnum.values().length);
        for (MarketTypeEnum marketType : MarketTypeEnum.values()) {
            List<Settlement> marketSettlements = settlements.stream()
                    .filter(settlement -> settlement.getMarketType() == marketType)
                    .sorted(Comparator.comparing(Settlement::getSettlementVersion))
                    .toList();
            Settlement current = marketSettlements.stream()
                    .filter(settlement -> Boolean.TRUE.equals(settlement.getIsCurrent()))
                    .findFirst()
                    .orElse(null);
            result.add(new MarketSettlementHistoryVo(
                    marketType,
                    current == null ? SettlementStatusEnum.PENDING : current.getSettlementStatus(),
                    current != null,
                    current != null
                            && current.getSupersedesSettlementVersion() != null
                            && supersededSettlementIds.contains(current.getId()),
                    marketSettlements.stream().map(this::toSettlement).toList()
            ));
        }
        return List.copyOf(result);
    }

    private HistoryMatchVo toMatch(MatchEntity match) {
        return new HistoryMatchVo(
                match.getId(),
                match.getLotteryDate(),
                match.getLotteryMatchNo(),
                match.getLeagueId(),
                match.getLeagueName(),
                match.getHomeTeamName(),
                match.getAwayTeamName(),
                match.getKickoffTime()
        );
    }

    private MatchResultFactHistoryVo toFact(MatchResultFact fact) {
        return new MatchResultFactHistoryVo(
                fact.getId(),
                fact.getFactVersion(),
                fact.getSupersedesFactVersion(),
                fact.getFactStatus(),
                fact.getMatchStatus(),
                fact.getHomeScore(),
                fact.getAwayScore(),
                fact.getProviderUpdatedAt(),
                Boolean.TRUE.equals(fact.getIsCurrent()),
                fact.getCreatedAt()
        );
    }

    private SettlementVersionVo toSettlement(Settlement settlement) {
        return new SettlementVersionVo(
                settlement.getId(),
                settlement.getSettlementVersion(),
                settlement.getSupersedesSettlementVersion(),
                settlement.getSettlementStatus(),
                settlement.getMatchFactId(),
                settlement.getRuleVersion(),
                Boolean.TRUE.equals(settlement.getIsCurrent()),
                settlement.getCreatedAt()
        );
    }

    private static Map<Long, Prediction> indexPredictionsById(Collection<Prediction> predictions) {
        return predictions.stream().collect(Collectors.toMap(Prediction::getId, Function.identity()));
    }

    private static Map<Long, MatchEntity> indexMatchesById(Collection<MatchEntity> matches) {
        return matches.stream().collect(Collectors.toMap(MatchEntity::getId, Function.identity()));
    }

    private static Map<Long, List<MatchResultFact>> groupByMatch(Collection<MatchResultFact> facts) {
        Map<Long, List<MatchResultFact>> grouped = new HashMap<>();
        for (MatchResultFact fact : facts) {
            grouped.computeIfAbsent(fact.getMatchId(), unused -> new ArrayList<>()).add(fact);
        }
        return grouped;
    }
}
