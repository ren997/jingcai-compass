package com.jingcaicompass.odds.mapper;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 静态守卫亚盘快照的三条读查询。
 *
 * <p>这些查询用了 {@code DISTINCT ON} 加多列排序，注解文本一旦与去重维度或排序不匹配，
 * 结果会「看起来正常但取的庄家/线不对」，而这种偏差只在主盘选择阶段才暴露。故在纯单测里钉住
 * 去重维度、来源过滤、正价格过滤与排序收尾。</p>
 *
 * <p>单源改造后多一条守卫：<b>不得再 JOIN {@code match_source_mappings}</b>。那个 JOIN 的唯一
 * 语义是「只取已确认映射的场次」，单源下 {@code match_id} 本身就是权威键，条件恒真；
 * 留着它会让映射表永远删不掉，而且新 provider 的场次在表里没有映射行时会被静默过滤成空结果。</p>
 */
class AsianOddsSnapshotMapperSqlContractTest {

    private static final List<String> AGGREGATION_QUERIES = List.of(
            "selectLatestHandicapLinesByMatchIds",
            "selectLatestTotalsLinesByMatchIds"
    );

    private static final List<String> ALL_QUERIES = List.of(
            "selectLatestCompleteLinesByMatchIds",
            "selectLatestHandicapLinesByMatchIds",
            "selectLatestTotalsLinesByMatchIds"
    );

    @Test
    void handicapQueryDistinctsOnBookmakerAndHandicapLine() {
        assertThat(sqlOf("selectLatestHandicapLinesByMatchIds"))
                .contains("DISTINCT ON (")
                .contains("snapshot.match_id")
                .contains("snapshot.provider_code")
                .contains("snapshot.bookmaker_code")
                .contains("snapshot.handicap_line");
    }

    @Test
    void totalsQueryDistinctsOnTotalLineRatherThanHandicapLine() {
        String sql = sqlOf("selectLatestTotalsLinesByMatchIds");

        // 按 handicap_line 去重会让同一家庄的同一档大小球重复计数，必须按 total_line。
        assertThat(sql).contains("snapshot.total_line")
                .contains("snapshot.total_line IS NOT NULL")
                .doesNotContain("snapshot.handicap_line");
    }

    @Test
    void noQueryDependsOnSourceMappings() {
        for (String methodName : ALL_QUERIES) {
            assertThat(sqlOf(methodName))
                    .as("%s 不得再依赖映射层", methodName)
                    .doesNotContain("match_source_mappings")
                    .doesNotContain("INNER JOIN");
        }
    }

    @Test
    void allQueriesFilterByActiveProvider() {
        for (String methodName : ALL_QUERIES) {
            assertThat(sqlOf(methodName))
                    .as("%s 必须按当前来源隔离样本", methodName)
                    .contains("snapshot.provider_code = #{providerCode}");
        }
    }

    @Test
    void bothQueriesFilterNonPositivePrices() {
        assertThat(sqlOf("selectLatestHandicapLinesByMatchIds"))
                .contains("snapshot.home_odds > 0")
                .contains("snapshot.away_odds > 0");
        assertThat(sqlOf("selectLatestTotalsLinesByMatchIds"))
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
