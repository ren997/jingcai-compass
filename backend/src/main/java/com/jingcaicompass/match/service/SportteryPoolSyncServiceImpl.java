package com.jingcaicompass.match.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.data.dto.ProviderSyncOutcome;
import com.jingcaicompass.data.enums.ProviderDataTypeEnum;
import com.jingcaicompass.data.service.ProviderSyncTemplate;
import com.jingcaicompass.match.dto.SportteryPoolSyncItemDto;
import com.jingcaicompass.match.dto.SportteryPoolSyncRequestDto;
import com.jingcaicompass.match.dto.SportteryPoolSyncResultDto;
import com.jingcaicompass.match.exception.SportteryDataAccessException;
import com.jingcaicompass.system.provider.ProviderErrorCategory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@ConditionalOnBean(DataSource.class)
public class SportteryPoolSyncServiceImpl implements SportteryPoolSyncService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final SportteryProvider sportteryProvider;
    private final ProviderSyncTemplate providerSyncTemplate;
    private final SportteryPoolPayloadMapper payloadMapper;
    private final SportteryPoolMatchWriter matchWriter;
    private final ObjectMapper objectMapper;

    public SportteryPoolSyncServiceImpl(
            SportteryProvider sportteryProvider,
            ProviderSyncTemplate providerSyncTemplate,
            SportteryPoolPayloadMapper payloadMapper,
            SportteryPoolMatchWriter matchWriter,
            ObjectMapper objectMapper
    ) {
        this.sportteryProvider = sportteryProvider;
        this.providerSyncTemplate = providerSyncTemplate;
        this.payloadMapper = payloadMapper;
        this.matchWriter = matchWriter;
        this.objectMapper = objectMapper;
    }

    @Override
    public SportteryPoolSyncResultDto sync(SportteryPoolSyncRequestDto request) {
        LocalDate businessDate = request == null || request.businessDate() == null
                ? LocalDate.now(SHANGHAI)
                : request.businessDate();

        AtomicInteger matchUpsertCount = new AtomicInteger();
        AtomicInteger snapshotInsertCount = new AtomicInteger();

        ProviderSyncOutcome outcome = providerSyncTemplate.execute(
                sportteryProvider.providerCode(),
                ProviderDataTypeEnum.SPORTTERY_POOL,
                () -> sportteryProvider.fetchMatchPoolRaw(businessDate),
                (dataType, requestKey, payload) -> {
                    List<SportteryPoolSyncItemDto> items = payloadMapper.parseItems(
                            toJson(payload.getPayload()),
                            businessDate
                    );
                    SportteryPoolMatchWriter.WriteResult writeResult = matchWriter.writeAll(
                            items,
                            payload.getPayloadHash()
                    );
                    matchUpsertCount.set(writeResult.matchUpsertCount());
                    snapshotInsertCount.set(writeResult.snapshotInsertCount());
                    return writeResult.parseResult();
                }
        );

        return new SportteryPoolSyncResultDto(
                outcome,
                matchUpsertCount.get(),
                snapshotInsertCount.get()
        );
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new SportteryDataAccessException(
                    ProviderErrorCategory.PARSE_FAILURE,
                    "无法序列化原始比赛池载荷",
                    exception
            );
        }
    }
}
