package com.jingcaicompass.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jingcaicompass.admin.dto.AdminManualMatchResultDto;
import com.jingcaicompass.admin.service.AdminManualMatchResultService;
import com.jingcaicompass.data.enums.ProviderDataTypeEnum;
import com.jingcaicompass.match.dto.SportteryMatchResultDto;
import com.jingcaicompass.match.enums.MatchResultFactSourceEnum;
import com.jingcaicompass.match.enums.MatchResultFactStatusEnum;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import com.jingcaicompass.match.service.MatchResultFactWriter;
import com.jingcaicompass.settlement.service.SettlementRecalculationService;
import com.jingcaicompass.system.exception.BusinessException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** T406 PostgreSQL 16 验证人工来源、版本链、定向结算和并发幂等。 */
@Testcontainers
@ActiveProfiles("integration")
@SpringBootTest
class ManualMatchResultApplicationIT {

    private static final String POSTGRES_IMAGE = "postgres:16-alpine";
    private static final AtomicLong KEY_SEQUENCE = new AtomicLong(4_060_000L);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE)
            .withDatabaseName("jingcai_manual_result")
            .withUsername("jingcai_test")
            .withPassword("jingcai_test");

    @DynamicPropertySource
    static void registerPostgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private AdminManualMatchResultService manualResultService;
    @Autowired private MatchResultFactWriter factWriter;
    @Autowired private SettlementRecalculationService recalculationService;

    @BeforeEach
    void verifiesIsolatedPostgresContainer() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            String expectedPrefix = "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                    + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + POSTGRES.getDatabaseName();
            assertThat(metadata.getURL()).startsWith(expectedPrefix);
            assertThat(metadata.getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(metadata.getDatabaseMajorVersion()).isEqualTo(16);
        }
        jdbcTemplate.execute("""
                TRUNCATE TABLE audit_logs, settlements, match_result_facts, sporttery_pool_snapshots,
                raw_data_payloads, predictions, matches RESTART IDENTITY CASCADE
                """);
    }

    @Test
    void recordsCorrectsAndSettlesOnlyTheSelectedMatch() {
        Fixture target = fixture("target");
        Fixture unrelated = fixture("unrelated");
        appendOfficialFact(unrelated.matchId(), unrelated.matchNo(), 3, 0);

        var first = manualResultService.record(finalRequest(target.matchId(), 2, 1, "首次人工证据"), "admin-1");
        var duplicate = manualResultService.record(finalRequest(target.matchId(), 2, 1, "首次人工证据"), "admin-1");
        var corrected = manualResultService.record(finalRequest(target.matchId(), 0, 1, "修正人工证据"), "admin-1");

        assertThat(first.writeOutcome()).isEqualTo("APPENDED");
        assertThat(first.resultSource()).isEqualTo(MatchResultFactSourceEnum.MANUAL);
        assertThat(first.settlement().settledMarketCount()).isEqualTo(2);
        assertThat(duplicate.writeOutcome()).isEqualTo("UNCHANGED");
        assertThat(duplicate.settlementTriggered()).isFalse();
        assertThat(corrected.writeOutcome()).isEqualTo("SUPERSEDED");
        assertThat(corrected.settlement().recalculatedMarketCount()).isEqualTo(2);
        assertThat(singleLong("SELECT COUNT(*) FROM match_result_facts WHERE match_id = ?", target.matchId())).isEqualTo(2L);
        assertThat(singleString("SELECT result_source FROM match_result_facts WHERE match_id = ? AND is_current", target.matchId()))
                .isEqualTo("MANUAL");
        assertThat(singleString("SELECT data_type FROM raw_data_payloads WHERE id = (SELECT raw_data_payload_id FROM match_result_facts WHERE match_id = ? AND is_current)", target.matchId()))
                .isEqualTo(ProviderDataTypeEnum.MANUAL_RESULT.getCode());
        assertThat(singleLong("SELECT COUNT(*) FROM settlements WHERE prediction_id = ?", target.predictionId())).isEqualTo(4L);
        assertThat(singleLong("SELECT COUNT(*) FROM settlements WHERE prediction_id = ?", unrelated.predictionId())).isZero();
        assertThat(singleLong("SELECT COUNT(*) FROM audit_logs WHERE operator_id = 'admin-1' AND target_type = 'MATCH_RESULT_FACT'", new Object[0]))
                .isEqualTo(2L);
    }

    @Test
    void concurrentIdenticalManualEntriesAppendOneFactAndKeepOneCurrentVersion() throws Exception {
        Fixture fixture = fixture("concurrent");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Void> record = () -> {
                manualResultService.record(finalRequest(fixture.matchId(), 1, 1, "并发人工证据"), "admin-1");
                return null;
            };
            List<Future<Void>> futures = executor.invokeAll(List.of(record, record));
            for (Future<Void> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(singleLong("SELECT COUNT(*) FROM match_result_facts WHERE match_id = ?", fixture.matchId())).isEqualTo(1L);
        assertThat(singleLong("SELECT COUNT(*) FROM match_result_facts WHERE match_id = ? AND is_current", fixture.matchId())).isEqualTo(1L);
        assertThat(singleLong("SELECT COUNT(*) FROM settlements WHERE prediction_id = ?", fixture.predictionId())).isEqualTo(2L);
    }

    @Test
    void officialFactSupersedesManualFactAndRetainsBothSourcesForRecalculation() {
        Fixture fixture = fixture("official-priority");
        manualResultService.record(finalRequest(fixture.matchId(), 2, 1, "人工初版"), "admin-1");

        MatchResultFactWriter.WriteResult official = appendOfficialFact(fixture.matchId(), fixture.matchNo(), 0, 1);
        var recalculated = recalculationService.recalculateOutdatedSettlementsForMatch(fixture.matchId());

        assertThat(official.officialReplacedManual()).isTrue();
        assertThat(recalculated.recalculatedMarketCount()).isEqualTo(2);
        assertThat(singleString("SELECT result_source FROM match_result_facts WHERE match_id = ? AND is_current", fixture.matchId()))
                .isEqualTo("OFFICIAL");
        assertThat(singleLong("SELECT COUNT(*) FROM match_result_facts WHERE match_id = ? AND result_source = 'MANUAL'", fixture.matchId()))
                .isEqualTo(1L);
        assertThat(singleLong("SELECT COUNT(*) FROM settlements WHERE prediction_id = ?", fixture.predictionId())).isEqualTo(4L);
    }

    @Test
    void databaseRejectsForgedManualEvidenceAndManualCannotReplaceOfficialFact() {
        Fixture fixture = fixture("source-constraint");
        long officialPayloadId = insertOfficialPayload();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO match_result_facts (
                    match_id, fact_version, fact_status, match_status, home_score, away_score,
                    raw_data_payload_id, provider_updated_at, result_source, source_note, entry_reason, entered_by, is_current
                ) VALUES (?, 1, 'FINAL', 'FINISHED', 1, 0, ?, CURRENT_TIMESTAMP,
                    'MANUAL', '伪装人工来源', '测试约束', 'admin-1', TRUE)
                """, fixture.matchId(), officialPayloadId))
                .isInstanceOf(DataIntegrityViolationException.class);

        var manual = manualResultService.record(finalRequest(fixture.matchId(), 1, 0, "有效人工证据"), "admin-1");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE match_result_facts SET entry_reason = '篡改历史' WHERE id = ?", manual.factId()))
                .isInstanceOf(DataIntegrityViolationException.class);

        appendOfficialFact(fixture.matchId(), fixture.matchNo(), 0, 1);
        assertThatThrownBy(() -> manualResultService.record(
                finalRequest(fixture.matchId(), 2, 0, "尝试覆盖官方"), "admin-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("官方赛果");
    }

    private Fixture fixture(String suffix) {
        long key = KEY_SEQUENCE.incrementAndGet();
        String matchNo = "T406-" + key;
        long matchId = jdbcTemplate.queryForObject("""
                INSERT INTO matches (
                    lottery_match_no, lottery_date, league_name, home_team_name, away_team_name, kickoff_time, match_status
                ) VALUES (?, CURRENT_DATE, ?, 'T406 主队', 'T406 客队', CURRENT_TIMESTAMP - INTERVAL '5 minutes', 'SCHEDULED')
                RETURNING id
                """, Long.class, matchNo, "T406 " + suffix + " 联赛");
        long predictionId = jdbcTemplate.queryForObject("""
                INSERT INTO predictions (
                    match_id, model_version, feature_version, generation_batch_id, generation_batch_hash, prediction_version,
                    home_win_prob, draw_prob, away_win_prob, handicap_pick, expected_total_goals, confidence_level,
                    analysis_summary, generated_at, prediction_status, publish_time, lock_time, prediction_hash
                ) VALUES (?, 't406-model', 't406-feature', ?, ?, 1, 0.500000, 0.250000, 0.250000,
                    'HOME_WIN', 2.50, 'MEDIUM', 'T406 人工赛果测试预测', CURRENT_TIMESTAMP - INTERVAL '5 minutes',
                    'LOCKED', CURRENT_TIMESTAMP - INTERVAL '4 minutes', CURRENT_TIMESTAMP - INTERVAL '3 minutes', ?)
                RETURNING id
                """, Long.class, matchId, "T406-batch-" + key, "a".repeat(64), "b".repeat(64));
        jdbcTemplate.update("""
                INSERT INTO sporttery_pool_snapshots (
                    match_id, lottery_match_no, lottery_date, official_handicap, captured_at, raw_payload_hash
                ) VALUES (?, ?, CURRENT_DATE, ?, CURRENT_TIMESTAMP - INTERVAL '6 minutes', ?)
                """, matchId, matchNo, new BigDecimal("-1"), String.format("%064x", KEY_SEQUENCE.incrementAndGet()));
        return new Fixture(matchId, predictionId, matchNo);
    }

    private MatchResultFactWriter.WriteResult appendOfficialFact(long matchId, String matchNo, int homeScore, int awayScore) {
        long payloadId = insertOfficialPayload();
        return factWriter.write(new SportteryMatchResultDto("t406-" + matchId, LocalDate.now(), matchNo,
                homeScore, awayScore, MatchStatusEnum.FINISHED, true, false,
                OffsetDateTime.now().plusMinutes(1)), payloadId);
    }

    private long insertOfficialPayload() {
        return jdbcTemplate.queryForObject("""
                INSERT INTO raw_data_payloads (
                    provider_code, data_type, request_key, requested_at, payload, payload_hash, parse_status
                ) VALUES ('T406_IT', 'SPORTTERY_RESULT', ?, CURRENT_TIMESTAMP, '{}'::jsonb, ?, 'SUCCESS')
                RETURNING id
                """, Long.class, "t406-official-" + KEY_SEQUENCE.incrementAndGet(),
                String.format("%064x", KEY_SEQUENCE.incrementAndGet()));
    }

    private AdminManualMatchResultDto finalRequest(long matchId, int homeScore, int awayScore, String sourceNote) {
        return new AdminManualMatchResultDto(matchId, MatchResultFactStatusEnum.FINAL, MatchStatusEnum.FINISHED,
                homeScore, awayScore, sourceNote, "T406 开发验证补录", true);
    }

    private Long singleLong(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, Long.class, arguments);
    }

    private String singleString(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, String.class, arguments);
    }

    private record Fixture(long matchId, long predictionId, String matchNo) {
    }
}
