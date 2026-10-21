package com.jingcaicompass.odds.mapper;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 静态守卫跨庄聚合取数查询。
 *
 * <p>这两条查询用了 {@code DISTINCT ON} 加多列排序，注解文本一旦与去重维度或排序不匹配，
 * 结果会「看起来正常但取的庄家/线不对」，而这种偏差只在主盘选择阶段才暴露。故在纯单测里钉住
 * 去重维度、确认映射条件、正价格过滤与排序收尾。</p>
 */
class AsianOddsSnapshotMapperSqlContractTest {

    private static final List<String> AGGREGATION_QUERIES = List.of(
            "selectLatestConfirmedHandicapLinesByMatchIds",
            "selectLatestConfirmedTotalsLinesByMatchIds"
    );

    @Test
    void handicapQueryDistinctsOnBookmakerAndHandicapLine() {
        assertThat(sqlOf("selectLatestConfirmedHandicapLinesByMatchIds"))
                .contains("DISTINCT ON (")
                .contains("snapshot.match_id")
                .contains("snapshot.provider_code")
                .contains("snapshot.bookmaker_code")
                .contains("snapshot.handicap_line");
    }

    @Test
    void totalsQueryDistinctsOnTotalLineRatherThanHandicapLine() {
        String sql = sqlOf("selectLatestConfirmedTotalsLinesByMatchIds");

        // 按 handicap_line 去重会让同一家庄的同一档大小球重复计数，必须按 total_line。
        assertThat(sql).contains("snapshot.total_line")
                .contains("snapshot.total_line IS NOT NULL")
                .doesNotContain("snapshot.handicap_line");
    }

    @Test
    void bothQueriesRequireConfirmedMappings() {
        for (String methodName : AGGREGATION_QUERIES) {
            assertThat(sqlOf(methodName))
                    .as("%s 只能使用已确认映射的比赛", methodName)
                    .contains("'AUTO_CONFIRMED'")
                    .contains("'MANUAL_CONFIRMED'");
        }
    }

    @Test
    void bothQueriesFilterNonPositivePrices() {
        assertThat(sqlOf("selectLatestConfirmedHandicapLinesByMatchIds"))
                .contains("snapshot.home_odds > 0")
                .contains("snapshot.away_odds > 0");
        assertThat(sqlOf("selectLatestConfirmedTotalsLinesByMatchIds"))
                .contains("snapshot.over_odds > 0")
                .contains("snapshot.under_odds > 0");
    }

    @Test
    void bothQueriesOrderByCaptureThenIdForDeterminism() {
        for (String methodName : AGGREGATION_QUERIES) {
            String sql = sqlOf(methodName);
            assertThat(sql)
                    .as("%s 的 ORDER BY 必须以 captured_at DESC, id DESC 收尾", methodName)
                    .contains("snapshot.captured_at DESC, snapshot.id DESC");
            assertThat(sql)
                    .as("%s 的 ORDER BY 前缀必须覆盖 DISTINCT ON 的全部列", methodName)
                    .contains("ORDER BY snapshot.match_id, snapshot.provider_code, snapshot.bookmaker_code");
        }
    }

    private String sqlOf(String methodName) {
        Method method = Arrays.stream(AsianOddsSnapshotMapper.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到亚盘快照查询方法: " + methodName));
        Select select = method.getAnnotation(Select.class);
        assertThat(select).as("%s 必须使用 @Select 注解", methodName).isNotNull();
        return String.join("\n", select.value());
    }
}
