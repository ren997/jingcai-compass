package com.jingcaicompass.backtest.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.jingcaicompass.backtest.entity.BacktestCaseEntity;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.ibatis.annotations.Insert;
import org.junit.jupiter.api.Test;

/**
 * 手写 INSERT 的列清单守门。
 *
 * <p>{@code insertIgnore} 是一段固定列数的 SQL，不走 MyBatis-Plus 的自动映射。
 * 于是「Entity 加了字段、Builder 也填了值」看起来一切正常，但值根本写不进库——
 * 症状是那一列永远为 null，不报错、不警告。{@code infeasibility_reason} 就是这样
 * 白跑了一整轮八月回测才发现。</p>
 *
 * <p>所以这条断言不针对某一列，而是针对所有列：Entity 里每个会被 INSERT 的字段，
 * 都必须在 SQL 里出现。以后再加列忘了改这里，测试会先挂，不用等一轮回测。</p>
 */
class BacktestCaseMapperSqlTest {

    /**
     * 不进 INSERT 的字段。
     *
     * <p>{@code id} 自增；{@code createdAt} 由 {@code FieldFill.INSERT} + DB 默认值兜；
     * 其余七列属于结算阶段，由 {@code updateSettlement} 单独写入，建样本时本就该为空。</p>
     */
    private static final Set<String> NOT_IN_INSERT = Set.of(
            "id", "createdAt",
            "homeScore", "awayScore",
            "handicapStatus", "handicapProfit",
            "totalsStatus", "totalsProfit", "settledAt");

    @Test
    void everyPersistedFieldAppearsInInsertIgnoreSql() throws Exception {
        String sql = insertSql();

        List<String> missing = new ArrayList<>();
        for (Field field : BacktestCaseEntity.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }
            String name = field.getName();
            if (NOT_IN_INSERT.contains(name)) {
                continue;
            }
            String column = toSnakeCase(name);
            if (!sql.contains(column)) {
                missing.add(name + " -> " + column);
            }
        }

        assertThat(missing)
                .as("这些字段在 Entity 上但不在 insertIgnore 的列清单里，值会被静默丢弃")
                .isEmpty();
    }

    @Test
    void bindsInfeasibilityReason() {
        // 单独钉一条：它的缺失不会让任何测试失败，只会让「为什么没发」查不出细分，
        // 而那正是这一列存在的全部理由。
        assertThat(insertSql()).contains("infeasibility_reason")
                .contains("#{item.infeasibilityReason}");
    }

    private static String insertSql() {
        try {
            Insert insert = BacktestCaseMapper.class
                    .getMethod("insertIgnore", BacktestCaseEntity.class)
                    .getAnnotation(Insert.class);
            assertThat(insert).as("insertIgnore 必须仍带 @Insert").isNotNull();
            return String.join(" ", insert.value());
        } catch (NoSuchMethodException e) {
            throw new AssertionError("找不到 insertIgnore(BacktestCaseEntity)", e);
        }
    }

    /** 驼峰转下划线；只处理小写字母 + 数字，够覆盖本实体。 */
    private static String toSnakeCase(String name) {
        StringBuilder out = new StringBuilder(name.length() + 4);
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (Character.isUpperCase(ch)) {
                out.append('_').append(Character.toLowerCase(ch));
            } else {
                out.append(ch);
            }
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }
}
