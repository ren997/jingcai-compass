package com.jingcaicompass.backtest.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.jingcaicompass.backtest.entity.BacktestMetricEntity;
import com.jingcaicompass.system.infrastructure.persistence.PostgresJsonbTypeHandler;
import com.baomidou.mybatisplus.annotation.TableField;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 守住 {@code backtest_metrics} 两个 jsonb 列的可读性。
 *
 * <p>这个坑已经踩过两次（归档的 {@code payload}、指标的 {@code skip_reason_counts}），
 * 两次表现都一样：<b>写库正常、读回 {@code null}、不抛任何异常</b>。第一次的表现是
 * 「切片恒 0 行」，第二次是「详情页整页白屏」（前端 {@code Object.keys(null)} 抛 TypeError
 * 被错误边界接住）。两次都不是数据库的问题，而是 {@code @Select} 走原生映射、
 * 不应用实体上的 typeHandler——{@code @TableName(autoResultMap = true)} 生成的那份 resultMap
 * 只有 MyBatis-Plus 自己拼的 SQL 才会用。</p>
 *
 * <p>反射测试而不是集成测试：这条约定的破坏方式只有「有人把 default 方法改成
 * {@code @Select}」和「有人为了让它能读而删掉 typeHandler」，两者都在编译期不可见、
 * 在纯单测里也不可见，只会在真实库上以「某一列悄悄变 null」的形式出现。</p>
 */
class BacktestMetricsMappingTest {

    @Test
    @DisplayName("selectByRunId 不走注解查询，否则 jsonb 列会被原生映射吞成 null")
    void selectByRunIdMustNotUseNativeQuery() throws NoSuchMethodException {
        Method method = BacktestMetricMapper.class.getMethod("selectByRunId", Long.class);

        assertThat(method.getAnnotation(Select.class))
                .as("@Select 会绕过 autoResultMap，skip_reason_counts / metrics_json 会读回 null 且不报错")
                .isNull();
        assertThat(method.isDefault())
                .as("应当是 default 方法里的 selectList(wrapper)，让 MyBatis-Plus 自己拼 SQL")
                .isTrue();
    }

    @Test
    @DisplayName("两个 jsonb 字段必须继续声明 typeHandler，否则写入会退化成字符串")
    void jsonbFieldsKeepTheirTypeHandler() throws NoSuchFieldException {
        assertJsonbField("skipReasonCounts");
        assertJsonbField("metricsJson");
    }

    private static void assertJsonbField(String name) throws NoSuchFieldException {
        Field field = BacktestMetricEntity.class.getDeclaredField(name);
        TableField annotation = field.getAnnotation(TableField.class);

        assertThat(annotation)
                .as("%s 必须保留 @TableField(typeHandler = ...)，否则 jsonb 写入会按 VARCHAR 绑定", name)
                .isNotNull();
        assertThat(annotation.typeHandler())
                .as("%s 的 typeHandler 必须是 PostgresJsonbTypeHandler", name)
                .isEqualTo(PostgresJsonbTypeHandler.class);
    }
}
