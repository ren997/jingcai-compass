package com.jingcaicompass.history.service;

import com.jingcaicompass.history.dto.HistoryListQueryDto;
import com.jingcaicompass.history.dto.HistoryQueryCriteriaDto;
import com.jingcaicompass.history.mapper.HistoryQueryMapper;
import com.jingcaicompass.history.vo.HistoryListItemVo;
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
        return new HistoryQueryCriteriaDto(
                query == null ? null : query.startDate(),
                query == null ? null : query.endDate(),
                query == null ? null : query.leagueId(),
                query == null || !StringUtils.hasText(query.modelVersion()) ? null : query.modelVersion().trim(),
                query != null && Boolean.TRUE.equals(query.lockedOnly()),
                query == null || query.settlementMarket() == null ? MarketTypeEnum.HAD : query.settlementMarket(),
                !requestedStatuses.isEmpty(),
                requestedStatuses.contains(SettlementStatusEnum.PENDING),
                persistedStatuses,
                pageSize,
                (long) (pageNo - 1) * pageSize
        );
    }
}
