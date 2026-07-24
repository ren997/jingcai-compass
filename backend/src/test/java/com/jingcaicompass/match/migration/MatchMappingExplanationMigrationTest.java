package com.jingcaicompass.match.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** 静态检查 V5 比赛映射解释与候选列契约。 */
class MatchMappingExplanationMigrationTest {

    @Test
    void definesExplanationAndCandidatesColumns() throws IOException {
        String sql = new ClassPathResource("db/migration/V5__match_source_mapping_explanation.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql).contains("ALTER TABLE match_source_mappings");
        assertThat(sql).contains("mapping_explanation");
        assertThat(sql).contains("mapping_candidates");
        assertThat(sql).contains("JSONB");
        assertThat(sql).contains("COMMENT ON COLUMN match_source_mappings.mapping_explanation");
        assertThat(sql).contains("COMMENT ON COLUMN match_source_mappings.mapping_candidates");
    }
}
