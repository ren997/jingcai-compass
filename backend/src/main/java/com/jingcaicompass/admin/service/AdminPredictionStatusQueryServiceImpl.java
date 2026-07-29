package com.jingcaicompass.admin.service;

import com.jingcaicompass.admin.dto.AdminPredictionLockListQueryDto;
import com.jingcaicompass.admin.dto.AdminPredictionStatusDetailQueryDto;
import com.jingcaicompass.admin.dto.AdminSettlementStatusListQueryDto;
import com.jingcaicompass.admin.enums.AdminPredictionLockDiagnosticEnum;
import com.jingcaicompass.admin.enums.AdminSettlementDiagnosticEnum;
import com.jingcaicompass.admin.mapper.AdminPredictionStatusCriteria;
import com.jingcaicompass.admin.mapper.AdminPredictionStatusMapper;
import com.jingcaicompass.admin.vo.AdminPredictionMatchVo;
import com.jingcaicompass.admin.vo.AdminPredictionStatusDetailVo;
import com.jingcaicompass.admin.vo.AdminPredictionStatusItemVo;
import com.jingcaicompass.admin.vo.AdminPredictionStatusPageVo;
import com.jingcaicompass.admin.vo.AdminResultFactVo;
import com.jingcaicompass.admin.vo.AdminSettlementMarketHistoryVo;
import com.jingcaicompass.admin.vo.AdminSettlementMarketVo;
import com.jingcaicompass.admin.vo.AdminSettlementVersionVo;
import com.jingcaicompass.admin.vo.AdminStatusDiagnosticVo;
import com.jingcaicompass.history.service.HistoryRecordAssembler;
import com.jingcaicompass.history.vo.HistoryListItemVo;
import com.jingcaicompass.history.vo.MarketSettlementHistoryVo;
import com.jingcaicompass.history.vo.MatchResultFactHistoryVo;
import com.jingcaicompass.history.vo.SettlementVersionVo;
import com.jingcaicompass.match.enums.MatchResultFactStatusEnum;
import com.jingcaicompass.prediction.enums.PredictionStatusEnum;
import com.jingcaicompass.settlement.enums.MarketTypeEnum;
import com.jingcaicompass.settlement.enums.SettlementStatusEnum;
import com.jingcaicompass.system.config.properties.PaginationProperties;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 从持久化预测、赛果事实和结算版本构建管理员只读状态视图。 */
@Service
@ConditionalOnBean(DataSource.class)
public class AdminPredictionStatusQueryServiceImpl implements AdminPredictionStatusQueryService {

    private static final List<PredictionStatusEnum> OPERATIONAL_STATUSES = List.of(
            PredictionStatusEnum.PUBLISHED, PredictionStatusEnum.LOCKED
    );
    private static final List<AdminSettlementDiagnosticEnum> DEFAULT_SETTLEMENT_DIAGNOSTICS = List.of(
            AdminSettlementDiagnosticEnum.AWAITING_RESULT,
            AdminSettlementDiagnosticEnum.SETTLEMENT_MISSING_HAD,
            AdminSettlementDiagnosticEnum.SETTLEMENT_MISSING_HHAD,
            AdminSettlementDiagnosticEnum.SETTLEMENT_STALE_HAD,
            AdminSettlementDiagnosticEnum.SETTLEMENT_STALE_HHAD
    );

    private final AdminPredictionStatusMapper statusMapper;
    private final HistoryRecordAssembler historyRecordAssembler;
    private final PaginationProperties paginationProperties;

    public AdminPredictionStatusQueryServiceImpl(
            AdminPredictionStatusMapper statusMapper,
            HistoryRecordAssembler historyRecordAssembler,
            PaginationProperties paginationProperties
    ) {
        this.statusMapper = statusMapper;
        this.historyRecordAssembler = historyRecordAssembler;
        this.paginationProperties = paginationProperties;
    }

