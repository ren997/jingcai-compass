package com.jingcaicompass.admin.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.jingcaicompass.admin.enums.AdminPredictionLockDiagnosticEnum;
import com.jingcaicompass.admin.enums.AdminSettlementDiagnosticEnum;
import com.jingcaicompass.prediction.enums.PredictionStatusEnum;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

/**
 * 结算侧三条语句的静态 SQL 契约。
 *
 * <p>这三条语句是「列表」「总数」「待人工处理角标」。只改其中一条的后果分别是：
 * 分页 total 与 records 不一致导致翻页错乱、角标永久虚高导致运营忽略它、
 * 真正缺结算的行不排到最前。注解式 Mapper 无法复用 {@code <sql>} 片段，
 * 因此只能用逐条文本断言把它们钉在一起。
 */
class AdminPredictionStatusMapperSqlContractTest {

    private static final String ASIAN_JOIN = "market_type = 'ASIAN_HANDICAP'";
    private static final String TOTALS_JOIN = "market_type = 'ASIAN_TOTAL_GOALS'";
    private static final String ASIAN_CONDITION = "p.prediction_type = 'ASIAN'";
    private static final String SPORTTERY_CONDITION = "p.prediction_type = 'SPORTTERY'";
    private static final String HANDICAP_DIRECTION = "p.asian_handicap_pick IS NOT NULL";
    private static final String TOTALS_DIRECTION = "p.total_goals_pick IS NOT NULL";

    /** 只在 ORDER BY 与待人工处理角标里出现的合并谓词，两处必须逐字相同。 */
    private static final String NEEDS_ATTENTION =
            "(p.asian_handicap_pick IS NOT NULL AND (ah.id IS NULL OR ah.match_fact_id <> fact.id))";

    @Test
    void everySettlementStatementKnowsAllFourMarketsAndBothPredictionTypes() {
        for (String sql : settlementStatements()) {
            assertThat(sql)
                    .as("结算语句必须四市场齐全并区分产品线")
                    .contains("market_type = 'HAD'")
                    .contains("market_type = 'HHAD'")
                    .contains(ASIAN_JOIN)
                    .contains(TOTALS_JOIN)
                    .contains(SPORTTERY_CONDITION)
                    .contains(ASIAN_CONDITION)
                    .contains(HANDICAP_DIRECTION)
                    .contains(TOTALS_DIRECTION);
        }
    }

    @Test
    void asianSettlementRequiresOnlyTheMarketsThatActuallyHaveADirection() {
        for (String sql : settlementStatements()) {
            // 亚盘允许只给出单侧方向；要求两个市场都存在会让单侧方向永远判为缺失结算。
            assertThat(sql)
                    .as("亚盘缺失判定必须按方向有无分别判断")
                    .contains("(p.asian_handicap_pick IS NOT NULL AND ah.id IS NULL)")
                    .contains("(p.total_goals_pick IS NOT NULL AND atg.id IS NULL)");
        }
    }

    @Test
    void manualAttentionCountUsesTheSamePredicateAsTheNeedsAttentionOrdering() {
        Map<String, String> statements = Map.of(
                "selectSettlementPredictionIds",
                sqlOf("selectSettlementPredictionIds", settlementCriteria()),
                "countManualSettlementAttention",
                sqlOf("countManualSettlementAttention", settlementCriteria())
        );

        assertThat(statements.get("selectSettlementPredictionIds"))
                .as("排序优先级必须使用与角标相同的谓词")
                .contains("ORDER BY CASE")
                .contains(NEEDS_ATTENTION);
        assertThat(statements.get("countManualSettlementAttention"))
                .contains(NEEDS_ATTENTION);
    }

    @Test
    void settlementDiagnosticsRenderGenericCodesWithFourMarketJoins() {
        String sql = sqlOf("selectSettlementPredictionIds", settlementCriteria());

        // 诊断码已与市场解耦：三个通用分支各自渲染出一段条件，且不再出现按市场命名的旧码。
        assertThat(sql)
                // AWAITING_RESULT
                .contains("fact.id IS NULL OR fact.fact_status = 'PENDING'")
                // SETTLEMENT_MISSING，体彩要求两个市场都在
                .contains("(p.prediction_type = 'SPORTTERY' AND (had.id IS NULL OR hhad.id IS NULL))")
                // SETTLEMENT_STALE，必须先判 id 非空避免 NULL 传播
                .contains("(had.id IS NOT NULL AND had.match_fact_id <> fact.id)")
                .doesNotContain("SETTLEMENT_MISSING_HAD")
                .doesNotContain("SETTLEMENT_STALE_HHAD");
    }

    private List<String> settlementStatements() {
        AdminPredictionStatusCriteria criteria = settlementCriteria();
        return List.of(
                sqlOf("selectSettlementPredictionIds", criteria),
                sqlOf("countSettlementPredictions", criteria),
                sqlOf("countManualSettlementAttention", criteria)
        );
    }

    private AdminPredictionStatusCriteria settlementCriteria() {
        return new AdminPredictionStatusCriteria(
                LocalDate.of(2026, 7, 29),
                "model-v1",
                List.of(PredictionStatusEnum.PUBLISHED, PredictionStatusEnum.LOCKED),
                List.of(AdminPredictionLockDiagnosticEnum.LOCKED),
                List.of(
                        AdminSettlementDiagnosticEnum.AWAITING_RESULT,
                        AdminSettlementDiagnosticEnum.SETTLEMENT_MISSING,
                        AdminSettlementDiagnosticEnum.SETTLEMENT_STALE
                ),
                20,
                0L,
                Instant.parse("2026-07-29T01:00:00Z")
        );
    }

    private String sqlOf(String method, AdminPredictionStatusCriteria criteria) {
        Configuration configuration = new Configuration();
        configuration.addMapper(AdminPredictionStatusMapper.class);
        BoundSql boundSql = configuration
                .getMappedStatement(AdminPredictionStatusMapper.class.getName() + "." + method)
                .getBoundSql(Map.of("criteria", criteria));
        return boundSql.getSql().replaceAll("\\s+", " ");
    }
}
