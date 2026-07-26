package com.jingcaicompass.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jingcaicompass.data.enums.SyncStatusEnum;
import com.jingcaicompass.match.dto.MatchResultSyncRequestDto;
import com.jingcaicompass.match.dto.SportteryMatchResultDto;
import com.jingcaicompass.match.dto.SportteryPoolSyncItemDto;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import com.jingcaicompass.match.service.MatchResultFactWriter;
import com.jingcaicompass.match.service.MatchResultSyncService;
import com.jingcaicompass.match.service.SportteryPoolMatchWriter;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** T402 PostgreSQL 16 验证 raw 同步、事实链、投影和只追加审计。 */
@Testcontainers
@ActiveProfiles("integration")
@SpringBootTest
class MatchResultSyncApplicationIT {

    private static final LocalDate LOTTERY_DATE = LocalDate.of(2026, 7, 22);
    private static final String POSTGRES_IMAGE = "postgres:16-alpine";
    private static final AtomicLong KEY_SEQUENCE = new AtomicLong(4_020_000L);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE)
            .withDatabaseName("jingcai_match_result_sync")
            .withUsername("jingcai_test")
            .withPassword("jingcai_test");

    @DynamicPropertySource
    static void registerPostgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MatchResultSyncService matchResultSyncService;

    @Autowired
    private MatchResultFactWriter matchResultFactWriter;

    @Autowired
    private SportteryPoolMatchWriter sportteryPoolMatchWriter;

    @BeforeEach
    void verifiesIsolatedPostgresContainer() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            String expectedPrefix = "jdbc:postgresql://"
                    + POSTGRES.getHost()
                    + ":"
                    + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
                    + "/"
                    + POSTGRES.getDatabaseName();
            assertThat(metadata.getURL()).startsWith(expectedPrefix);
            assertThat(metadata.getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(metadata.getDatabaseMajorVersion()).isEqualTo(16);
        }
    }

    @Test
    void syncsStubRawPayloadAndReplaysIdempotently() {
        String key = key("raw");
        for (String matchNo : List.of("周三001", "周三002", "周三004", "周三005")) {
            insertMatch(key, matchNo);
        }

        var first = matchResultSyncService.sync(new MatchResultSyncRequestDto(LOTTERY_DATE, LOTTERY_DATE));
        var second = matchResultSyncService.sync(new MatchResultSyncRequestDto(LOTTERY_DATE, LOTTERY_DATE));

        assertThat(first.outcome().status()).isEqualTo(SyncStatusEnum.SUCCESS);
        assertThat(first.appendedFactCount()).isEqualTo(4);
        assertThat(first.supersededFactCount()).isZero();
        assertThat(first.unchangedFactCount()).isZero();
        assertThat(second.outcome().status()).isEqualTo(SyncStatusEnum.SUCCESS);
        assertThat(second.appendedFactCount()).isZero();
        assertThat(second.supersededFactCount()).isZero();
        assertThat(second.unchangedFactCount()).isEqualTo(4);
        assertThat(count("match_result_facts", "match_id IN (SELECT id FROM matches WHERE league_name = ?)", key))
                .isEqualTo(4);
        assertThat(count("raw_data_payloads", "data_type = 'SPORTTERY_RESULT' AND provider_code = 'STUB'", null))
                .isEqualTo(1);
        assertThat(singleString("""
                SELECT fact_status
                FROM match_result_facts fact
                JOIN matches match_record ON match_record.id = fact.match_id
                WHERE match_record.league_name = ?
                  AND match_record.lottery_match_no = '周三005'
                  AND fact.is_current
                """, key)).isEqualTo("PENDING");
        assertThat(singleString("""
                SELECT home_score || ':' || away_score
                FROM matches
                WHERE league_name = ?
                  AND lottery_match_no = '周三001'
                """, key)).isEqualTo("1:1");
        assertThat(matchAuditCount(key, "SYNC")).isEqualTo(4);
    }

    @Test
    void preservesCorrectedFactHistoryUpdatesProjectionAndProtectsItFromPoolSync() {
        String key = key("corrected");
        long matchId = insertMatch(key, "T402-001");
        long firstRawPayloadId = insertResultPayload(key + "-first");
        long amendedRawPayloadId = insertResultPayload(key + "-amended");

        matchResultFactWriter.write(result("T402-001", 2, 1, false, "2026-07-22T23:30:00+08:00"), firstRawPayloadId);
        matchResultFactWriter.write(result("T402-001", 1, 1, true, "2026-07-23T10:00:00+08:00"), amendedRawPayloadId);

        assertThat(count("match_result_facts", "match_id = ?", matchId)).isEqualTo(2);
        assertThat(singleLong("""
                SELECT fact_version
                FROM match_result_facts
                WHERE match_id = ? AND is_current
                """, matchId)).isEqualTo(2L);
        assertThat(singleString("SELECT home_score || ':' || away_score FROM matches WHERE id = ?", matchId))
                .isEqualTo("1:1");
        assertThat(matchAuditCount(key, "SUPERSEDE")).isEqualTo(1);

        sportteryPoolMatchWriter.writeAll(
                List.of(poolItem("T402-001", MatchStatusEnum.SCHEDULED)),
                "a".repeat(64)
        );
        assertThat(singleString("SELECT match_status FROM matches WHERE id = ?", matchId)).isEqualTo("FINISHED");
        assertThat(singleString("SELECT home_score || ':' || away_score FROM matches WHERE id = ?", matchId))
                .isEqualTo("1:1");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE match_result_facts SET home_score = 9 WHERE match_id = ? AND fact_version = 1",
                matchId
        )).isInstanceOf(DataAccessException.class);
    }

    private long insertMatch(String leagueName, String matchNo) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO matches (
                    lottery_match_no, lottery_date, league_name, home_team_name,
                    away_team_name, kickoff_time, match_status
                )
                VALUES (?, ?, ?, 'T402 主队', 'T402 客队', CURRENT_TIMESTAMP, 'SCHEDULED')
                RETURNING id
                """,
                Long.class,
                matchNo,
                LOTTERY_DATE,
                leagueName
        );
    }

    private long insertResultPayload(String requestKey) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO raw_data_payloads (
                    provider_code, data_type, request_key, requested_at,
                    payload, payload_hash, parse_status
                )
                VALUES ('T402_IT', 'SPORTTERY_RESULT', ?, CURRENT_TIMESTAMP,
                        '{}'::jsonb, ?, 'SUCCESS')
                RETURNING id
                """,
                Long.class,
                requestKey,
                String.format("%064d", KEY_SEQUENCE.incrementAndGet())
        );
    }

    private SportteryMatchResultDto result(
            String matchNo,
            int homeScore,
            int awayScore,
            boolean amended,
            String providerUpdatedAt
    ) {
        return new SportteryMatchResultDto(
                "t402-" + matchNo,
                LOTTERY_DATE,
                matchNo,
                homeScore,
                awayScore,
                MatchStatusEnum.FINISHED,
                amended,
                false,
                OffsetDateTime.parse(providerUpdatedAt)
        );
    }

    private SportteryPoolSyncItemDto poolItem(String matchNo, MatchStatusEnum status) {
        return new SportteryPoolSyncItemDto(
                "t402-" + matchNo,
                LOTTERY_DATE,
                matchNo,
                "T402 池联赛",
                "T402 池主队",
                "T402 池客队",
                OffsetDateTime.parse("2026-07-22T19:30:00+08:00"),
                new BigDecimal("-1"),
                status,
                "Selling",
                new BigDecimal("2.10"),
                new BigDecimal("3.20"),
                new BigDecimal("3.40"),
                new BigDecimal("1.85"),
                new BigDecimal("3.45"),
                new BigDecimal("3.90")
        );
    }

    private int count(String table, String whereClause, Object argument) {
        String sql = "SELECT COUNT(*) FROM " + table + " WHERE " + whereClause;
        Integer result = argument == null
                ? jdbcTemplate.queryForObject(sql, Integer.class)
                : jdbcTemplate.queryForObject(sql, Integer.class, argument);
        return result == null ? 0 : result;
    }

    private Long singleLong(String sql, Object argument) {
        return jdbcTemplate.queryForObject(sql, Long.class, argument);
    }

    private String singleString(String sql, Object argument) {
        return jdbcTemplate.queryForObject(sql, String.class, argument);
    }

    private int matchAuditCount(String leagueName, String actionType) {
        Integer result = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_logs audit
                JOIN match_result_facts fact ON audit.target_id = CAST(fact.id AS VARCHAR)
                JOIN matches match_record ON match_record.id = fact.match_id
                WHERE audit.target_type = 'MATCH_RESULT_FACT'
                  AND audit.action_type = ?
                  AND match_record.league_name = ?
                """,
                Integer.class,
                actionType,
                leagueName
        );
        return result == null ? 0 : result;
    }

    private String key(String prefix) {
        return "T402-" + prefix + "-" + KEY_SEQUENCE.incrementAndGet();
    }
}
