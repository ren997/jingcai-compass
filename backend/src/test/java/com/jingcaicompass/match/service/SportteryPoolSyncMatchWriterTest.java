package com.jingcaicompass.match.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jingcaicompass.match.dto.SportteryPoolSyncItemDto;
import com.jingcaicompass.match.entity.MatchEntity;
import com.jingcaicompass.match.entity.SportteryPoolSnapshot;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import com.jingcaicompass.match.mapper.MatchMapper;
import com.jingcaicompass.match.mapper.SportteryPoolSnapshotMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SportteryPoolSyncMatchWriterTest {

    @Mock
    private MatchMapper matchMapper;

    @Mock
    private SportteryPoolSnapshotMapper snapshotMapper;

    private SportteryPoolMatchWriter writer;

    @BeforeEach
    void setUp() {
        writer = new SportteryPoolMatchWriter(matchMapper, snapshotMapper);
    }

    @Test
    void firstSyncInsertsMatchAndSnapshot() {
        when(matchMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(matchMapper.insert(any(MatchEntity.class))).thenAnswer(invocation -> {
            MatchEntity entity = invocation.getArgument(0);
            entity.setId(11L);
            return 1;
        });
        when(snapshotMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        SportteryPoolMatchWriter.WriteResult result = writer.writeAll(
                List.of(sampleItem("周三001", "2.10", "Selling")),
                "a".repeat(64)
        );

        assertThat(result.parseResult().successCount()).isEqualTo(1);
        assertThat(result.parseResult().failureCount()).isEqualTo(0);
        assertThat(result.matchUpsertCount()).isEqualTo(1);
        assertThat(result.snapshotInsertCount()).isEqualTo(1);
        verify(matchMapper).insert(any(MatchEntity.class));
        verify(snapshotMapper).insert(any(SportteryPoolSnapshot.class));
    }

    @Test
    void unchangedOddsSkipsSnapshotInsert() {
        MatchEntity existing = new MatchEntity();
        existing.setId(22L);
        when(matchMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        SportteryPoolSnapshot latest = baseSnapshot(22L, "2.10", "Selling");
        when(snapshotMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(latest);

        SportteryPoolMatchWriter.WriteResult result = writer.writeAll(
                List.of(sampleItem("周三001", "2.10", "Selling")),
                "b".repeat(64)
        );

        assertThat(result.snapshotInsertCount()).isEqualTo(0);
        verify(matchMapper).updateById(existing);
        verify(snapshotMapper, never()).insert(any(SportteryPoolSnapshot.class));
    }

    @Test
    void oddsChangeAppendsSnapshot() {
        MatchEntity existing = new MatchEntity();
        existing.setId(33L);
        when(matchMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
        when(snapshotMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(baseSnapshot(33L, "2.10", "Selling"));

        SportteryPoolMatchWriter.WriteResult result = writer.writeAll(
                List.of(sampleItem("周三001", "2.25", "Selling")),
                "c".repeat(64)
        );

        assertThat(result.snapshotInsertCount()).isEqualTo(1);
        ArgumentCaptor<SportteryPoolSnapshot> captor = ArgumentCaptor.forClass(SportteryPoolSnapshot.class);
        verify(snapshotMapper).insert(captor.capture());
        assertThat(captor.getValue().getHadHomeSp()).isEqualByComparingTo("2.25");
    }

    @Test
    void singleFailureStillPersistsOthers() {
        when(matchMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenThrow(new IllegalStateException("db down"))
                .thenReturn(null);
        when(matchMapper.insert(any(MatchEntity.class))).thenAnswer(invocation -> {
            MatchEntity entity = invocation.getArgument(0);
            entity.setId(44L);
            return 1;
        });
        when(snapshotMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        SportteryPoolMatchWriter.WriteResult result = writer.writeAll(
                List.of(
                        sampleItem("周三001", "2.10", "Selling"),
                        sampleItem("周三002", "2.55", "Selling")
                ),
                "d".repeat(64)
        );

        assertThat(result.parseResult().successCount()).isEqualTo(1);
        assertThat(result.parseResult().failureCount()).isEqualTo(1);
        assertThat(result.parseResult().errorMessage()).contains("周三001");
        verify(matchMapper, times(1)).insert(any(MatchEntity.class));
    }

    @Test
    void emptyPoolWritesNothing() {
        SportteryPoolMatchWriter.WriteResult result = writer.writeAll(List.of(), "e".repeat(64));
        assertThat(result.parseResult().successCount()).isEqualTo(0);
        assertThat(result.matchUpsertCount()).isEqualTo(0);
        assertThat(result.snapshotInsertCount()).isEqualTo(0);
        verify(matchMapper, never()).insert(any(MatchEntity.class));
        verify(snapshotMapper, never()).insert(any(SportteryPoolSnapshot.class));
    }

    private SportteryPoolSyncItemDto sampleItem(String matchNo, String hadHomeSp, String sellStatus) {
        return new SportteryPoolSyncItemDto(
                "900001",
                LocalDate.of(2026, 7, 22),
                matchNo,
                "演示联赛",
                "演示主队",
                "演示客队",
                OffsetDateTime.parse("2026-07-22T19:30:00+08:00"),
                new BigDecimal("-1"),
                MatchStatusEnum.SCHEDULED,
                sellStatus,
                new BigDecimal(hadHomeSp),
                new BigDecimal("3.20"),
                new BigDecimal("3.40"),
                new BigDecimal("1.85"),
                new BigDecimal("3.45"),
                new BigDecimal("3.90")
        );
    }

    private SportteryPoolSnapshot baseSnapshot(Long matchId, String hadHomeSp, String sellStatus) {
        SportteryPoolSnapshot snapshot = new SportteryPoolSnapshot();
        snapshot.setMatchId(matchId);
        snapshot.setOfficialHandicap(new BigDecimal("-1"));
        snapshot.setHadHomeSp(new BigDecimal(hadHomeSp));
        snapshot.setHadDrawSp(new BigDecimal("3.20"));
        snapshot.setHadAwaySp(new BigDecimal("3.40"));
        snapshot.setHhadHomeSp(new BigDecimal("1.85"));
        snapshot.setHhadDrawSp(new BigDecimal("3.45"));
        snapshot.setHhadAwaySp(new BigDecimal("3.90"));
        snapshot.setSellStatus(sellStatus);
        return snapshot;
    }
}
