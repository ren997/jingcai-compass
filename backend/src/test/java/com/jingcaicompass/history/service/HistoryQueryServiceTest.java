package com.jingcaicompass.history.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jingcaicompass.history.dto.HistoryListQueryDto;
import com.jingcaicompass.history.dto.HistoryQueryCriteriaDto;
import com.jingcaicompass.history.mapper.HistoryQueryMapper;
import com.jingcaicompass.settlement.enums.SettlementStatusEnum;
import com.jingcaicompass.system.config.properties.PaginationProperties;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HistoryQueryServiceTest {

    @Mock
    private HistoryQueryMapper historyQueryMapper;

    @Mock
    private HistoryRecordAssembler historyRecordAssembler;

    @Test
    void defaultsToHadAndBuildsPendingAndPersistedStatusFilterWithStablePage() {
        HistoryQueryService service = new HistoryQueryServiceImpl(
                historyQueryMapper,
                historyRecordAssembler,
                new PaginationProperties(100)
        );
        when(historyQueryMapper.countPredictionIds(any())).thenReturn(4L);
        when(historyQueryMapper.selectPagePredictionIds(any())).thenReturn(List.of(41L, 40L));
        when(historyRecordAssembler.assemble(List.of(41L, 40L))).thenReturn(List.of());

        var page = service.list(new HistoryListQueryDto(
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31),
                3L,
                " model-v1 ",
                true,
                null,
                Set.of(SettlementStatusEnum.PENDING, SettlementStatusEnum.HIT),
                2,
                500
        ));

        ArgumentCaptor<HistoryQueryCriteriaDto> criteria = ArgumentCaptor.forClass(HistoryQueryCriteriaDto.class);
        verify(historyQueryMapper).countPredictionIds(criteria.capture());
        HistoryQueryCriteriaDto captured = criteria.getValue();
        assertThat(captured.settlementMarket().name()).isEqualTo("HAD");
        assertThat(captured.pendingStatusRequested()).isTrue();
        assertThat(captured.persistedSettlementStatuses()).containsExactly(SettlementStatusEnum.HIT);
        assertThat(captured.pageSize()).isEqualTo(100);
        assertThat(captured.offset()).isEqualTo(100);
        assertThat(captured.modelVersion()).isEqualTo("model-v1");
        assertThat(page.pageNo()).isEqualTo(2);
        assertThat(page.total()).isEqualTo(4);
    }
}
