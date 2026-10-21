package com.jingcaicompass.history.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jingcaicompass.history.dto.HistoryListQueryDto;
import com.jingcaicompass.history.dto.HistoryQueryCriteriaDto;
import com.jingcaicompass.history.mapper.HistoryQueryMapper;
import com.jingcaicompass.prediction.enums.PredictionTypeEnum;
import com.jingcaicompass.settlement.enums.MarketTypeEnum;
import com.jingcaicompass.settlement.enums.SettlementStatusEnum;
import com.jingcaicompass.system.config.properties.PaginationProperties;
import com.jingcaicompass.system.exception.BusinessException;
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
        assertThat(captured.predictionType()).isEqualTo(PredictionTypeEnum.SPORTTERY);
    }

    @Test
    void infersPredictionTypeFromAsianMarketWhenTypeOmitted() {
        HistoryQueryServiceImpl service = service();
        stubEmptyPage();

        service.list(query(null, MarketTypeEnum.ASIAN_TOTAL_GOALS));

        HistoryQueryCriteriaDto captured = captureCriteria();
        assertThat(captured.predictionType()).isEqualTo(PredictionTypeEnum.ASIAN);
        assertThat(captured.settlementMarket()).isEqualTo(MarketTypeEnum.ASIAN_TOTAL_GOALS);
    }

    @Test
    void defaultsMarketToFirstMarketOfRequestedType() {
        HistoryQueryServiceImpl service = service();
        stubEmptyPage();

        service.list(query(PredictionTypeEnum.ASIAN, null));

        HistoryQueryCriteriaDto captured = captureCriteria();
        assertThat(captured.predictionType()).isEqualTo(PredictionTypeEnum.ASIAN);
        assertThat(captured.settlementMarket()).isEqualTo(MarketTypeEnum.ASIAN_HANDICAP);
    }

    @Test
    void rejectsMarketThatDoesNotBelongToRequestedType() {
        HistoryQueryServiceImpl service = service();

        assertThatThrownBy(() -> service.list(query(PredictionTypeEnum.ASIAN, MarketTypeEnum.HAD)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("does not belong to ASIAN");
    }

    private HistoryQueryServiceImpl service() {
        return new HistoryQueryServiceImpl(
                historyQueryMapper,
                historyRecordAssembler,
                new PaginationProperties(100)
        );
    }

    private void stubEmptyPage() {
        when(historyQueryMapper.countPredictionIds(any())).thenReturn(0L);
        when(historyQueryMapper.selectPagePredictionIds(any())).thenReturn(List.of());
        when(historyRecordAssembler.assemble(List.of())).thenReturn(List.of());
    }

    private HistoryListQueryDto query(
            PredictionTypeEnum predictionType,
            MarketTypeEnum settlementMarket
    ) {
        return new HistoryListQueryDto(
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 21),
                null,
                null,
                false,
                settlementMarket,
                Set.of(),
                1,
                20,
                predictionType
        );
    }

    private HistoryQueryCriteriaDto captureCriteria() {
        ArgumentCaptor<HistoryQueryCriteriaDto> criteria = ArgumentCaptor.forClass(HistoryQueryCriteriaDto.class);
        verify(historyQueryMapper).countPredictionIds(criteria.capture());
        return criteria.getValue();
    }
}
