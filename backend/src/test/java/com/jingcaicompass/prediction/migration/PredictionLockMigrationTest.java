package com.jingcaicompass.prediction.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** 静态检查 V9 已发布预测保护契约。 */
class PredictionLockMigrationTest {

    private static final String MIGRATION =
            "db/migration/V9__protect_published_predictions.sql";

    @Test
    void protectsLifecycleAndPublishedCoreContent() throws IOException {
        String sql = new ClassPathResource(MIGRATION)
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql).contains("CREATE OR REPLACE FUNCTION protect_published_prediction()");
        assertThat(sql).contains("CREATE TRIGGER trg_predictions_protect_published");
        assertThat(sql).contains("BEFORE UPDATE ON predictions");
        assertThat(sql).contains("OLD.prediction_status = 'DRAFT'");
        assertThat(sql).contains("NEW.prediction_status NOT IN ('DRAFT', 'PUBLISHED')");
        assertThat(sql).contains("OLD.prediction_status = 'PUBLISHED'");
        assertThat(sql).contains("NEW.prediction_status NOT IN ('PUBLISHED', 'LOCKED')");
        assertThat(sql).contains("OLD.lock_time > CURRENT_TIMESTAMP");
        assertThat(sql).contains("OLD.prediction_status = 'LOCKED'");
        assertThat(sql).contains("CONSTRAINT = 'ck_predictions_immutable_after_publish'");
        assertThat(sql).contains(
                "NEW.match_id",
                "NEW.model_version",
                "NEW.feature_version",
                "NEW.home_win_prob",
                "NEW.draw_prob",
                "NEW.away_win_prob",
                "NEW.analysis_summary",
                "NEW.publish_time",
                "NEW.lock_time",
                "NEW.prediction_hash"
        );
        assertThat(sql).contains("COMMENT ON FUNCTION protect_published_prediction()");
        assertThat(sql).doesNotContain("BEFORE DELETE");
    }
}
