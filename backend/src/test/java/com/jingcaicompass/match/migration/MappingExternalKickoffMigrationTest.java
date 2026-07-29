package com.jingcaicompass.match.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** 静态检查 V15 的供应商开赛时间精确回填契约。 */
class MappingExternalKickoffMigrationTest {

    @Test
    void definesExternalKickoffAndExactTheOddsEventBackfill() throws IOException {
        String sql = new ClassPathResource("db/migration/V15__add_match_mapping_external_kickoff_time.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql).contains("external_kickoff_time");
        assertThat(sql).contains("event.value ->> 'commence_time'");
        assertThat(sql).contains("event.value ->> 'id' = mapping.external_match_id");
        assertThat(sql).contains("mapping.provider_code = 'THE_ODDS_API'");
        assertThat(sql).doesNotContain("apiKey");
    }
}
