package com.jingcaicompass.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import com.jingcaicompass.match.dto.MatchDetailQueryDto;
import com.jingcaicompass.match.dto.MatchListQueryDto;
import com.jingcaicompass.match.enums.MatchDataAvailabilityEnum;
import com.jingcaicompass.match.enums.MatchListSortEnum;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import com.jingcaicompass.match.service.MatchQueryService;
import com.jingcaicompass.match.service.MatchQueryServiceImpl;
import com.jingcaicompass.match.service.SportteryProvider;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** T501 PostgreSQL 16 验证公开比赛查询只读取持久化事实与盘口快照。 */
@Testcontainers
@ActiveProfiles("integration")
@SpringBootTest
class PublicMatchQueryApplicationIT {

    private static final String POSTGRES_IMAGE = "postgres:16-alpine";
    private static final AtomicLong KEY_SEQUENCE = new AtomicLong(5_010_000L);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE)
            .withDatabaseName("jingcai_public_match_query")
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
    private MatchQueryService matchQueryService;

    @MockBean
    private SportteryProvider sportteryProvider;

    @BeforeEach
    void preparesIsolatedPostgres() throws Exception {
        assertThat(matchQueryService).isInstanceOf(MatchQueryServiceImpl.class);
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertThat(metadata.getURL()).startsWith("jdbc:postgresql://" + POSTGRES.getHost());
            assertThat(metadata.getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(metadata.getDatabaseMajorVersion()).isEqualTo(16);
        }
        jdbcTemplate.execute("""
                TRUNCATE TABLE raw_data_payloads, asian_odds_snapshots, sporttery_pool_snapshots,
                match_source_mappings, matches, leagues RESTART IDENTITY CASCADE
                """);
    }

    @Test
    void pagesPersistedMatchesAndUsesTheLatestSportterySnapshotWithoutCallingProvider() {
        long leagueId = insertLeague("T501 分页联赛");
        long earlierMatch = insertMatch(leagueId, "T501-001", "SCHEDULED", "2026-07-22T10:00:00Z");
        long laterMatch = insertMatch(leagueId, "T501-002", "LOCKED", "2026-07-22T12:00:00Z");
        insertRawSportteryPayload("a");
        insertSportterySnapshot(earlierMatch, "a", "2026-07-22T08:00:00Z", "-1", "1.80");
        insertSportterySnapshot(earlierMatch, "a", "2026-07-22T09:00:00Z", "-0.5", "1.90");

        var firstPage = matchQueryService.list(new MatchListQueryDto(
                LocalDate.of(2026, 7, 22), leagueId,
                Set.of(MatchStatusEnum.SCHEDULED, MatchStatusEnum.LOCKED),
                MatchListSortEnum.KICKOFF_DESC, 1, 1
        ));
        var secondPage = matchQueryService.list(new MatchListQueryDto(
                LocalDate.of(2026, 7, 22), leagueId,
                Set.of(MatchStatusEnum.SCHEDULED, MatchStatusEnum.LOCKED),
                MatchListSortEnum.KICKOFF_DESC, 2, 1
        ));

        assertThat(firstPage.total()).isEqualTo(2);
        assertThat(firstPage.records()).extracting(item -> item.matchId()).containsExactly(laterMatch);
        assertThat(secondPage.records()).singleElement().satisfies(item -> {
            assertThat(item.matchId()).isEqualTo(earlierMatch);
            assertThat(item.officialHandicap()).isEqualByComparingTo("-0.5");
            assertThat(item.sportteryDataSource()).isEqualTo("T501_SPORTTERY");
        });
        verifyNoInteractions(sportteryProvider);
    }

    @Test
    void returnsEveryCurrentAsianLineWithMappingAndExplicitAbsenceStatus() {
        long leagueId = insertLeague("T501 详情联赛");
        long matchId = insertMatch(leagueId, "T501-DETAIL", "SCHEDULED", "2026-07-22T10:00:00Z");
        insertRawSportteryPayload("b");
        insertSportterySnapshot(matchId, "b", "2026-07-22T08:00:00Z", "0", "1.75");
        insertAsianSnapshot(matchId, "BOOK_A", "-0.5", "2026-07-22T08:00:00Z", "1.80");
        insertAsianSnapshot(matchId, "BOOK_A", "-0.5", "2026-07-22T09:00:00Z", "1.92");
        insertAsianSnapshot(matchId, "BOOK_A", "-0.25", "2026-07-22T09:00:00Z", "1.85");
        insertMapping(matchId, "ASIAN_TEST", "match-detail", "MANUAL_CONFIRMED");

        var detail = matchQueryService.detail(new MatchDetailQueryDto(matchId));

        assertThat(detail.sportteryMarket().availability()).isEqualTo(MatchDataAvailabilityEnum.AVAILABLE);
        assertThat(detail.sportteryMarket().officialHandicap()).isEqualByComparingTo("0");
        assertThat(detail.asianOddsAvailability()).isEqualTo(MatchDataAvailabilityEnum.AVAILABLE);
        assertThat(detail.asianOddsMarkets()).hasSize(2);
        assertThat(detail.asianOddsMarkets().stream()
                .filter(item -> item.handicapLine().compareTo(new BigDecimal("-0.5")) == 0)
                .findFirst().orElseThrow().homeOdds()).isEqualByComparingTo("1.92");
        assertThat(detail.mappingAvailability()).isEqualTo(MatchDataAvailabilityEnum.AVAILABLE);
        assertThat(detail.sourceMappings()).singleElement()
                .satisfies(item -> assertThat(item.mappingExplanation()).isEqualTo("T501 integration mapping"));
        verifyNoInteractions(sportteryProvider);
    }

    @Test
    void distinguishesMissingMarketsAndMappingsFromAnAbsentMatch() {
        long leagueId = insertLeague("T501 缺失联赛");
        long matchId = insertMatch(leagueId, "T501-MISSING", "SCHEDULED", "2026-07-22T10:00:00Z");

        var detail = matchQueryService.detail(new MatchDetailQueryDto(matchId));

        assertThat(detail.sportteryMarket().availability()).isEqualTo(MatchDataAvailabilityEnum.NO_SPORTTERY_SNAPSHOT);
        assertThat(detail.asianOddsAvailability()).isEqualTo(MatchDataAvailabilityEnum.NO_ASIAN_ODDS_SNAPSHOT);
        assertThat(detail.mappingAvailability()).isEqualTo(MatchDataAvailabilityEnum.NO_SOURCE_MAPPING);
    }

    private long insertLeague(String name) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO leagues (name_zh) VALUES (?) RETURNING id", Long.class, name + "-" + nextKey()
        );
    }

    private long insertMatch(long leagueId, String lotteryMatchNo, String status, String kickoff) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO matches (
                    lottery_match_no, lottery_date, league_id, league_name, home_team_name,
                    away_team_name, kickoff_time, match_status
                ) VALUES (?, DATE '2026-07-22', ?, 'T501 联赛', 'T501 主队', 'T501 客队', ?, ?)
                RETURNING id
                """, Long.class, lotteryMatchNo + "-" + nextKey(), leagueId, Timestamp.from(Instant.parse(kickoff)), status);
    }

    private void insertRawSportteryPayload(String seed) {
        jdbcTemplate.update("""
                INSERT INTO raw_data_payloads (
                    provider_code, data_type, request_key, requested_at, http_status, payload, payload_hash, parse_status
                ) VALUES ('T501_SPORTTERY', 'SPORTTERY_POOL', ?, CURRENT_TIMESTAMP, 200, '{}'::jsonb, ?, 'SUCCESS')
                """, "T501-raw-" + seed, hash(seed));
    }

    private void insertSportterySnapshot(
            long matchId,
            String hashSeed,
            String capturedAt,
            String handicap,
            String homeSp
    ) {
        jdbcTemplate.update("""
                INSERT INTO sporttery_pool_snapshots (
                    match_id, lottery_match_no, lottery_date, official_handicap,
                    had_home_sp, had_draw_sp, had_away_sp, captured_at, raw_payload_hash
                ) VALUES (?, ?, DATE '2026-07-22', ?, ?, 3.20, 4.10, ?, ?)
                """, matchId, "T501-" + matchId, new BigDecimal(handicap), new BigDecimal(homeSp),
                Timestamp.from(Instant.parse(capturedAt)), hash(hashSeed));
    }

    private void insertAsianSnapshot(
            long matchId,
            String bookmaker,
            String handicap,
            String capturedAt,
            String homeOdds
    ) {
        jdbcTemplate.update("""
                INSERT INTO asian_odds_snapshots (
                    match_id, provider_code, bookmaker_code, handicap_line, home_odds, away_odds,
                    snapshot_type, captured_at, raw_payload_hash
                ) VALUES (?, 'ASIAN_TEST', ?, ?, ?, 2.10, 'PRE_KICKOFF', ?, ?)
                """, matchId, bookmaker, new BigDecimal(handicap), new BigDecimal(homeOdds),
                Timestamp.from(Instant.parse(capturedAt)), hash("asian-" + bookmaker + handicap + capturedAt));
    }

    private void insertMapping(long matchId, String providerCode, String externalMatchId, String status) {
        jdbcTemplate.update("""
                INSERT INTO match_source_mappings (
                    match_id, provider_code, external_match_id, mapping_status, mapping_confidence,
                    mapping_method, mapping_explanation
                ) VALUES (?, ?, ?, ?, 1.0000, 'MANUAL', 'T501 integration mapping')
                """, matchId, providerCode, externalMatchId, status);
    }

    private static long nextKey() {
        return KEY_SEQUENCE.incrementAndGet();
    }

    private static String hash(String seed) {
        return String.format("%064x", Integer.toUnsignedLong(seed.hashCode()));
    }
}
