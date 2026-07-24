package com.jingcaicompass.odds.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.jingcaicompass.odds.dto.AsianOddsLineDto;
import com.jingcaicompass.odds.entity.AsianOddsSnapshot;
import com.jingcaicompass.odds.enums.OddsSnapshotTypeEnum;
import com.jingcaicompass.odds.mapper.AsianOddsSnapshotMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AsianOddsSnapshotWriterTest {

    @Mock
    private AsianOddsSnapshotMapper asianOddsSnapshotMapper;

    private AsianOddsSnapshotWriter writer;

    @BeforeEach
    void setUp() {
        writer = new AsianOddsSnapshotWriter(asianOddsSnapshotMapper);
    }

    @Test
    void insertsFirstSnapshotWithTotals() {
        when(asianOddsSnapshotMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        AsianOddsSnapshotWriter.WriteResult result = writer.writeLines(
                10L,
                "STUB",
                List.of(line(
                        "pinnacle",
                        "-0.5",
                        "1.90",
                        "1.95",
                        "2.5",
                        "1.92",
                        "1.94"
                )),
                "a".repeat(64)
        );

        assertThat(result.snapshotInsertCount()).isEqualTo(1);
        assertThat(result.skippedIncomplete()).isZero();
        ArgumentCaptor<AsianOddsSnapshot> captor = ArgumentCaptor.forClass(AsianOddsSnapshot.class);
        verify(asianOddsSnapshotMapper).insert(captor.capture());
        AsianOddsSnapshot saved = captor.getValue();
        assertThat(saved.getSnapshotType()).isEqualTo(OddsSnapshotTypeEnum.FIRST_SEEN);
        assertThat(saved.getTotalLine()).isEqualByComparingTo("2.5");
        assertThat(saved.getOverOdds()).isEqualByComparingTo("1.92");
        assertThat(saved.getUnderOdds()).isEqualByComparingTo("1.94");
    }

    @Test
    void skipsInsertWhenContentUnchanged() {
        AsianOddsSnapshot latest = new AsianOddsSnapshot();
        latest.setHomeOdds(new BigDecimal("1.90"));
        latest.setAwayOdds(new BigDecimal("1.95"));
        latest.setTotalLine(new BigDecimal("2.5"));
        latest.setOverOdds(new BigDecimal("1.92"));
        latest.setUnderOdds(new BigDecimal("1.94"));
        when(asianOddsSnapshotMapper.selectOne(any(Wrapper.class))).thenReturn(latest);

        AsianOddsSnapshotWriter.WriteResult result = writer.writeLines(
                10L,
                "STUB",
                List.of(line(
                        "pinnacle",
                        "-0.5",
                        "1.90",
                        "1.95",
                        "2.5",
                        "1.92",
                        "1.94"
                )),
                "a".repeat(64)
        );

        assertThat(result.snapshotInsertCount()).isZero();
        verify(asianOddsSnapshotMapper, never()).insert(any(AsianOddsSnapshot.class));
    }

    @Test
    void insertsWhenAhOrTotalsChange() {
        AsianOddsSnapshot latest = new AsianOddsSnapshot();
        latest.setHomeOdds(new BigDecimal("1.90"));
        latest.setAwayOdds(new BigDecimal("1.95"));
        latest.setTotalLine(new BigDecimal("2.5"));
        latest.setOverOdds(new BigDecimal("1.92"));
        latest.setUnderOdds(new BigDecimal("1.94"));
        when(asianOddsSnapshotMapper.selectOne(any(Wrapper.class))).thenReturn(latest);

        AsianOddsSnapshotWriter.WriteResult result = writer.writeLines(
                10L,
                "STUB",
                List.of(line(
                        "pinnacle",
                        "-0.5",
                        "1.91",
                        "1.95",
                        "2.5",
                        "1.92",
                        "1.94"
                )),
                "a".repeat(64)
        );

        assertThat(result.snapshotInsertCount()).isEqualTo(1);
        ArgumentCaptor<AsianOddsSnapshot> captor = ArgumentCaptor.forClass(AsianOddsSnapshot.class);
        verify(asianOddsSnapshotMapper).insert(captor.capture());
        assertThat(captor.getValue().getSnapshotType()).isEqualTo(OddsSnapshotTypeEnum.PRE_KICKOFF);
        assertThat(captor.getValue().getHomeOdds()).isEqualByComparingTo("1.91");
    }

    @Test
    void writesAhAndClearsPartialTotals() {
        when(asianOddsSnapshotMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        AsianOddsLineDto partialTotals = new AsianOddsLineDto(
                "pinnacle",
                new BigDecimal("0"),
                new BigDecimal("1.88"),
                new BigDecimal("1.98"),
                new BigDecimal("2.5"),
                null,
                null,
                OffsetDateTime.parse("2026-07-22T12:00:00+08:00")
        );

        AsianOddsSnapshotWriter.WriteResult result = writer.writeLines(
                11L,
                "STUB",
                List.of(partialTotals),
                "b".repeat(64)
        );

        assertThat(result.snapshotInsertCount()).isEqualTo(1);
        assertThat(result.skippedIncomplete()).isEqualTo(1);
        ArgumentCaptor<AsianOddsSnapshot> captor = ArgumentCaptor.forClass(AsianOddsSnapshot.class);
        verify(asianOddsSnapshotMapper).insert(captor.capture());
        assertThat(captor.getValue().getTotalLine()).isNull();
        assertThat(captor.getValue().getOverOdds()).isNull();
    }

    @Test
    void skipsIncompleteAhLines() {
        AsianOddsLineDto incomplete = new AsianOddsLineDto(
                "pinnacle",
                new BigDecimal("-0.5"),
                null,
                new BigDecimal("1.95"),
                null,
                null,
                null,
                Instant.now().atOffset(java.time.ZoneOffset.UTC)
        );

        AsianOddsSnapshotWriter.WriteResult result = writer.writeLines(
                12L,
                "STUB",
                List.of(incomplete),
                "c".repeat(64)
        );

        assertThat(result.snapshotInsertCount()).isZero();
        assertThat(result.skippedIncomplete()).isEqualTo(1);
        verify(asianOddsSnapshotMapper, never()).insert(any(AsianOddsSnapshot.class));
        verify(asianOddsSnapshotMapper, never()).selectOne(any());
    }

    private static AsianOddsLineDto line(
            String bookmaker,
            String handicap,
            String home,
            String away,
            String total,
            String over,
            String under
    ) {
        return new AsianOddsLineDto(
                bookmaker,
                new BigDecimal(handicap),
                new BigDecimal(home),
                new BigDecimal(away),
                total == null ? null : new BigDecimal(total),
                over == null ? null : new BigDecimal(over),
                under == null ? null : new BigDecimal(under),
                OffsetDateTime.parse("2026-07-22T12:00:00+08:00")
        );
    }
}
