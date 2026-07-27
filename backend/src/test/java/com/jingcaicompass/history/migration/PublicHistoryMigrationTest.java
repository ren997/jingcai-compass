package com.jingcaicompass.history.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class PublicHistoryMigrationTest {

    @Test
    void addsOnlyQueryBackedPublicHistoryIndexes() throws IOException {
        String sql = new ClassPathResource("db/migration/V12__add_public_history_indexes.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql).contains(
                "idx_matches_history_lottery_league_kickoff",
                "idx_predictions_history_public_model_match",
                "idx_settlements_current_market_status_prediction",
                "WHERE prediction_status IN ('PUBLISHED', 'LOCKED')",
                "WHERE is_current"
        );
        assertThat(sql).doesNotContain("CREATE TABLE");
    }
}
