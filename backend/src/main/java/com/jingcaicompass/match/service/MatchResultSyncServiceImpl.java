package com.jingcaicompass.match.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.data.dto.ProviderParseResult;
import com.jingcaicompass.data.dto.ProviderSyncOutcome;
import com.jingcaicompass.data.enums.ProviderDataTypeEnum;
import com.jingcaicompass.data.service.ProviderSyncTemplate;
import com.jingcaicompass.match.dto.MatchResultSyncRequestDto;
import com.jingcaicompass.match.dto.MatchResultSyncResultDto;
import com.jingcaicompass.match.dto.SportteryMatchResultDto;
import com.jingcaicompass.match.exception.SportteryDataAccessException;
import com.jingcaicompass.system.provider.ProviderErrorCategory;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

/** 体彩赛果同步：raw 存档后逐条追加权威事实与当前投影。 */
@Service
@ConditionalOnBean(DataSource.class)
public class MatchResultSyncServiceImpl implements MatchResultSyncService {

    private final SportteryProvider sportteryProvider;
    private final ProviderSyncTemplate providerSyncTemplate;
    private final SportteryMatchResultPayloadMapper payloadMapper;
    private final MatchResultFactWriter factWriter;
    private final ObjectMapper objectMapper;

    public MatchResultSyncServiceImpl(
            SportteryProvider sportteryProvider,
            ProviderSyncTemplate providerSyncTemplate,
            SportteryMatchResultPayloadMapper payloadMapper,
            MatchResultFactWriter factWriter,
            ObjectMapper objectMapper
    ) {
        this.sportteryProvider = sportteryProvider;
        this.providerSyncTemplate = providerSyncTemplate;
        this.payloadMapper = payloadMapper;
        this.factWriter = factWriter;
        this.objectMapper = objectMapper;
    }

    @Override
    public MatchResultSyncResultDto sync(MatchResultSyncRequestDto request) {
        LocalDate startDate = requireStartDate(request);
        LocalDate endDate = requireEndDate(request, startDate);
        SyncCounters counters = new SyncCounters();

        // 1) 模板负责同步运行和原始响应存档。
        ProviderSyncOutcome outcome = providerSyncTemplate.execute(
                sportteryProvider.providerCode(),
                ProviderDataTypeEnum.SPORTTERY_RESULT,
                () -> sportteryProvider.fetchMatchResultsRaw(startDate, endDate),
                (dataType, requestKey, payload) -> {
                    // 2) 从已存档 raw 解析，并将每条赛果交给独立事务写入器。
                    List<SportteryMatchResultDto> items = payloadMapper.parseItems(toJson(payload.getPayload()));
                    return writeItems(latestItemsByLotteryIdentity(items), payload.getId(), counters);
                }
        );

        return new MatchResultSyncResultDto(
                outcome,
                counters.appendedFactCount,
                counters.supersededFactCount,
                counters.unchangedFactCount
        );
    }

    private ProviderParseResult writeItems(
            List<SportteryMatchResultDto> items,
            Long rawDataPayloadId,
            SyncCounters counters
    ) {
        if (rawDataPayloadId == null) {
            throw new IllegalStateException("saved SPORTTERY_RESULT payload must have an id");
        }
        if (items.isEmpty()) {
            return ProviderParseResult.empty();
        }

        int successCount = 0;
        int failureCount = 0;
        List<String> errors = new ArrayList<>();
        for (SportteryMatchResultDto item : items) {
            try {
                MatchResultFactWriter.WriteResult result = factWriter.write(item, rawDataPayloadId);
                switch (result.outcome()) {
                    case APPENDED -> counters.appendedFactCount++;
                    case SUPERSEDED -> {
                        counters.appendedFactCount++;
                        counters.supersededFactCount++;
                    }
                    case UNCHANGED -> counters.unchangedFactCount++;
                }
                successCount++;
            } catch (RuntimeException exception) {
                failureCount++;
                errors.add(summarize(item, exception));
            }
        }
        return new ProviderParseResult(successCount, failureCount, truncate(String.join("; ", errors)));
    }

    /**
     * 同一 raw 响应偶尔会同时含同场的旧版与修正版；只消费供应商时间较新的有效事实。
     * 缺少业务键或更新时间的异常条目仍保留给单条写入器报告失败。
     */
    private List<SportteryMatchResultDto> latestItemsByLotteryIdentity(List<SportteryMatchResultDto> items) {
        Map<String, SportteryMatchResultDto> latestByIdentity = new LinkedHashMap<>();
        List<SportteryMatchResultDto> invalidItems = new ArrayList<>();
        for (SportteryMatchResultDto item : items) {
            if (item == null
                    || item.lotteryDate() == null
                    || !org.springframework.util.StringUtils.hasText(item.lotteryMatchNo())
                    || item.providerUpdatedAt() == null) {
                invalidItems.add(item);
                continue;
            }
            String identity = item.lotteryDate() + "/" + item.lotteryMatchNo();
            SportteryMatchResultDto current = latestByIdentity.get(identity);
            if (current == null || item.providerUpdatedAt().isAfter(current.providerUpdatedAt())) {
                latestByIdentity.put(identity, item);
            }
        }
        List<SportteryMatchResultDto> effectiveItems = new ArrayList<>(latestByIdentity.values());
        effectiveItems.addAll(invalidItems);
        return List.copyOf(effectiveItems);
    }

    private LocalDate requireStartDate(MatchResultSyncRequestDto request) {
        if (request == null || request.startDate() == null) {
            throw new IllegalArgumentException("startDate must not be null");
        }
        return request.startDate();
    }

    private LocalDate requireEndDate(MatchResultSyncRequestDto request, LocalDate startDate) {
        if (request.endDate() == null) {
            throw new IllegalArgumentException("endDate must not be null");
        }
        if (request.endDate().isBefore(startDate)) {
            throw new IllegalArgumentException("endDate must not be before startDate");
        }
        return request.endDate();
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new SportteryDataAccessException(
                    ProviderErrorCategory.PARSE_FAILURE,
                    "无法序列化原始体彩赛果载荷",
                    exception
            );
        }
    }

    private String summarize(SportteryMatchResultDto item, RuntimeException exception) {
        String matchNo = item == null || item.lotteryMatchNo() == null ? "?" : item.lotteryMatchNo();
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        return matchNo + ":" + message;
    }

    private String truncate(String message) {
        if (message == null || message.length() <= 500) {
            return message;
        }
        return message.substring(0, 500);
    }

    private static final class SyncCounters {

        private int appendedFactCount;
        private int supersededFactCount;
        private int unchangedFactCount;
    }
}
