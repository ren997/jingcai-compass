package com.jingcaicompass.prediction.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** 静态检查 V7 预测版本与公开快照约束契约。 */
class PredictionSnapshotMigrationTest {

    private static final String MIGRATION =
            "db/migration/V7__init_prediction_and_public_snapshot.sql";

    @Test
    void definesVersionedPredictionContract() throws IOException {
        String sql = readMigration();

        assertThat(sql).contains("CREATE TABLE predictions");
        assertThat(sql).contains("home_win_prob           NUMERIC(7,6)   NOT NULL");
        assertThat(sql).contains("draw_prob               NUMERIC(7,6)   NOT NULL");
        assertThat(sql).contains("away_win_prob           NUMERIC(7,6)   NOT NULL");
        assertThat(sql).contains("generation_batch_id");
        assertThat(sql).contains("generation_batch_hash");
        assertThat(sql).contains("feature_version");
        assertThat(sql).contains("CONSTRAINT uk_predictions_match_model_version UNIQUE");
        assertThat(sql).contains("CONSTRAINT uk_predictions_generation_batch UNIQUE");
        assertThat(sql).contains("CONSTRAINT ck_predictions_probability_range CHECK");
        assertThat(sql).contains("CONSTRAINT ck_predictions_probability_sum CHECK");
        assertThat(sql).contains("BETWEEN 0.999999 AND 1.000001");
        assertThat(sql).contains("'HOME_WIN'", "'DRAW'", "'AWAY_WIN'");
        assertThat(sql).contains("'LOW'", "'MEDIUM'", "'HIGH'");
        assertThat(sql).contains("'DRAFT'", "'PUBLISHED'", "'LOCKED'");
        assertThat(sql).contains("prediction_hash ~ '^[0-9a-f]{64}$'");
        assertThat(sql).contains("publish_time < lock_time");
        assertThat(sql).contains("CREATE INDEX idx_predictions_match_model_version_desc");
        assertThat(sql).contains("CREATE INDEX idx_predictions_status_lock_time");
        assertThat(sql).doesNotContain("is_locked");
        assertThat(sql).doesNotContain("CREATE TRIGGER");
    }

    @Test
    void definesPublicSnapshotMetadataContract() throws IOException {
        String sql = readMigration();

        assertThat(sql).contains("CREATE TABLE prediction_snapshots");
        assertThat(sql).contains("CONSTRAINT uk_prediction_snapshots_date_version UNIQUE");
        assertThat(sql).contains("CONSTRAINT ck_prediction_snapshots_version_positive CHECK");
        assertThat(sql).contains("'PENDING'", "'PUBLISHED'", "'FAILED'");
        assertThat(sql).contains("snapshot_hash ~ '^[0-9a-f]{64}$'");
        assertThat(sql).contains(
                "storage_type",
                "object_key",
                "object_version",
                "file_url",
                "content_type",
                "content_length",
                "published_at",
                "failure_reason"
        );
        assertThat(sql).contains("COMMENT ON TABLE predictions");
        assertThat(sql).contains("COMMENT ON TABLE prediction_snapshots");
    }

    private String readMigration() throws IOException {
        return new ClassPathResource(MIGRATION).getContentAsString(StandardCharsets.UTF_8);
    }
}
