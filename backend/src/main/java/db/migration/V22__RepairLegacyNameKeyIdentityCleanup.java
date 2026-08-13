package db.migration;

import com.jingcaicompass.match.support.NameNormalizationSupport;
import com.jingcaicompass.match.support.ProviderEntityKeySupport;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * 补偿 V19 无法由展示名和时间戳证明的名称键待复核身份，只清理唯一匹配的无引用临时实体。
 */
public class V22__RepairLegacyNameKeyIdentityCleanup extends BaseJavaMigration {

    private static final String PROVIDER_CODE = "THE_ODDS_API";
    private static final String LEGACY_METHOD = "LEGACY_NAME_CANDIDATE_REVIEW_REQUIRED";
    private static final String SCOPED_NAME_PREFIX = "SCOPED_NAME:";

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        // 1) 仅重建 V17 已解绑且仍待人工复核的 The Odds 历史身份。
        cleanupEntities(connection, EntityType.LEAGUE);
        cleanupEntities(connection, EntityType.TEAM);
    }

    private void cleanupEntities(Connection connection, EntityType entityType) throws SQLException {
        List<LegacyMapping> mappings = loadLegacyMappings(connection, entityType);
        List<InternalEntity> candidates = loadOrphanCandidates(connection, entityType);
        Map<Long, Set<Long>> candidateIdsByMappingId = new HashMap<>();
        Map<Long, Set<Long>> mappingIdsByCandidateId = new HashMap<>();

        // 2) 通过旧 NAME/SCOPED_NAME 哈希与当前规范化规则恢复唯一候选，拒绝模糊匹配。
        for (LegacyMapping mapping : mappings) {
            for (InternalEntity candidate : candidates) {
                if (!matches(mapping, candidate)) {
                    continue;
                }
                candidateIdsByMappingId
                        .computeIfAbsent(mapping.id(), ignored -> new HashSet<>())
                        .add(candidate.id());
                mappingIdsByCandidateId
                        .computeIfAbsent(candidate.id(), ignored -> new HashSet<>())
                        .add(mapping.id());
            }
        }

        // 3) 删除前再次由 SQL 检查引用；并发写入或任何歧义时宁可保留。
        for (LegacyMapping mapping : mappings) {
            Set<Long> candidateIds = candidateIdsByMappingId.getOrDefault(mapping.id(), Set.of());
            if (candidateIds.size() != 1) {
                continue;
            }
            Long candidateId = candidateIds.iterator().next();
            if (mappingIdsByCandidateId.getOrDefault(candidateId, Set.of()).size() == 1) {
                deleteIfStillOrphaned(connection, entityType, candidateId);
            }
        }
    }

    private List<LegacyMapping> loadLegacyMappings(Connection connection, EntityType entityType)
            throws SQLException {
        String sql = """
                SELECT id, %s, external_scope
                FROM %s
                WHERE provider_code = ?
                  AND mapping_status = 'PENDING'
                  AND mapping_method = ?
                  AND %s IS NULL
                """.formatted(
                entityType.externalIdColumn(), entityType.mappingTable(), entityType.entityIdColumn()
        );
        List<LegacyMapping> mappings = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, PROVIDER_CODE);
            statement.setString(2, LEGACY_METHOD);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    mappings.add(new LegacyMapping(
                            resultSet.getLong("id"),
                            resultSet.getString(2),
                            resultSet.getString("external_scope")
                    ));
                }
            }
        }
        return mappings;
    }

    private List<InternalEntity> loadOrphanCandidates(Connection connection, EntityType entityType)
            throws SQLException {
        List<InternalEntity> candidates = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(entityType.orphanCandidateSql());
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                candidates.add(new InternalEntity(
                        resultSet.getLong("id"),
                        resultSet.getString("name_zh"),
                        resultSet.getString("name_en")
                ));
            }
        }
        return candidates;
    }

    private boolean matches(LegacyMapping mapping, InternalEntity candidate) {
        if (mapping.externalId() == null) {
            return false;
        }
        for (String name : candidate.names()) {
            if (NameNormalizationSupport.normalizedKey(name).isEmpty()) {
                continue;
            }
            if (mapping.externalId().equals(ProviderEntityKeySupport.nameKey(name))) {
                return true;
            }
            if (mapping.externalId().startsWith(SCOPED_NAME_PREFIX)
                    && mapping.scope() != null
                    && !NameNormalizationSupport.normalizedKey(mapping.scope()).isEmpty()
                    && mapping.externalId().equals(ProviderEntityKeySupport.scopedNameKey(mapping.scope(), name))) {
                return true;
            }
        }
        return false;
    }

    private void deleteIfStillOrphaned(Connection connection, EntityType entityType, Long entityId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(entityType.deleteIfOrphanedSql())) {
            statement.setLong(1, entityId);
            statement.executeUpdate();
        }
    }

    private record LegacyMapping(Long id, String externalId, String scope) {
    }

    private record InternalEntity(Long id, String nameZh, String nameEn) {

        private List<String> names() {
            List<String> names = new ArrayList<>(2);
            names.add(nameZh);
            names.add(nameEn);
            return names;
        }
    }

    private enum EntityType {
        LEAGUE(
                "provider_league_mappings",
                "league_id",
                "external_league_id",
                """
                        SELECT league.id, league.name_zh, league.name_en
                        FROM leagues league
                        WHERE NOT EXISTS (SELECT 1 FROM matches match WHERE match.league_id = league.id)
                          AND NOT EXISTS (SELECT 1 FROM league_aliases alias WHERE alias.league_id = league.id)
                          AND NOT EXISTS (SELECT 1 FROM provider_league_mappings mapping WHERE mapping.league_id = league.id)
                        """,
                """
                        DELETE FROM leagues league
                        WHERE league.id = ?
                          AND NOT EXISTS (SELECT 1 FROM matches match WHERE match.league_id = league.id)
                          AND NOT EXISTS (SELECT 1 FROM league_aliases alias WHERE alias.league_id = league.id)
                          AND NOT EXISTS (SELECT 1 FROM provider_league_mappings mapping WHERE mapping.league_id = league.id)
                        """
        ),
        TEAM(
                "provider_team_mappings",
                "team_id",
                "external_team_id",
                """
                        SELECT team.id, team.name_zh, team.name_en
                        FROM teams team
                        WHERE NOT EXISTS (SELECT 1 FROM matches match
                                            WHERE match.home_team_id = team.id OR match.away_team_id = team.id)
                          AND NOT EXISTS (SELECT 1 FROM team_aliases alias WHERE alias.team_id = team.id)
                          AND NOT EXISTS (SELECT 1 FROM provider_team_mappings mapping WHERE mapping.team_id = team.id)
                        """,
                """
                        DELETE FROM teams team
                        WHERE team.id = ?
                          AND NOT EXISTS (SELECT 1 FROM matches match
                                            WHERE match.home_team_id = team.id OR match.away_team_id = team.id)
                          AND NOT EXISTS (SELECT 1 FROM team_aliases alias WHERE alias.team_id = team.id)
                          AND NOT EXISTS (SELECT 1 FROM provider_team_mappings mapping WHERE mapping.team_id = team.id)
                        """
        );

        private final String mappingTable;
        private final String entityIdColumn;
        private final String externalIdColumn;
        private final String orphanCandidateSql;
        private final String deleteIfOrphanedSql;

        EntityType(
                String mappingTable,
                String entityIdColumn,
                String externalIdColumn,
                String orphanCandidateSql,
                String deleteIfOrphanedSql
        ) {
            this.mappingTable = mappingTable;
            this.entityIdColumn = entityIdColumn;
            this.externalIdColumn = externalIdColumn;
            this.orphanCandidateSql = orphanCandidateSql;
            this.deleteIfOrphanedSql = deleteIfOrphanedSql;
        }

        private String mappingTable() {
            return mappingTable;
        }

        private String entityIdColumn() {
            return entityIdColumn;
        }

        private String externalIdColumn() {
            return externalIdColumn;
        }

        private String orphanCandidateSql() {
            return orphanCandidateSql;
        }

        private String deleteIfOrphanedSql() {
            return deleteIfOrphanedSql;
        }
    }
}
