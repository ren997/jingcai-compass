package com.jingcaicompass.match.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.match.dto.SportteryPoolSyncItemDto;
import com.jingcaicompass.system.stub.StubFixtureLoader;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class SportteryPoolSyncPayloadMapperTest {

    private final SportteryPoolPayloadMapper mapper =
            new SportteryPoolPayloadMapper(new ObjectMapper().findAndRegisterModules());

    @Test
    void parsesHadAndHhadSpFromRawFixture() {
        String json = StubFixtureLoader.readText("stub/sporttery/pool-raw-normal.json");
        List<SportteryPoolSyncItemDto> items = mapper.parseItems(json, LocalDate.of(2026, 7, 22));

        assertThat(items).hasSize(2);
        assertThat(items.getFirst().lotteryMatchNo()).isEqualTo("周三001");
        assertThat(items.getFirst().hadHomeSp()).isEqualByComparingTo("2.10");
        assertThat(items.getFirst().hhadAwaySp()).isEqualByComparingTo("3.90");
        assertThat(items.getFirst().sellStatus()).isEqualTo("Selling");
        assertThat(items.getFirst().officialHandicap()).isEqualByComparingTo("-1");
    }
}
