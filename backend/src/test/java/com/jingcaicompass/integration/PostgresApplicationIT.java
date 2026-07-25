package com.jingcaicompass.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL 16 空库集成验证：完整启动持久化上下文，并验证 V1～V6 与数据库原生行为。
 */
@Testcontainers
@ActiveProfiles("integration")
@SpringBootTest
class PostgresApplicationIT {

    private static final String POSTGRES_IMAGE = "postgres:16-alpine";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(POSTGRES_IMAGE)
                    .withDatabaseName("jingcai_integration")
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
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void verifiesIsolatedContainerDataSource() throws Exception {
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
    void appliesAllMigrationsAndLoadsExpectedSchema() {
        MigrationInfo[] applied = Arrays.stream(flyway.info().applied())
                .filter(info -> info.getVersion() != null)
                .toArray(MigrationInfo[]::new);

        assertThat(applied).hasSize(6);
        assertThat(applied[applied.length - 1].getVersion().getVersion()).isEqualTo("6");
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.migrate().migrationsExecuted).isZero();

        List<String> tables = jdbcTemplate.queryForList(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                """,
                String.class
        );
        assertThat(tables).contains(
                "data_providers",
                "raw_data_payloads",
                "data_sync_runs",
                "leagues",
                "teams",
                "matches",
                "provider_league_mappings",
                "provider_team_mappings",
                "match_source_mappings",
                "sporttery_pool_snapshots",
                "asian_odds_snapshots",
                "league_aliases",
                "team_aliases",
                "audit_logs"
        );

        List<String> mappingColumns = jdbcTemplate.queryForList(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'match_source_mappings'
                """,
                String.class
        );
        assertThat(mappingColumns).contains("mapping_explanation", "mapping_candidates");
    }

    @Test
    void enforcesJsonbUniqueAndCheckConstraints() {
        String providerCode = "T006_JSONB";
        String payloadHash = "a".repeat(64);
        jdbcTemplate.update(
                """
                INSERT INTO raw_data_payloads (
                    provider_code,
                    data_type,
                    request_key,
                    requested_at,
                    payload,
                    payload_hash,
                    parse_status
                )
                VALUES (?, 'OTHER', 't006-jsonb', CURRENT_TIMESTAMP, CAST(? AS JSONB), ?, 'SUCCESS')
                """,
                providerCode,
                "{\"source\":\"t006\",\"valid\":true}",
                payloadHash
        );

        String source = jdbcTemplate.queryForObject(
                """
                SELECT payload ->> 'source'
                FROM raw_data_payloads
                WHERE provider_code = ?
                """,
                String.class,
                providerCode
        );
        assertThat(source).isEqualTo("t006");

        jdbcTemplate.update(
                """
                INSERT INTO data_providers (provider_code, provider_name, category)
                VALUES ('T006_UNIQUE', 'T006 provider', 'OTHER')
                """
        );
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO data_providers (provider_code, provider_name, category)
                VALUES ('T006_UNIQUE', 'Duplicate provider', 'OTHER')
                """
        )).isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO data_providers (provider_code, provider_name, category)
                VALUES ('T006_INVALID_CATEGORY', 'Invalid provider', 'INVALID')
                """
        )).isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO matches (
                    lottery_match_no,
                    lottery_date,
                    league_name,
                    home_team_name,
                    away_team_name,
                    kickoff_time,
                    match_status,
                    home_score
                )
                VALUES (
                    'T006-NEGATIVE',
                    DATE '2026-07-25',
                    'T006 League',
                    'T006 Home',
                    'T006 Away',
                    TIMESTAMPTZ '2026-07-25 12:00:00+08',
                    'FINISHED',
                    -1
                )
                """
        )).isInstanceOf(DataAccessException.class);
    }

    @Test
    void preservesTimestamptzAndRollsBackFailedTransactions() {
        OffsetDateTime expected = OffsetDateTime.parse("2026-07-25T12:34:56.123456+08:00");
        OffsetDateTime actual = jdbcTemplate.queryForObject(
                "SELECT CAST(? AS TIMESTAMPTZ)",
                (resultSet, rowNumber) -> resultSet.getObject(1, OffsetDateTime.class),
                expected
        );
        assertThat(actual).isNotNull();
        assertThat(actual.toInstant()).isEqualTo(expected.toInstant());

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update(
                    """
                    INSERT INTO data_providers (provider_code, provider_name, category)
                    VALUES ('T006_ROLLBACK', 'Rollback provider', 'OTHER')
                    """
            );
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM data_providers WHERE provider_code = 'T006_ROLLBACK'",
                Integer.class
        );
        assertThat(count).isZero();
    }
}
