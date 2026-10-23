package com.jingcaicompass.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.jingcaicompass.odds.entity.AsianOddsSnapshot;
import com.jingcaicompass.odds.mapper.AsianOddsSnapshotMapper;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
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
 * PostgreSQL 16 验证跨庄聚合的两条取数查询。
 *
 * <p>这两条 SQL 用 {@code DISTINCT ON} 加多列排序，注解文本断言查不出语法错误，也查不出
 * 「去重维度写错导致同一家庄被重复计数」这类只在真库暴露的问题。主盘选择完全依赖这两条查询的
 * 去重口径，因此必须有真实库证据。</p>
 *
 * <p>单源改造后这两个测试同时承担一个反向证据：查询<b>不再依赖 {@code match_source_mappings}</b>。
 * 因此造数据时只写 {@code matches} 与 {@code asian_odds_snapshots}，一条映射都不插——
 * 如果哪天有人把 JOIN 加回来，这里会立刻变成空结果。</p>
 */
@Testcontainers
@ActiveProfiles("integration")
@SpringBootTest
class AsianOddsAggregationQueryIT {

    private static final String POSTGRES_IMAGE = "postgres:16-alpine";
    private static final AtomicLong KEY_SEQUENCE = new AtomicLong(6_130_000L);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE)
            .withDatabaseName("jingcai_asian_aggregation")
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
    private AsianOddsSnapshotMapper asianOddsSnapshotMapper;

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
        jdbcTemplate.execute("""
                TRUNCATE TABLE asian_odds_snapshots, match_source_mappings, matches
                RESTART IDENTITY CASCADE
                """);
    }

    @Test
    void handicapQueryReturnsLatestQuotePerBookmakerAndLine() {
        long matchId = insertMatch("ah-latest");
        insertSnapshot(matchId, "pinnacle", "-0.25", "1.90", "1.95", "2.50", "1.90", "1.95",
                "2026-10-21 09:00:00+08");
        insertSnapshot(matchId, "pinnacle", "-0.25", "1.75", "2.10", "2.50", "1.85", "2.00",
                "2026-10-21 11:00:00+08");
        insertSnapshot(matchId, "bet365", "-0.25", "1.88", "1.97", "2.50", "1.80", "2.05",
                "2026-10-21 10:00:00+08");

        List<AsianOddsSnapshot> rows =
                asianOddsSnapshotMapper.selectLatestHandicapLinesByMatchIds(List.of(matchId), "STUB");

        assertThat(rows).hasSize(2);
        AsianOddsSnapshot pinnacle = rows.stream()
                .filter(row -> "pinnacle".equals(row.getBookmakerCode()))
                .findFirst()
                .orElseThrow();
        assertThat(pinnacle.getHomeOdds()).isEqualByComparingTo("1.75");
    }

    @Test
    void totalsQueryDeduplicatesByTotalLineAcrossHandicapRows() {
        long matchId = insertMatch("totals-dedupe");
        // 同一家庄的同一档大小球随让球线重复出现，必须按 total_line 折叠成一条。
        insertSnapshot(matchId, "pinnacle", "-0.50", "1.95", "1.95", "2.75", "1.85", "2.00",
                "2026-10-21 09:00:00+08");
        insertSnapshot(matchId, "pinnacle", "-0.25", "1.88", "2.02", "2.75", "1.90", "1.95",
                "2026-10-21 11:00:00+08");
        insertSnapshot(matchId, "pinnacle", "-0.25", "1.88", "2.02", "3.00", "2.05", "1.80",
                "2026-10-21 11:05:00+08");

        List<AsianOddsSnapshot> rows =
                asianOddsSnapshotMapper.selectLatestTotalsLinesByMatchIds(List.of(matchId), "STUB");

        assertThat(rows).hasSize(2);
        AsianOddsSnapshot line275 = rows.stream()
                .filter(row -> row.getTotalLine().compareTo(new java.math.BigDecimal("2.75")) == 0)
                .findFirst()
                .orElseThrow();
        // 2.75 折叠后保留最新一条（11:00，让球线 -0.25）。
        assertThat(line275.getHandicapLine()).isEqualByComparingTo("-0.25");
        assertThat(line275.getOverOdds()).isEqualByComparingTo("1.90");
    }

    @Test
    void otherProviderRowsAreExcludedFromBothQueries() {
        long matchId = insertMatch("other-provider");
        insertSnapshot(matchId, "NOVA_API", "pinnacle", "-0.25", "1.90", "1.95", "2.50", "1.90", "1.95",
                "2026-10-21 10:00:00+08");

        // provider_code 过滤不是可选优化：聚合器按 (bookmaker, line) 去重、不含 provider，
        // 两个来源的同名庄会互相覆盖并一起进中位数，主盘会悄悄偏移。
        assertThat(asianOddsSnapshotMapper.selectLatestHandicapLinesByMatchIds(List.of(matchId), "STUB"))
                .isEmpty();
        assertThat(asianOddsSnapshotMapper.selectLatestTotalsLinesByMatchIds(List.of(matchId), "STUB"))
                .isEmpty();
        assertThat(asianOddsSnapshotMapper.selectLatestHandicapLinesByMatchIds(List.of(matchId), "NOVA_API"))
                .hasSize(1);
    }

    @Test
    void nonPositivePriceRowsAreExcluded() {
        long matchId = insertMatch("zero-price");
        insertSnapshot(matchId, "pinnacle", "-0.25", "0.00", "1.95", "2.50", "1.90", "1.95",
                "2026-10-21 10:00:00+08");

        assertThat(asianOddsSnapshotMapper.selectLatestHandicapLinesByMatchIds(List.of(matchId), "STUB"))
                .isEmpty();
    }

    private long insertMatch(String suffix) {
        long key = KEY_SEQUENCE.incrementAndGet();
        long matchId = jdbcTemplate.queryForObject(
                """
                INSERT INTO matches (
                    lottery_match_no, lottery_date, league_name, home_team_name,
                    away_team_name, kickoff_time, match_status
                )
                VALUES (?, CURRENT_DATE, 'T613B 联赛', 'T613B 主队', 'T613B 客队', CURRENT_TIMESTAMP, 'SCHEDULED')
                RETURNING id
                """,
                Long.class,
                "T613B-" + suffix + "-" + key
        );
        return matchId;
    }

    private void insertSnapshot(
            long matchId,
            String bookmakerCode,
            String handicapLine,
            String homeOdds,
            String awayOdds,
            String totalLine,
            String overOdds,
            String underOdds,
            String capturedAt
    ) {
        insertSnapshot(matchId, "STUB", bookmakerCode, handicapLine, homeOdds, awayOdds,
                totalLine, overOdds, underOdds, capturedAt);
    }

    private void insertSnapshot(
            long matchId,
            String providerCode,
            String bookmakerCode,
            String handicapLine,
            String homeOdds,
            String awayOdds,
            String totalLine,
            String overOdds,
            String underOdds,
            String capturedAt
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO asian_odds_snapshots (
                    match_id, provider_code, bookmaker_code,
                    handicap_line, home_odds, away_odds,
                    total_line, over_odds, under_odds,
                    snapshot_type, captured_at, raw_payload_hash
                ) VALUES (?, ?, ?,
                          CAST(? AS NUMERIC), CAST(? AS NUMERIC), CAST(? AS NUMERIC),
                          CAST(? AS NUMERIC), CAST(? AS NUMERIC), CAST(? AS NUMERIC),
                          'FIRST_SEEN', CAST(? AS TIMESTAMPTZ), ?)
                """,
                matchId,
                providerCode,
                bookmakerCode,
                handicapLine,
                homeOdds,
                awayOdds,
                totalLine,
                overOdds,
                underOdds,
                capturedAt,
                String.format("%064x", KEY_SEQUENCE.incrementAndGet())
        );
    }
}
