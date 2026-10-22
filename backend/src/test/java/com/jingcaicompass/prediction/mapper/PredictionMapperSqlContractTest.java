package com.jingcaicompass.prediction.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

/**
 * 静态守卫自动发布用的四条 SQL。
 *
 * <p>这四条查询的错误方式全是静默的：去重方向写反会让公开版本每轮翻新一次；漏掉
 * {@code NOT EXISTS} 会让同一场重复发布；漏掉 {@code kickoff_time > now} 会让开赛后的
 * 场次也进候选（发布服务随后抛「截止时间已过」，整批记 PARTIAL，看起来像偶发故障）；
 * 失败落库语句多碰一个内容列就会被不可变触发器拒绝。</p>
 */
class PredictionMapperSqlContractTest {

    @Test
    void candidateQueryTakesSmallestVersionPerMatchAndModel() {
        String sql = sqlOf("selectAutoPublishCandidateDrafts");

        // DISTINCT ON 与 ORDER BY 必须同序，且版本必须是 ASC：
        // 写成 DESC 就会永远发布最新一版，赔率每抖一次公开版本就 +1
        assertThat(sql)
                .contains("DISTINCT ON (p.match_id, p.model_version)")
                .contains("ORDER BY p.match_id")
                .contains("p.model_version ASC")
                .contains("p.prediction_version ASC");
        assertThat(sql)
                .as("候选必须限定为 DRAFT")
                .contains("p.prediction_status = 'DRAFT'");
    }

    @Test
    void candidateQuerySkipsMatchModelsThatAlreadyPublished() {
        String sql = sqlOf("selectAutoPublishCandidateDrafts");

        assertThat(sql)
                .contains("NOT EXISTS")
                .contains("q.prediction_status IN ('PUBLISHED', 'LOCKED')");
    }

    @Test
    void candidateQueryOnlyIncludesMatchesThatHaveNotKickedOff() {
        String sql = sqlOf("selectAutoPublishCandidateDrafts");

        // 开赛后发布必然被发布服务拒绝，但拒绝会走失败落库并让整轮记 PARTIAL，
        // 与真正的发布故障混在一起；在候选阶段就排除掉才是干净的
        assertThat(sql)
                .contains("m.lottery_date = #{lotteryDate}")
                .contains("m.kickoff_time > #{now}");
    }

    @Test
    void pendingCountMatchesAlreadyPublishedOnly() {
        String sql = sqlOf("countUnpublishedMatchesByLotteryDate");

        assertThat(sql)
                .contains("m.lottery_date = #{lotteryDate}")
                .contains("m.kickoff_time > #{now}")
                .contains("NOT EXISTS")
                .contains("p.prediction_status IN ('PUBLISHED', 'LOCKED')");
    }

    @Test
    void latestGeneratedAtUsesMaxGeneratedAtOfTheBusinessDate() {
        String sql = sqlOf("selectLatestGeneratedAtByLotteryDate");

        // 生成节流靠这张表的 generated_at；用 created_at 会被导入延迟干扰
        assertThat(sql)
                .contains("MAX(p.generated_at)")
                .contains("INNER JOIN matches m ON m.id = p.match_id")
                .contains("m.lottery_date = #{lotteryDate}");
    }

    @Test
    void failureRecordingOnlyTouchesMutableColumns() {
        String sql = updateSqlOf("recordPublishFailure");

        assertThat(sql)
                .contains("publish_attempt_count = publish_attempt_count + 1")
                .contains("last_publish_error = #{error}");

        // 触发器 protect_published_prediction() 锁死了内容列；多碰一列会让
        // 「记录失败原因」这件事本身失败，把诊断信息一起丢掉
        assertThat(sql)
                .as("失败落库不得触碰任何不可变内容列")
                .doesNotContain("home_win_prob")
                .doesNotContain("draw_prob")
                .doesNotContain("away_win_prob")
                .doesNotContain("prediction_status")
                .doesNotContain("prediction_hash")
                .doesNotContain("generated_at");
    }

    private String sqlOf(String methodName) {
        Method method = findMethod(methodName);
        Select select = method.getAnnotation(Select.class);
        assertThat(select).as("%s 必须使用 @Select 注解", methodName).isNotNull();
        return String.join("\n", select.value());
    }

    private String updateSqlOf(String methodName) {
        Method method = findMethod(methodName);
        Update update = method.getAnnotation(Update.class);
        assertThat(update).as("%s 必须使用 @Update 注解", methodName).isNotNull();
        return String.join("\n", update.value());
    }

    private Method findMethod(String methodName) {
        return Arrays.stream(PredictionMapper.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到预测 Mapper 方法: " + methodName));
    }
}