    @Override
    public AdminPredictionStatusPageVo locks(AdminPredictionLockListQueryDto query) {
        // 1) 使用数据库时间固定到期判断，避免应用节点时钟影响锁定诊断。
        int pageNo = pageNo(query == null ? null : query.pageNo());
        int pageSize = pageSize(query == null ? null : query.pageSize());
        Instant referenceTime = statusMapper.selectDatabaseTime();
        AdminPredictionStatusCriteria criteria = new AdminPredictionStatusCriteria(
                query == null ? null : query.lotteryDate(), normalizedModelVersion(query == null ? null : query.modelVersion()),
                operationalStatuses(query == null ? null : query.predictionStatuses()),
                distinct(query == null ? null : query.lockDiagnostics()), List.of(), pageSize,
                (long) (pageNo - 1) * pageSize, referenceTime
        );

        // 2) 先按 SQL 固定分页，再批量装配事实、结算和历史版本，避免 N+1。
        List<Long> predictionIds = statusMapper.selectLockPredictionIds(criteria);
        List<AdminPredictionStatusItemVo> records = assemble(predictionIds, referenceTime);
        return new AdminPredictionStatusPageVo(records, pageNo, pageSize,
                statusMapper.countLockPredictions(criteria), statusMapper.countOverdueLocks(criteria));
    }

    @Override
    public AdminPredictionStatusPageVo settlements(AdminSettlementStatusListQueryDto query) {
        // 1) 默认只显示待赛果、缺失结算和引用过期事实的运营项。
        int pageNo = pageNo(query == null ? null : query.pageNo());
        int pageSize = pageSize(query == null ? null : query.pageSize());
        Instant referenceTime = statusMapper.selectDatabaseTime();
        AdminPredictionStatusCriteria criteria = new AdminPredictionStatusCriteria(
                query == null ? null : query.lotteryDate(), normalizedModelVersion(query == null ? null : query.modelVersion()),
                OPERATIONAL_STATUSES, List.of(), settlementDiagnostics(query == null ? null : query.diagnostics()), pageSize,
                (long) (pageNo - 1) * pageSize, referenceTime
        );

        // 2) 使用当前事实与当前市场结算的关联查询确定积压和需重算项。
        List<Long> predictionIds = statusMapper.selectSettlementPredictionIds(criteria);
        List<AdminPredictionStatusItemVo> records = assemble(predictionIds, referenceTime);
        return new AdminPredictionStatusPageVo(records, pageNo, pageSize,
                statusMapper.countSettlementPredictions(criteria), statusMapper.countManualSettlementAttention(criteria));
    }

