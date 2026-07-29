package com.jingcaicompass.match.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** 静态检查 V14 的可读外部队名与 The Odds 精确事件回填契约。 */
class MappingDisplayNameMigrationTest {

    @Test
    void definesDisplayNamesAndExactTheOddsEventBackfill() throws IOException {
        String sql = new ClassPathResource("db/migration/V14__add_match_mapping_display_names.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql).contains("external_home_team_name");
        assertThat(sql).contains("external_away_team_name");
        assertThat(sql).contains("payload.provider_code = mapping.provider_code");
        assertThat(sql).contains("event.value ->> 'id' = mapping.external_match_id");
        assertThat(sql).contains("mapping.provider_code = 'THE_ODDS_API'");
        assertThat(sql).doesNotContain("apiKey");
    }
}
