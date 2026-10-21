package com.jingcaicompass.settlement.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.jingcaicompass.prediction.enums.PredictionTypeEnum;
import com.jingcaicompass.settlement.enums.MarketTypeEnum;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

/**
 * 静态守卫结算候选查询与市场枚举的一致性。
 *
 * <p>这些查询决定"哪些预测会被结算任务取到"。一旦某个市场或某类预测只存在于
 * {@link MarketTypeEnum#marketsFor} 而不在 SQL 里，对应预测会静默停在 {@code LOCKED}：
 * 单测直接调用写入器不会暴露，只有真实跑任务才发现。故用注解文本断言把它钉住。</p>
 */
class SettlementMapperSqlContractTest {

    /** 五个按预测类型分派的候选查询。 */
    private static final List<String> TYPE_AWARE_QUERIES = List.of(
            "selectPendingLockedPredictionIds",
            "selectPendingLockedPredictionIdsByMatchId",
            "selectOutdatedLockedPredictionIds",
            "selectOutdatedLockedPredictionIdsByMatchId",
            "countOverdueSettlementBacklog"
    );

    /** 需要逐个市场判定是否已结算的候选查询。 */
    private static final List<String> MARKET_AWARE_QUERIES = List.of(
            "selectPendingLockedPredictionIds",
            "selectPendingLockedPredictionIdsByMatchId",
            "countOverdueSettlementBacklog"
    );

    @Test
    void everyCandidateQueryDispatchesOnPredictionType() {
        for (String methodName : TYPE_AWARE_QUERIES) {
            assertThat(sqlOf(methodName))
                    .as("%s 必须同时覆盖体彩与亚盘预测", methodName)
                    .contains("'SPORTTERY'")
                    .contains("'ASIAN'");
        }
    }

    @Test
    void marketAwareCandidateQueriesCoverEveryMarketOfEveryPredictionType() {
        for (String methodName : MARKET_AWARE_QUERIES) {
            String sql = sqlOf(methodName);
            for (PredictionTypeEnum predictionType : PredictionTypeEnum.values()) {
                for (MarketTypeEnum marketType : MarketTypeEnum.marketsFor(predictionType)) {
                    assertThat(sql)
                            .as("%s 缺少 %s 预测的 %s 市场", methodName, predictionType, marketType)
                            .contains("'" + marketType.getCode() + "'");
                }
            }
        }
    }

    @Test
    void asianCandidateQueriesRequireOnlyThePickedDirections() {
        for (String methodName : MARKET_AWARE_QUERIES) {
            assertThat(sqlOf(methodName))
                    .as("%s 必须按亚盘方向判定待结算市场，否则单方向预测会永久空转", methodName)
                    .contains("asian_handicap_pick IS NOT NULL")
                    .contains("total_goals_pick IS NOT NULL");
        }
    }

    @Test
    void singleMarketLookupKeepsMarketParameter() {
        assertThat(sqlOf("selectCurrentByPredictionIdAndMarket"))
                .contains("#{marketType}");
    }

    private String sqlOf(String methodName) {
        Method method = Arrays.stream(SettlementMapper.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到结算查询方法: " + methodName));
        Select select = method.getAnnotation(Select.class);
        assertThat(select).as("%s 必须使用 @Select 注解", methodName).isNotNull();
        return String.join("\n", select.value());
    }
}
