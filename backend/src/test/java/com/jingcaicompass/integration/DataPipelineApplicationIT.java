package com.jingcaicompass.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.jingcaicompass.data.dto.DataPipelineResultDto;
import com.jingcaicompass.data.enums.DataPipelineStatusEnum;
import com.jingcaicompass.data.enums.SyncStatusEnum;
import com.jingcaicompass.data.service.DataPipelineService;
import com.jingcaicompass.match.support.NameNormalizationSupport;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.LocalDate;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T207 双源流水线 PostgreSQL 端到端验证。
 */
@Testcontainers
@ActiveProfiles("integration")
@SpringBootTest
class DataPipelineApplicationIT {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 7, 22);
    private static final String POSTGRES_IMAGE = "postgres:16-alpine";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(POSTGRES_IMAGE)
                    .withDatabaseName("jingcai_pipeline")
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
    private DataPipelineService dataPipelineService;

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
    void runsStubPipelineTwiceWithoutDuplicatingDomainData() {
        SeedData seed = seedConfirmedDictionaryAliasesAndManualMapping();

        DataPipelineResultDto first = dataPipelineService.run(BUSINESS_DATE);

        assertThat(first.status()).isEqualTo(DataPipelineStatusEnum.PARTIAL);
        assertThat(first.sportteryStatus()).isEqualTo(SyncStatusEnum.SUCCESS);
        assertThat(first.asianOddsStatus()).isEqualTo(SyncStatusEnum.PARTIAL);
        assertThat(first.sportteryMatchUpsertCount()).isEqualTo(2);
        assertThat(first.sportterySnapshotInsertCount()).isEqualTo(2);
        assertThat(first.normalization().totalMatchCount()).isEqualTo(2);
        assertThat(first.normalization().normalizedMatchCount()).isEqualTo(2);
        assertThat(first.normalization().pendingMatchCount()).isZero();
        assertThat(first.normalization().failureCount()).isZero();
        assertThat(first.normalization().updatedMatchCount()).isEqualTo(1);
        assertThat(first.confirmedMappingCount()).isEqualTo(6);
        assertThat(first.pendingMappingCount()).isEqualTo(2);
        assertThat(first.asianOddsSnapshotInsertCount()).isEqualTo(5);
        assertThat(first.skippedUnmapped()).isEqualTo(2);
        assertThat(first.skippedIncomplete()).isEqualTo(1);
        assertThat(first.coveredMatchCount()).isEqualTo(2);
        assertThat(first.coverageRate()).isEqualByComparingTo("1.0000");
        assertThat(first.errorMessage()).contains("failurebook");

        assertThat(singleLong("""
                SELECT COUNT(*)
                FROM provider_team_mappings
                WHERE mapping_status = 'MANUAL_CONFIRMED'
                  AND mapping_method = 'ALIAS'
                """)).isEqualTo(2);
        assertThat(singleLong("""
                SELECT COUNT(*)
                FROM provider_team_mappings
                WHERE mapping_status = 'PENDING'
                """)).isEqualTo(2);
        assertThat(singleString("""
                SELECT mapping_status
                FROM match_source_mappings
                WHERE provider_code = 'STUB'
                  AND external_match_id = 'asian-stub-time-conflict-window-001'
                """)).isEqualTo("PENDING");
        assertThat(singleString("""
                SELECT mapping_status
                FROM match_source_mappings
                WHERE provider_code = 'STUB'
                  AND external_match_id = 'asian-stub-manual-001'
                """)).isEqualTo("MANUAL_CONFIRMED");
        assertThat(singleLong("""
                SELECT match_id
                FROM match_source_mappings
                WHERE provider_code = 'STUB'
                  AND external_match_id = 'asian-stub-manual-001'
                """)).isEqualTo(seed.manualMatchId());

        PipelineCounts afterFirst = counts();
        DataPipelineResultDto second = dataPipelineService.run(BUSINESS_DATE);
        PipelineCounts afterSecond = counts();

        assertThat(second.status()).isEqualTo(DataPipelineStatusEnum.PARTIAL);
        assertThat(second.sportterySnapshotInsertCount()).isZero();
        assertThat(second.asianOddsSnapshotInsertCount()).isZero();
        assertThat(second.normalization().updatedMatchCount()).isZero();
        assertThat(second.confirmedMappingCount()).isEqualTo(6);
        assertThat(second.pendingMappingCount()).isEqualTo(2);
        assertThat(afterSecond).isEqualTo(afterFirst);
        assertThat(afterSecond.matches()).isEqualTo(2);
        assertThat(afterSecond.leagues()).isEqualTo(1);
        assertThat(afterSecond.teams()).isEqualTo(6);
        assertThat(afterSecond.providerLeagueMappings()).isEqualTo(1);
        assertThat(afterSecond.providerTeamMappings()).isEqualTo(8);
        assertThat(afterSecond.matchSourceMappings()).isEqualTo(8);
        assertThat(afterSecond.sportterySnapshots()).isEqualTo(2);
        assertThat(afterSecond.asianOddsSnapshots()).isEqualTo(5);
        assertThat(afterSecond.rawPayloads()).isEqualTo(2);

        assertThat(singleLong("""
                SELECT home_team_id
                FROM matches
                WHERE lottery_date = DATE '2026-07-22'
                  AND lottery_match_no = '周三001'
                """)).isEqualTo(seed.homeTeamAId());
        assertThat(singleLong("""
                SELECT away_team_id
                FROM matches
                WHERE lottery_date = DATE '2026-07-22'
                  AND lottery_match_no = '周三001'
                """)).isEqualTo(seed.awayTeamAId());
    }

    private SeedData seedConfirmedDictionaryAliasesAndManualMapping() {
        Long leagueId = jdbcTemplate.queryForObject(
                """
                INSERT INTO leagues (name_zh)
                VALUES ('演示联赛')
                RETURNING id
                """,
                Long.class
        );
        Long homeTeamAId = insertTeam("演示主队 A");
        Long awayTeamAId = insertTeam("演示客队 A");
        insertTeam("演示主队 B");
        insertTeam("演示客队 B");

        insertTeamAlias(homeTeamAId, "演示主队A别名");
        insertTeamAlias(awayTeamAId, "演示客队A别名");

        Long manualMatchId = jdbcTemplate.queryForObject(
                """
                INSERT INTO matches (
                    lottery_match_no,
                    lottery_date,
                    league_id,
                    home_team_id,
                    away_team_id,
                    league_name,
                    home_team_name,
                    away_team_name,
                    kickoff_time,
                    match_status
                )
                VALUES (
                    '周三001',
                    DATE '2026-07-22',
                    ?,
                    ?,
                    ?,
                    '演示联赛',
                    '演示主队 A',
                    '演示客队 A',
                    TIMESTAMPTZ '2026-07-22 19:30:00+08',
                    'SCHEDULED'
                )
                RETURNING id
                """,
                Long.class,
                leagueId,
                homeTeamAId,
                awayTeamAId
        );

        jdbcTemplate.update(
                """
                INSERT INTO matches (
                    lottery_match_no,
                    lottery_date,
                    league_name,
                    home_team_name,
                    away_team_name,
                    kickoff_time,
                    match_status
                )
                VALUES (
                    '周三002',
                    DATE '2026-07-22',
                    '演示联赛',
                    '演示主队 B',
                    '演示客队 B',
                    TIMESTAMPTZ '2026-07-22 21:00:00+08',
                    'SCHEDULED'
                )
                """
        );

        jdbcTemplate.update(
                """
                INSERT INTO match_source_mappings (
                    match_id,
                    provider_code,
                    external_match_id,
                    mapping_status,
                    mapping_confidence,
                    mapping_method,
                    mapping_explanation,
                    confirmed_by
                )
                VALUES (?, 'STUB', 'asian-stub-manual-001', 'MANUAL_CONFIRMED',
                        1.0000, 'MANUAL_REVIEW', 'T207 seeded manual mapping', 't207-it')
                """,
                manualMatchId
        );
        return new SeedData(manualMatchId, homeTeamAId, awayTeamAId);
    }

    private Long insertTeam(String name) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO teams (name_zh) VALUES (?) RETURNING id",
                Long.class,
                name
        );
    }

    private void insertTeamAlias(Long teamId, String alias) {
        jdbcTemplate.update(
                """
                INSERT INTO team_aliases (
                    team_id,
                    alias_raw,
                    alias_normalized,
                    source,
                    confirmed_by
                )
                VALUES (?, ?, ?, 'T207_IT', 't207-it')
                """,
                teamId,
                alias,
                NameNormalizationSupport.normalizedKey(alias)
        );
    }

    private PipelineCounts counts() {
        return new PipelineCounts(
                count("matches"),
                count("leagues"),
                count("teams"),
                count("provider_league_mappings"),
                count("provider_team_mappings"),
                count("match_source_mappings"),
                count("sporttery_pool_snapshots"),
                count("asian_odds_snapshots"),
                count("raw_data_payloads")
        );
    }

    private int count(String table) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private Long singleLong(String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class);
    }

    private String singleString(String sql) {
        return jdbcTemplate.queryForObject(sql, String.class);
    }

    private record SeedData(Long manualMatchId, Long homeTeamAId, Long awayTeamAId) {
    }

    private record PipelineCounts(
            int matches,
            int leagues,
            int teams,
            int providerLeagueMappings,
            int providerTeamMappings,
            int matchSourceMappings,
            int sportterySnapshots,
            int asianOddsSnapshots,
            int rawPayloads
    ) {
    }
}
