package com.jingcaicompass.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V29 回测四表在真实 PostgreSQL 16 上的行为验证。
 *
 * <p>为什么必须有一条这样的 IT：迁移里的约束与触发器是<b>声明</b>，只有从空库重放 V1~V29
 * 才会暴露声明矛盾类缺陷（约束与既存数据冲突、部分唯一索引与冲突目标不匹配、
 * 触发器函数体引用不存在的列）。本机的 dry-run 看不出这些——那边是「在生产库上加表再回滚」，
 * 既存数据与迁移历史都不参与。</p>
 *
 * <p>断言的两条主线：<b>无泄漏</b>（决策时刻严格早于开赛、冻结行不晚于决策时刻）与
 * <b>结论不可事后修饰</b>（决策列冻结、结算只写一次、派生指标禁改、终态不可回退）。</p>
 */
@Testcontainers
class BacktestMigrationIT {

    private static final AtomicLong MATCH_SEQUENCE = new AtomicLong(60_900_000L);
    private static final String PROVIDER = "NOVA_API";

    /** 比赛开赛时刻：所有决策截面都相对它推导，避免手写时间戳算错。 */
    private static final Instant KICKOFF = Instant.parse("2026-06-21T12:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("jingcai_backtest")
            .withUsername("jingcai_test")
            .withPassword("jingcai_test");

    private static Flyway flyway;

    @BeforeAll
    static void migratesToLatest() {
        flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
    }

    @Test
    void replayReachesV29WithAllFourTables() throws SQLException {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("29");
        assertThat(tableExists("odds_history_archive")).isTrue();
        assertThat(tableExists("backtest_runs")).isTrue();
        assertThat(tableExists("backtest_cases")).isTrue();
        assertThat(tableExists("backtest_metrics")).isTrue();
    }

    @Test
    void archiveIsAppendOnlyAndSuccessRequiresPayload() throws SQLException {
        long matchId = createMatch();

        // SUCCESS 缺载荷 → 拒绝（防「看着有数据其实没解析」）
        assertRejected("""
                INSERT INTO odds_history_archive (provider_code, external_match_id, fetch_status)
                VALUES ('%s', 'no-payload', 'SUCCESS')
                """.formatted(PROVIDER), "ck_odds_history_archive_payload_scope");

        archiveSuccess(matchId, "append-only-case");

        // 只追加：UPDATE / DELETE 一律拒绝
        assertRejected("""
                UPDATE odds_history_archive SET failure_message = 'x'
                WHERE external_match_id = 'append-only-case'
                """, "append only");
        assertRejected("""
                DELETE FROM odds_history_archive WHERE external_match_id = 'append-only-case'
                """, "append only");
    }

    @Test
    void oneSuccessSeriesPerMatchButFailuresAccumulate() throws SQLException {
        long matchId = createMatch();
        archiveSuccess(matchId, "dup-success");

        // 部分唯一索引 (provider_code, external_match_id) WHERE fetch_status='SUCCESS'
        assertRejected("""
                INSERT INTO odds_history_archive (
                    provider_code, external_match_id, fetch_status, payload, payload_hash,
                    bookmaker_count, point_count, series_start_at, series_end_at)
                VALUES ('%s', 'dup-success', 'SUCCESS', '{}'::jsonb, repeat('a', 64), 1, 1, now(), now())
                """.formatted(PROVIDER), "uk_odds_history_archive_success");

        // 失败行不受该索引约束：同一场持续 404 必须留痕，而不是被覆盖
        execute("""
                INSERT INTO odds_history_archive (provider_code, external_match_id, fetch_status)
                VALUES (?, 'dup-success', 'NO_HISTORY')
                """, PROVIDER);
        execute("""
                INSERT INTO odds_history_archive (provider_code, external_match_id, fetch_status)
                VALUES (?, 'dup-success', 'NO_HISTORY')
                """, PROVIDER);
        assertThat(queryForLong("""
                SELECT count(*) FROM odds_history_archive
                WHERE external_match_id = 'dup-success' AND fetch_status = 'NO_HISTORY'
                """)).isEqualTo(2);
    }

    @Test
    void caseRejectsLookaheadAndInconsistentSkipState() throws SQLException {
        long matchId = createMatch();
        long runId = createRun(1);
        long archiveId = archiveSuccess(matchId, "lookahead");

        // 决策时刻 == 开赛时刻 → 拒绝（必须严格早于）
        assertRejected("""
                INSERT INTO backtest_cases (
                    run_id, group_key, external_match_id, match_id, archive_id,
                    decision_at, kickoff_time, skip_reason, decision_stage)
                VALUES (%d, 'g1', 'lookahead', %d, %d, '%s', '%s', 'KICKOFF_PASSED', 'KICKOFF_PASSED')
                """.formatted(runId, matchId, archiveId, KICKOFF, KICKOFF),
                "ck_backtest_cases_before_kickoff");

        // 冻结行采集晚于决策时刻 → 拒绝
        assertRejected("""
                INSERT INTO backtest_cases (
                    run_id, group_key, external_match_id, match_id, archive_id,
                    decision_at, kickoff_time, skip_reason, decision_stage, frozen_captured_at)
                VALUES (%d, 'g1', 'lookahead', %d, %d, '%s', '%s', 'KICKOFF_PASSED', 'KICKOFF_PASSED', '%s')
                """.formatted(runId, matchId, archiveId, minutesBefore(105), KICKOFF, minutesBefore(60)),
                "ck_backtest_cases_no_lookahead");

        // 有跳过原因却带方向 → 拒绝
        assertRejected("""
                INSERT INTO backtest_cases (
                    run_id, group_key, external_match_id, match_id, archive_id,
                    decision_at, kickoff_time, skip_reason, decision_stage,
                    handicap_pick, handicap_confidence)
                VALUES (%d, 'g1', 'lookahead', %d, %d, '%s', '%s',
                        'LOW_CONFIDENCE_ASIAN_MARKETS', 'BOTH_MARKETS_REJECTED', 'HOME_COVER', 'MEDIUM')
                """.formatted(runId, matchId, archiveId, minutesBefore(105), KICKOFF),
                "ck_backtest_cases_decided");

        // 有方向但没有冻结行 → 拒绝
        assertRejected("""
                INSERT INTO backtest_cases (
                    run_id, group_key, external_match_id, match_id, archive_id,
                    decision_at, kickoff_time, decision_stage, totals_pick, totals_confidence)
                VALUES (%d, 'g1', 'lookahead', %d, %d, '%s', '%s', 'DECIDED', 'OVER', 'MEDIUM')
                """.formatted(runId, matchId, archiveId, minutesBefore(105), KICKOFF),
                "ck_backtest_cases_decided");
    }

    @Test
    void decisionColumnsAreFrozenOnceWritten() throws SQLException {
        long matchId = createMatch();
        long runId = createRun(1);
        long archiveId = archiveSuccess(matchId, "immutable");
        long caseId = insertDecidedCase(runId, archiveId, matchId, "immutable");

        // 允许事后改决策列，就等于允许拿赛果去修饰当初的判断。
        assertRejected("""
                UPDATE backtest_cases SET handicap_pick = 'AWAY_COVER' WHERE id = %d
                """.formatted(caseId), "decision columns are immutable");
        assertRejected("""
                DELETE FROM backtest_cases WHERE id = %d
                """.formatted(caseId), "must not be deleted");
    }

    @Test
    void settlementIsWrittenOnceOnly() throws SQLException {
        long matchId = createMatch();
        long runId = createRun(1);
        long archiveId = archiveSuccess(matchId, "settle-once");
        long caseId = insertDecidedCase(runId, archiveId, matchId, "settle-once");

        execute("""
                UPDATE backtest_cases SET result_status = 'FINAL', home_score = 2, away_score = 1,
                    handicap_status = 'HIT', handicap_profit = 0.88,
                    totals_status = 'MISS', totals_profit = -1,
                    settled_at = now()
                WHERE id = ?
                """, caseId);

        assertRejected("""
                UPDATE backtest_cases SET settled_at = now(), totals_profit = 1 WHERE id = %d
                """.formatted(caseId), "already settled");
    }

    @Test
    void runCannotSucceedWhileCasesArePending() throws SQLException {
        long matchId = createMatch();
        long runId = createRun(1);
        long archiveId = archiveSuccess(matchId, "run-guard");
        insertDecidedCase(runId, archiveId, matchId, "run-guard");

        execute("UPDATE backtest_runs SET run_status = 'ARCHIVING', run_stage = 'ARCHIVING' WHERE id = ?", runId);
        execute("UPDATE backtest_runs SET run_status = 'EVALUATING', run_stage = 'EVALUATING' WHERE id = ?", runId);
        execute("UPDATE backtest_runs SET run_status = 'SETTLING', run_stage = 'SETTLING' WHERE id = ?", runId);
        execute("UPDATE backtest_runs SET run_status = 'AGGREGATING', run_stage = 'AGGREGATING' WHERE id = ?", runId);

        // 还有 PENDING 行就标 SUCCEEDED：完成时刻之后的结算不会再被触发，样本会被永久漏算。
        assertRejected("""
                UPDATE backtest_runs SET run_status = 'SUCCEEDED', finished_at = now() WHERE id = %d
                """.formatted(runId), "pending cases");

        execute("""
                UPDATE backtest_cases SET result_status = 'VOID', handicap_status = 'VOID',
                    handicap_profit = 0, totals_status = 'VOID', totals_profit = 0, settled_at = now()
                WHERE run_id = ?
                """, runId);
        execute("UPDATE backtest_runs SET run_status = 'SUCCEEDED', finished_at = now() WHERE id = ?", runId);
        assertThat(queryForString("SELECT run_status FROM backtest_runs WHERE id = " + runId))
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void runDefinitionAndTerminalStateAreImmutable() throws SQLException {
        long runId = createRun(1);

        assertRejected("""
                UPDATE backtest_runs SET date_from = DATE '2026-06-19' WHERE id = %d
                """.formatted(runId), "definition is immutable");

        execute("UPDATE backtest_runs SET run_status = 'ARCHIVING', run_stage = 'ARCHIVING' WHERE id = ?", runId);
        execute("""
                UPDATE backtest_runs SET run_status = 'FAILED', run_stage = 'DONE',
                    finished_at = now(), error_code = 'FIXTURE'
                WHERE id = ?
                """, runId);

        assertRejected("""
                UPDATE backtest_runs SET run_status = 'QUEUED', finished_at = NULL WHERE id = %d
                """.formatted(runId), "terminal backtest run must not change");

        // 非法流转也要拦：QUEUED 不能跳到 AGGREGATING
        long another = createRun(1);
        assertRejected("""
                UPDATE backtest_runs SET run_status = 'AGGREGATING', run_stage = 'AGGREGATING' WHERE id = %d
                """.formatted(another), "illegal backtest run transition");
    }

    @Test
    void derivedMetricsCannotBeUpdated() throws SQLException {
        long runId = createRun(1);
        execute("""
                INSERT INTO backtest_metrics (run_id, group_key, market_scope)
                VALUES (?, 'g1', 'ALL')
                """, runId);

        assertRejected("""
                UPDATE backtest_metrics SET hit_count = 99 WHERE run_id = %d
                """.formatted(runId), "derived, recompute instead");
    }

    private long createMatch() throws SQLException {
        return queryForLong("""
                INSERT INTO matches (
                    lottery_match_no, lottery_date, league_name, home_team_name, away_team_name,
                    kickoff_time, match_status)
                VALUES (?, ?, 'Backtest League', 'Home FC', 'Away FC', ?, 'FINISHED')
                RETURNING id
                """,
                "BT-" + MATCH_SEQUENCE.incrementAndGet(),
                LocalDate.of(2026, 6, 20),
                Timestamp.from(KICKOFF));
    }

    private long archiveSuccess(long matchId, String externalMatchId) throws SQLException {
        return queryForLong("""
                INSERT INTO odds_history_archive (
                    provider_code, external_match_id, match_id, lottery_date, kickoff_time,
                    fetch_status, payload, payload_hash, bookmaker_count, point_count,
                    series_start_at, series_end_at)
                VALUES (?, ?, ?, DATE '2026-06-20', ?, 'SUCCESS',
                        '{"data":{"providers":[]}}'::jsonb, repeat('b', 64), 3, 40, ?, ?)
                RETURNING id
                """,
                PROVIDER,
                externalMatchId,
                matchId,
                Timestamp.from(KICKOFF),
                Timestamp.from(Instant.parse("2026-06-20T02:00:00Z")),
                Timestamp.from(Instant.parse("2026-06-21T10:00:00Z")));
    }

    private long createRun(int totalMatches) throws SQLException {
        return queryForLong("""
                INSERT INTO backtest_runs (
                    run_key, run_name, run_status, run_stage, operator_username,
                    date_from, date_to, parameter_sets, provider_code,
                    model_version, selector_version, aggregation_version, builder_version,
                    total_match_count)
                VALUES (repeat('a', 32), 'it', 'QUEUED', 'NONE', 'integration',
                        DATE '2026-06-20', DATE '2026-06-20', '[{"groupKey":"g1"}]'::jsonb, ?,
                        'asian-decision-v1', 'sel-v1', 'agg-v1', 'slice-v1', ?)
                RETURNING id
                """, PROVIDER, totalMatches);
    }

    private long insertDecidedCase(long runId, long archiveId, long matchId, String externalMatchId)
            throws SQLException {
        return queryForLong("""
                INSERT INTO backtest_cases (
                    run_id, group_key, external_match_id, match_id, archive_id,
                    decision_at, kickoff_time, decision_stage,
                    frozen_bookmaker_code, frozen_handicap_line, frozen_home_odds, frozen_away_odds,
                    frozen_total_line, frozen_over_odds, frozen_under_odds, frozen_captured_at,
                    handicap_pick, handicap_confidence)
                VALUES (?, 'g1', ?, ?, ?, ?, ?, 'DECIDED',
                        'pinnacle', -0.50, 1.9000, 2.0000, 2.75, 1.8800, 1.9200, ?,
                        'HOME_COVER', 'MEDIUM')
                RETURNING id
                """,
                runId,
                externalMatchId,
                matchId,
                archiveId,
                Timestamp.from(minutesBefore(105)),
                Timestamp.from(KICKOFF),
                Timestamp.from(minutesBefore(120)));
    }

    private static Instant minutesBefore(int minutes) {
        return KICKOFF.minusSeconds(minutes * 60L);
    }

    private boolean tableExists(String table) throws SQLException {
        return queryForLong(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = '" + table + "'") == 1;
    }

    /**
     * 断言某条 SQL 被拒绝，并核对主消息文本。
     *
     * <p>触发器用 {@code RAISE ... USING CONSTRAINT=} 指定的约束名<b>不会</b>出现在 JDBC 的
     * message 里（只进 PG 侧的 {@code constraint_name} 字段），所以这里比的是主消息。
     * 按约束名断言会得到「触发器明明生效但测试失败」的假象——踩过。</p>
     */
    private void assertRejected(String sql, String expectedFragment) {
        assertThatThrownBy(() -> execute(sql))
                .as("应被拒绝：%s", expectedFragment)
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(expectedFragment);
    }

    private long queryForLong(String sql, Object... parameters) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private String queryForString(String sql) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private void execute(String sql, Object... parameters) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            statement.executeUpdate();
        }
    }

    private Connection connection() throws SQLException {
        return POSTGRES.createConnection("");
    }

    private void bind(PreparedStatement statement, Object... parameters) throws SQLException {
        for (int index = 0; index < parameters.length; index++) {
            statement.setObject(index + 1, parameters[index]);
        }
    }
}
