package com.jingcaicompass.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 回测迁移的静态守门：<b>不许碰线上统计口径</b>。
 *
 * <p>这些断言看起来像字符串检查，但它们守的是一条真实的边界：
 * 回测结论一旦流进 {@code settlements} / {@code predictions}，线上 ROI 与命中率就会被
 * 几次实验污染，而且事后无法分辨哪些行是实验产生的。放在这里而不是只写在注释里，
 * 是因为「顺手加一条 UPDATE 方便对账」这种改动极其常见，且测试是唯一会立刻拦住它的东西。</p>
 *
 * <p>真正的行为验证在 {@code BacktestMigrationIT}（需要 Docker，从空库重放 V1~V29）
 * 与 {@code tools/backtest-schema-verify/}（无 Docker 时的生产库单事务验证）。</p>
 */
class BacktestMigrationTest {

    private static final String MIGRATION = "db/migration/V29__add_historical_backtest.sql";

    private static String sql;
    private static String normalized;

    @BeforeAll
    static void loadMigration() throws IOException {
        try (InputStream stream = BacktestMigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("迁移文件必须在 classpath 上：%s", MIGRATION).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        // 去掉整行注释再断言：注释里出现 "settlements" 是正常的（说明里必然要提到它）。
        StringBuilder withoutComments = new StringBuilder();
        for (String line : sql.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--")) {
                continue;
            }
            withoutComments.append(line).append('\n');
        }
        normalized = withoutComments.toString().toLowerCase(Locale.ROOT);
    }

    @Test
    void createsExactlyTheFourBacktestTables() {
        assertThat(normalized)
                .contains("create table odds_history_archive")
                .contains("create table backtest_runs")
                .contains("create table backtest_cases")
                .contains("create table backtest_metrics");
    }

    @Test
    void neverWritesToProductionStatisticTables() {
        assertThat(normalized)
                .as("回测迁移不得写入线上结算/预测/快照表")
                .doesNotContain("insert into settlements")
                .doesNotContain("update settlements")
                .doesNotContain("insert into predictions")
                .doesNotContain("update predictions")
                .doesNotContain("insert into asian_odds_snapshots")
                .doesNotContain("update asian_odds_snapshots");
    }

    @Test
    void doesNotAlterOrDropExistingObjects() {
        assertThat(normalized)
                .as("本迁移只新增对象，不改动既有表结构")
                .doesNotContain("alter table")
                .doesNotContain("drop table")
                .doesNotContain("drop index")
                .doesNotContain("truncate");
    }

    @Test
    void enforcesNoLookaheadAtTheDatabaseLevel() {
        assertThat(normalized)
                .contains("ck_backtest_cases_before_kickoff")
                .contains("constraint ck_backtest_cases_before_kickoff check (decision_at < kickoff_time)")
                .contains("ck_backtest_cases_no_lookahead")
                .contains("frozen_captured_at is null or frozen_captured_at <= decision_at");
    }

    @Test
    void protectsAppendOnlyAndDerivedTables() {
        assertThat(normalized)
                .contains("protect_odds_history_archive")
                .contains("trg_odds_history_archive_append_only")
                .contains("protect_backtest_metrics")
                .contains("trg_backtest_metrics_no_update")
                .contains("guard_backtest_cases")
                .contains("guard_backtest_runs");
    }

    @Test
    void refusesToFinishRunWhileCasesArePending() {
        assertThat(normalized)
                .contains("ck_backtest_runs_settled")
                .contains("result_status = 'pending'");
    }

    @Test
    void usesVarcharForFingerprintKeysInsteadOfBlankPaddedChar() {
        // PG 的 CHAR(n) 出库会补空格，而这些列都要与内存中的十六进制指纹逐字比对：
        // 一旦读回带空格，查表会静默 miss（表现为「明明有归档却判定未采过」）。
        assertThat(normalized)
                .contains("run_key                 varchar(32)")
                .contains("group_key               varchar(16)")
                .contains("payload_hash        varchar(64)");
        // 用正则而不是 doesNotContain：VARCHAR(32) 里也含 "CHAR(32)" 这段子串，
        // 朴素的包含判断会把正确写法判成违规。
        assertThat(sql)
                .as("三列都不允许再退化成 CHAR")
                .doesNotMatch("(?s).*(?<!VAR)CHAR\\((32|16|64)\\).*");
    }

    @Test
    void archivesOneSuccessSeriesPerMatchWithAPartialUniqueIndex() {
        assertThat(normalized)
                .contains("uk_odds_history_archive_success")
                .contains("where fetch_status = 'success'");
    }

    @Test
    void metricsCannotBeUpdatedInPlace() {
        assertThat(normalized).contains("before update on backtest_metrics");
    }
}
