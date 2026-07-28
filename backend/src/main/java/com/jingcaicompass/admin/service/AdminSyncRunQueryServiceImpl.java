package com.jingcaicompass.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jingcaicompass.admin.dto.AdminSyncRunDetailQueryDto;
import com.jingcaicompass.admin.dto.AdminSyncRunErrorQueryDto;
import com.jingcaicompass.admin.dto.AdminSyncRunListQueryDto;
import com.jingcaicompass.admin.dto.AdminSyncRunQuotaSummaryQueryDto;
import com.jingcaicompass.admin.vo.AdminRawPayloadSnippetVo;
import com.jingcaicompass.admin.vo.AdminSyncRunDetailVo;
import com.jingcaicompass.admin.vo.AdminSyncRunErrorVo;
import com.jingcaicompass.admin.vo.AdminSyncRunListItemVo;
import com.jingcaicompass.admin.vo.AdminSyncRunQuotaItemVo;
import com.jingcaicompass.admin.vo.AdminSyncRunQuotaSummaryVo;
import com.jingcaicompass.data.entity.DataSyncRun;
import com.jingcaicompass.data.entity.RawDataPayload;
import com.jingcaicompass.data.enums.ProviderDataTypeEnum;
import com.jingcaicompass.data.enums.SyncStatusEnum;
import com.jingcaicompass.data.mapper.DataSyncRunMapper;
import com.jingcaicompass.data.mapper.RawDataPayloadMapper;
import com.jingcaicompass.match.client.SportteryProviderProperties;
import com.jingcaicompass.odds.client.AsianOddsProviderProperties;
import com.jingcaicompass.system.api.PageResult;
import com.jingcaicompass.system.config.properties.PaginationProperties;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 以持久化运行与载荷事实提供后台只读观测，不调用外部 Provider。 */
@Service
@ConditionalOnBean(DataSource.class)
public class AdminSyncRunQueryServiceImpl implements AdminSyncRunQueryService {

    static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final DataSyncRunMapper dataSyncRunMapper;
    private final RawDataPayloadMapper rawDataPayloadMapper;
    private final PaginationProperties paginationProperties;
    private final SportteryProviderProperties sportteryProperties;
    private final AsianOddsProviderProperties asianOddsProperties;
    private final AdminSensitiveDataSanitizer sanitizer;
    private final Clock clock;

    public AdminSyncRunQueryServiceImpl(
            DataSyncRunMapper dataSyncRunMapper,
            RawDataPayloadMapper rawDataPayloadMapper,
            PaginationProperties paginationProperties,
            SportteryProviderProperties sportteryProperties,
            AsianOddsProviderProperties asianOddsProperties,
            AdminSensitiveDataSanitizer sanitizer,
            Clock clock
    ) {
        this.dataSyncRunMapper = dataSyncRunMapper;
        this.rawDataPayloadMapper = rawDataPayloadMapper;
        this.paginationProperties = paginationProperties;
        this.sportteryProperties = sportteryProperties;
        this.asianOddsProperties = asianOddsProperties;
        this.sanitizer = sanitizer;
        this.clock = clock;
    }

    @Override
    public PageResult<AdminSyncRunListItemVo> list(AdminSyncRunListQueryDto query) {
        Page<DataSyncRun> page = dataSyncRunMapper.selectPage(
                new Page<>(pageNo(query == null ? null : query.pageNo()), pageSize(query == null ? null : query.pageSize())),
                runQuery(query)
        );
        return new PageResult<>(page.getRecords().stream().map(this::toListItem).toList(),
                page.getCurrent(), page.getSize(), page.getTotal());
    }

    @Override
    public AdminSyncRunDetailVo detail(AdminSyncRunDetailQueryDto query) {
        Objects.requireNonNull(query, "query must not be null");
        DataSyncRun run = dataSyncRunMapper.selectById(query.syncRunId());
        if (run == null) {
            throw new BusinessException(ErrorCode.SYNC_RUN_NOT_FOUND);
        }

        // 1) 只通过 V13 精确关联读取载荷，绝不按时间窗口猜测
        List<AdminRawPayloadSnippetVo> payloads = rawDataPayloadMapper.selectBySyncRunId(run.getId()).stream()
                .map(this::toPayloadSnippet)
                .toList();
        String notice = payloads.isEmpty()
                ? "该运行没有精确关联的原始响应；V13 前的历史记录不会按时间窗口推断。"
                : null;
        return new AdminSyncRunDetailVo(toListItem(run), payloads, notice);
    }

    @Override
    public PageResult<AdminSyncRunErrorVo> errors(AdminSyncRunErrorQueryDto query) {
        AdminSyncRunListQueryDto listQuery = new AdminSyncRunListQueryDto(
                query == null ? null : query.providerCode(),
                query == null ? null : query.dataType(),
                List.of(SyncStatusEnum.FAILED, SyncStatusEnum.PARTIAL),
                query == null ? null : query.pageNo(),
                query == null ? null : query.pageSize()
        );
        Page<DataSyncRun> page = dataSyncRunMapper.selectPage(
                new Page<>(pageNo(listQuery.pageNo()), pageSize(listQuery.pageSize())),
                runQuery(listQuery)
        );
        List<AdminSyncRunErrorVo> records = page.getRecords().stream().map(run -> new AdminSyncRunErrorVo(
                run.getId(), run.getProviderCode(), run.getDataType(), run.getSyncStatus(),
                run.getStartedAt(), run.getFinishedAt(), run.getFailureCount(), run.getRetryCount(),
                sanitizer.sanitizeText(run.getErrorMessage())
        )).toList();
        return new PageResult<>(records, page.getCurrent(), page.getSize(), page.getTotal());
    }

