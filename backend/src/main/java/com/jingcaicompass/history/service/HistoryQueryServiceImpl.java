package com.jingcaicompass.history.service;

import com.jingcaicompass.history.dto.HistoryListQueryDto;
import com.jingcaicompass.history.dto.HistoryQueryCriteriaDto;
import com.jingcaicompass.history.mapper.HistoryQueryMapper;
import com.jingcaicompass.history.vo.HistoryListItemVo;
import com.jingcaicompass.prediction.enums.PredictionTypeEnum;
import com.jingcaicompass.settlement.enums.MarketTypeEnum;
import com.jingcaicompass.settlement.enums.SettlementStatusEnum;
import com.jingcaicompass.system.api.PageResult;
import com.jingcaicompass.system.config.properties.PaginationProperties;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 公共历史分页：先筛选稳定 ID 顺序，再批量装配不可变版本链。 */
@Service
@ConditionalOnBean(DataSource.class)
public class HistoryQueryServiceImpl implements HistoryQueryService {

    private final HistoryQueryMapper historyQueryMapper;
    private final HistoryRecordAssembler historyRecordAssembler;
    private final PaginationProperties paginationProperties;

    public HistoryQueryServiceImpl(
            HistoryQueryMapper historyQueryMapper,
            HistoryRecordAssembler historyRecordAssembler,
            PaginationProperties paginationProperties
    ) {
        this.historyQueryMapper = historyQueryMapper;
        this.historyRecordAssembler = historyRecordAssembler;
        this.paginationProperties = paginationProperties;
    }

    @Override
    public PageResult<HistoryListItemVo> list(HistoryListQueryDto query) {
        // 1) 归一化筛选与分页，禁止调用方控制排序字段。
        HistoryQueryCriteriaDto criteria = normalize(query);
        long total = historyQueryMapper.countPredictionIds(criteria);
        List<Long> predictionIds = historyQueryMapper.selectPagePredictionIds(criteria);

        // 2) 按数据库返回的稳定顺序装配预测、事实、结算和修正审计。
        return new PageResult<>(
                historyRecordAssembler.assemble(predictionIds),
                criteria.offset() / criteria.pageSize() + 1,
                criteria.pageSize(),
                total
        );
    }

    private HistoryQueryCriteriaDto normalize(HistoryListQueryDto query) {
        if (query != null && query.startDate() != null && query.endDate() != null
                && query.startDate().isAfter(query.endDate())) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "startDate must not be after endDate");
        }
        int pageNo = query == null || query.pageNo() == null || query.pageNo() < 1 ? 1 : query.pageNo();
        long requestedSize = query == null || query.pageSize() == null || query.pageSize() < 1
                ? 20
                : query.pageSize();
        long pageSize = Math.min(requestedSize, paginationProperties.maxPageSize());
        Set<SettlementStatusEnum> requestedStatuses = query == null || query.settlementStatuses() == null
                ? Set.of()
                : Set.copyOf(query.settlementStatuses());
        Set<SettlementStatusEnum> persistedStatuses = requestedStatuses.stream()
                .filter(status -> status != SettlementStatusEnum.PENDING)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        PredictionTypeEnum predictionType = normalizePredictionType(query);
        return new HistoryQueryCriteriaDto(
                query == null ? null : query.startDate(),
                query == null ? null : query.endDate(),
                query == null ? null : query.leagueId(),
                query == null || !StringUtils.hasText(query.modelVersion()) ? null : query.modelVersion().trim(),
                query != null && Boolean.TRUE.equals(query.lockedOnly()),
                resolveSettlementMarket(query, predictionType),
                !requestedStatuses.isEmpty(),
                requestedStatuses.contains(SettlementStatusEnum.PENDING),
                persistedStatuses,
                pageSize,
                (long) (pageNo - 1) * pageSize,
                predictionType
        );
    }

    /**
     * 解析产品线：显式给出时以显式为准，否则由市场反推。
     *
     * <p>只看市场不看类型就会把「按亚盘让球筛结算状态」和「列出体彩预测」两件事混在一起，
     * 筛出来的条目没有对应的结算行，看起来就是一条永远待结算的记录。
     */
    private PredictionTypeEnum normalizePredictionType(HistoryListQueryDto query) {
        if (query == null) {
            return PredictionTypeEnum.SPORTTERY;
        }
        if (query.predictionType() != null) {
            return query.predictionType();
        }
        return query.settlementMarket() != null && query.settlementMarket().isAsian()
                ? PredictionTypeEnum.ASIAN
                : PredictionTypeEnum.SPORTTERY;
    }

    /**
     * 确定结算市场：缺省取该类预测的第一个市场，显式给出时必须是该类预测的市场之一。
     *
     * <p>跨产品线的市场组合直接拒绝：它必然查出空集，而空集在页面上与「这段时间没有预测」
     * 无法区分，比报错更难排查。
     */
    private MarketTypeEnum resolveSettlementMarket(HistoryListQueryDto query, PredictionTypeEnum predictionType) {
        List<MarketTypeEnum> allowed = MarketTypeEnum.marketsFor(predictionType);
        MarketTypeEnum requested = query == null ? null : query.settlementMarket();
        if (requested == null) {
            return allowed.get(0);
        }
        if (!allowed.contains(requested)) {
            throw new BusinessException(
                    ErrorCode.INVALID_PARAMETER,
                    "settlementMarket " + requested.getCode() + " does not belong to " + predictionType
            );
        }
        return requested;
    }
}