    @Override
    public AdminPredictionStatusDetailVo detail(AdminPredictionStatusDetailQueryDto query) {
        Objects.requireNonNull(query, "query must not be null");
        if (statusMapper.selectOperationalPredictionId(query.predictionId()) == null) {
            throw new BusinessException(ErrorCode.PREDICTION_NOT_FOUND);
        }

        // 1) 读取当前数据库时间和完整不可变历史，草稿从运营入口隔离。
        Instant referenceTime = statusMapper.selectDatabaseTime();
        HistoryListItemVo item = historyRecordAssembler.assemble(List.of(query.predictionId())).stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.PREDICTION_NOT_FOUND));

        // 2) 同时返回当前投影和版本链，页面不会将历史记录伪装成当前状态。
        return new AdminPredictionStatusDetailVo(
                toItem(item, referenceTime),
                item.resultFacts().stream().map(this::toFact).toList(),
                item.settlementMarkets().stream().map(market -> toMarketHistory(market, currentFact(item))).toList()
        );
    }

    private List<AdminPredictionStatusItemVo> assemble(List<Long> predictionIds, Instant referenceTime) {
        return historyRecordAssembler.assemble(predictionIds).stream()
                .map(item -> toItem(item, referenceTime))
                .toList();
    }

    private AdminPredictionStatusItemVo toItem(HistoryListItemVo item, Instant referenceTime) {
        MatchResultFactHistoryVo currentFact = currentFact(item);
        Map<MarketTypeEnum, MarketSettlementHistoryVo> markets = marketsByType(item.settlementMarkets());
        AdminSettlementMarketVo had = toMarket(markets.get(MarketTypeEnum.HAD), MarketTypeEnum.HAD, currentFact);
        AdminSettlementMarketVo hhad = toMarket(markets.get(MarketTypeEnum.HHAD), MarketTypeEnum.HHAD, currentFact);
        return new AdminPredictionStatusItemVo(
                item.predictionId(), item.modelVersion(), item.featureVersion(), item.predictionVersion(), item.predictionStatus(),
                item.publishTime(), item.lockTime(), item.predictionHash(),
                new AdminPredictionMatchVo(item.match().matchId(), item.match().lotteryDate(), item.match().lotteryMatchNo(),
                        item.match().leagueName(), item.match().homeTeamName(), item.match().awayTeamName(), item.match().kickoffTime()),
                lockDiagnostics(item, referenceTime), currentFact == null ? null : toFact(currentFact), had, hhad,
                settlementDiagnostics(currentFact, had, hhad)
        );
    }

    private List<AdminStatusDiagnosticVo> lockDiagnostics(HistoryListItemVo item, Instant referenceTime) {
        AdminPredictionLockDiagnosticEnum diagnostic = item.predictionStatus() == PredictionStatusEnum.LOCKED
                ? AdminPredictionLockDiagnosticEnum.LOCKED
                : item.lockTime() != null && !item.lockTime().isAfter(referenceTime)
                        ? AdminPredictionLockDiagnosticEnum.OVERDUE
                        : AdminPredictionLockDiagnosticEnum.SCHEDULED;
        return List.of(diagnostic(diagnostic.getCode(), diagnostic.getDesc()));
    }

    private List<AdminStatusDiagnosticVo> settlementDiagnostics(
            MatchResultFactHistoryVo currentFact,
            AdminSettlementMarketVo had,
            AdminSettlementMarketVo hhad
    ) {
        if (!isConfirmed(currentFact)) {
            return List.of(diagnostic(
                    AdminSettlementDiagnosticEnum.AWAITING_RESULT.getCode(),
                    AdminSettlementDiagnosticEnum.AWAITING_RESULT.getDesc()
            ));
        }
        java.util.ArrayList<AdminStatusDiagnosticVo> result = new java.util.ArrayList<>();
        addMarketDiagnostics(result, had, AdminSettlementDiagnosticEnum.SETTLEMENT_MISSING_HAD,
                AdminSettlementDiagnosticEnum.SETTLEMENT_STALE_HAD);
        addMarketDiagnostics(result, hhad, AdminSettlementDiagnosticEnum.SETTLEMENT_MISSING_HHAD,
                AdminSettlementDiagnosticEnum.SETTLEMENT_STALE_HHAD);
        return List.copyOf(result);
    }

    private void addMarketDiagnostics(
            Collection<AdminStatusDiagnosticVo> target,
            AdminSettlementMarketVo market,
            AdminSettlementDiagnosticEnum missing,
            AdminSettlementDiagnosticEnum stale
    ) {
        if (!market.currentSettlementPersisted()) {
            target.add(diagnostic(missing.getCode(), missing.getDesc()));
        } else if (market.stale()) {
            target.add(diagnostic(stale.getCode(), stale.getDesc()));
        }
    }

    private AdminSettlementMarketVo toMarket(
            MarketSettlementHistoryVo market,
            MarketTypeEnum fallbackMarketType,
            MatchResultFactHistoryVo currentFact
    ) {
        if (market == null || !market.currentSettlementPersisted()) {
            return new AdminSettlementMarketVo(
                    market == null ? fallbackMarketType : market.marketType(), SettlementStatusEnum.PENDING,
                    false, null, null, null, null, false
            );
        }
        SettlementVersionVo current = market.versions().stream().filter(SettlementVersionVo::current).findFirst()
                .orElseThrow(() -> new IllegalStateException("current settlement flag has no version"));
        boolean stale = isConfirmed(currentFact) && !Objects.equals(current.matchFactId(), currentFact.factId());
        return new AdminSettlementMarketVo(market.marketType(), current.settlementStatus(), true,
                current.settlementId(), current.settlementVersion(), current.matchFactId(), current.ruleVersion(), stale);
    }

    private AdminSettlementMarketHistoryVo toMarketHistory(
            MarketSettlementHistoryVo market,
            MatchResultFactHistoryVo currentFact
    ) {
        AdminSettlementMarketVo current = toMarket(market, market.marketType(), currentFact);
        return new AdminSettlementMarketHistoryVo(
                market.marketType(), current.currentStatus(), current.currentSettlementPersisted(), current.stale(),
                market.versions().stream().map(this::toSettlementVersion).toList()
        );
    }

    private AdminSettlementVersionVo toSettlementVersion(SettlementVersionVo settlement) {
        return new AdminSettlementVersionVo(
                settlement.settlementId(), settlement.settlementVersion(), settlement.supersedesSettlementVersion(),
                settlement.settlementStatus(), settlement.matchFactId(), settlement.ruleVersion(), settlement.current(), settlement.createdAt()
        );
    }

    private AdminResultFactVo toFact(MatchResultFactHistoryVo fact) {
        return new AdminResultFactVo(
                fact.factId(), fact.factVersion(), fact.supersedesFactVersion(), fact.factStatus(), fact.matchStatus(),
                fact.homeScore(), fact.awayScore(), fact.providerUpdatedAt(), fact.current(), fact.createdAt()
        );
    }

    private MatchResultFactHistoryVo currentFact(HistoryListItemVo item) {
        return item.resultFacts().stream().filter(MatchResultFactHistoryVo::current).findFirst().orElse(null);
    }

    private Map<MarketTypeEnum, MarketSettlementHistoryVo> marketsByType(List<MarketSettlementHistoryVo> markets) {
        Map<MarketTypeEnum, MarketSettlementHistoryVo> result = new EnumMap<>(MarketTypeEnum.class);
        for (MarketSettlementHistoryVo market : markets) {
            result.put(market.marketType(), market);
        }
        return result;
    }

    private boolean isConfirmed(MatchResultFactHistoryVo fact) {
        return fact != null && (fact.factStatus() == MatchResultFactStatusEnum.FINAL
                || fact.factStatus() == MatchResultFactStatusEnum.VOID);
    }

    private AdminStatusDiagnosticVo diagnostic(String code, String description) {
        return new AdminStatusDiagnosticVo(code, description);
    }

    private List<PredictionStatusEnum> operationalStatuses(List<PredictionStatusEnum> requested) {
        List<PredictionStatusEnum> normalized = distinct(requested).stream()
                .filter(OPERATIONAL_STATUSES::contains)
                .toList();
        return normalized.isEmpty() ? OPERATIONAL_STATUSES : normalized;
    }

    private List<AdminSettlementDiagnosticEnum> settlementDiagnostics(List<AdminSettlementDiagnosticEnum> requested) {
        List<AdminSettlementDiagnosticEnum> normalized = distinct(requested);
        return normalized.isEmpty() ? DEFAULT_SETTLEMENT_DIAGNOSTICS : normalized;
    }

    private <T> List<T> distinct(List<T> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).distinct().toList();
    }

    private String normalizedModelVersion(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private int pageNo(Integer requested) {
        return requested == null || requested < 1 ? 1 : requested;
    }

    private int pageSize(Integer requested) {
        int value = requested == null || requested < 1 ? 20 : requested;
        return (int) Math.min(value, paginationProperties.maxPageSize());
    }
}