    @Override
    public AdminSyncRunQuotaSummaryVo quotaSummary(AdminSyncRunQuotaSummaryQueryDto query) {
        LocalDate businessDate = query == null || query.businessDate() == null
                ? LocalDate.now(clock.withZone(SHANGHAI))
                : query.businessDate();
        Instant from = businessDate.atStartOfDay(SHANGHAI).toInstant();
        Instant to = businessDate.plusDays(1).atStartOfDay(SHANGHAI).toInstant();
        List<DataSyncRun> runs = dataSyncRunMapper.selectList(new LambdaQueryWrapper<DataSyncRun>()
                .ge(DataSyncRun::getStartedAt, from)
                .lt(DataSyncRun::getStartedAt, to));
        Map<QuotaKey, QuotaAccumulator> totals = new LinkedHashMap<>();
        for (DataSyncRun run : runs) {
            QuotaKey key = new QuotaKey(run.getProviderCode(), run.getDataType());
            totals.computeIfAbsent(key, ignored -> new QuotaAccumulator()).add(run.getQuotaCost());
        }
        List<AdminSyncRunQuotaItemVo> items = totals.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<QuotaKey, QuotaAccumulator> entry) -> entry.getKey().providerCode)
                        .thenComparing(entry -> entry.getKey().dataType.name()))
                .map(entry -> toQuotaItem(entry.getKey(), entry.getValue()))
                .toList();
        return new AdminSyncRunQuotaSummaryVo(businessDate, Instant.now(clock), items);
    }

    private LambdaQueryWrapper<DataSyncRun> runQuery(AdminSyncRunListQueryDto query) {
        LambdaQueryWrapper<DataSyncRun> wrapper = new LambdaQueryWrapper<DataSyncRun>()
                .orderByDesc(DataSyncRun::getStartedAt)
                .orderByDesc(DataSyncRun::getId);
        if (query == null) {
            return wrapper;
        }
        if (StringUtils.hasText(query.providerCode())) {
            wrapper.eq(DataSyncRun::getProviderCode, query.providerCode().trim());
        }
        if (query.dataType() != null) {
            wrapper.eq(DataSyncRun::getDataType, query.dataType());
        }
        if (query.syncStatuses() != null && !query.syncStatuses().isEmpty()) {
            wrapper.in(DataSyncRun::getSyncStatus, query.syncStatuses());
        }
        return wrapper;
    }

    private int pageNo(Integer requested) {
        return requested == null || requested < 1 ? 1 : requested;
    }

    private long pageSize(Integer requested) {
        long value = requested == null || requested < 1 ? 20 : requested;
        return Math.min(value, paginationProperties.maxPageSize());
    }

    private AdminSyncRunListItemVo toListItem(DataSyncRun run) {
        return new AdminSyncRunListItemVo(
                run.getId(), run.getProviderCode(), run.getDataType(), run.getSyncStatus(),
                run.getStartedAt(), run.getFinishedAt(), run.getFetchedCount(), run.getSuccessCount(),
                run.getFailureCount(), run.getRetryCount(), run.getQuotaCost(),
                sanitizer.sanitizeText(run.getErrorMessage())
        );
    }

    private AdminRawPayloadSnippetVo toPayloadSnippet(RawDataPayload payload) {
        AdminSensitiveDataSanitizer.SanitizedText fragment = sanitizer.sanitizePayload(payload.getPayload());
        return new AdminRawPayloadSnippetVo(
                payload.getId(), sanitizer.sanitizeText(payload.getRequestKey()), payload.getRequestedAt(),
                payload.getProviderUpdatedAt(), payload.getHttpStatus(), payload.getPayloadHash(),
                payload.getParseStatus(), sanitizer.sanitizeText(payload.getParseError()), fragment.value(), fragment.truncated()
        );
    }

    private AdminSyncRunQuotaItemVo toQuotaItem(QuotaKey key, QuotaAccumulator total) {
        Integer threshold = warningThreshold(key.dataType);
        return new AdminSyncRunQuotaItemVo(
                key.providerCode, key.dataType, total.runCount, total.consumedQuota,
                threshold, threshold != null && total.consumedQuota >= threshold
        );
    }

    private Integer warningThreshold(ProviderDataTypeEnum dataType) {
        int value = switch (dataType) {
            case SPORTTERY_POOL, SPORTTERY_RESULT -> sportteryProperties.quotaWarningThreshold();
            case ASIAN_ODDS -> asianOddsProperties.quotaWarningThreshold();
            case OTHER -> 0;
        };
        return value > 0 ? value : null;
    }

    private record QuotaKey(String providerCode, ProviderDataTypeEnum dataType) {
    }

    private static final class QuotaAccumulator {
        private long runCount;
        private long consumedQuota;

        void add(Integer quotaCost) {
            runCount++;
            consumedQuota += quotaCost == null ? 0 : Math.max(quotaCost, 0);
        }
    }
}
