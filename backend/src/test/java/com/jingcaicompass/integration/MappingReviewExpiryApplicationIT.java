package com.jingcaicompass.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jingcaicompass.match.dto.MappingReviewConfirmDto;
import com.jingcaicompass.match.dto.MappingReviewListQueryDto;
import com.jingcaicompass.match.enums.MappingReviewScopeEnum;
import com.jingcaicompass.match.enums.MappingStatusEnum;
import com.jingcaicompass.match.service.MatchMappingReviewService;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Timestamp;
import java.time.Instant;
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

/** PostgreSQL 16 验证比赛映射复核的当前与历史时效边界。 */
@Testcontainers
@ActiveProfiles("integration")
@SpringBootTest
class MappingReviewExpiryApplicationIT {

    private static final AtomicLong KEY_SEQUENCE = new AtomicLong(20_800_000L);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("jingcai_mapping_review_expiry")
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
    private MatchMappingReviewService matchMappingReviewService;

    @BeforeEach
    void preparesIsolatedPostgres() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertThat(metadata.getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(metadata.getDatabaseMajorVersion()).isEqualTo(16);
        }
        jdbcTemplate.execute("TRUNCATE TABLE match_source_mappings, matches, leagues RESTART IDENTITY CASCADE");
    }

    @Test
    void defaultsToFutureMatchesKeepsHistorySeparateAndRejectsExpiredConfirmation() {
        long leagueId = insertLeague();
        long historicalMatchId = insertMatch(leagueId, Instant.now().minusSeconds(3_600));
        long activeMatchId = insertMatch(leagueId, Instant.now().plusSeconds(3_600));
        long historicalMappingId = insertPendingMapping(historicalMatchId, "history");
        insertPendingMapping(activeMatchId, "active");

        var activePage = matchMappingReviewService.listByMatch(
                new MappingReviewListQueryDto("T208_EXPIRY", MappingStatusEnum.PENDING, null, 1, 20)
        );
        var historyPage = matchMappingReviewService.listByMatch(
                new MappingReviewListQueryDto(
                        "T208_EXPIRY", MappingStatusEnum.PENDING, MappingReviewScopeEnum.HISTORY, 1, 20
                )
        );

        assertThat(activePage.records()).extracting(item -> item.match().matchId()).containsExactly(activeMatchId);
        assertThat(historyPage.records()).extracting(item -> item.match().matchId()).containsExactly(historicalMatchId);
        assertThatThrownBy(() -> matchMappingReviewService.confirm(
                new MappingReviewConfirmDto(historicalMappingId, null), "reviewer"
        )).isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.MAPPING_REVIEW_EXPIRED);
    }

    private long insertLeague() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO leagues (name_zh) VALUES (?) RETURNING id",
                Long.class,
                "T208 时效联赛-" + KEY_SEQUENCE.incrementAndGet()
        );
    }

    private long insertMatch(long leagueId, Instant kickoffTime) {
        long key = KEY_SEQUENCE.incrementAndGet();
        return jdbcTemplate.queryForObject("""
                INSERT INTO matches (
                    lottery_match_no, lottery_date, league_id, league_name, home_team_name,
                    away_team_name, kickoff_time, match_status
                ) VALUES (?, CURRENT_DATE, ?, 'T208 时效联赛', 'T208 主队', 'T208 客队', ?, 'SCHEDULED')
                RETURNING id
                """, Long.class, "T208-" + key, leagueId, Timestamp.from(kickoffTime));
    }

    private long insertPendingMapping(long matchId, String suffix) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO match_source_mappings (
                    match_id, provider_code, external_match_id, mapping_status, mapping_confidence,
                    mapping_method, mapping_explanation
                ) VALUES (?, 'T208_EXPIRY', ?, 'PENDING', 0.5000, 'SCORE_PENDING', 'T208 expiry test')
                RETURNING id
                """, Long.class, matchId, "T208-expiry-" + suffix + "-" + KEY_SEQUENCE.incrementAndGet());
    }
}
