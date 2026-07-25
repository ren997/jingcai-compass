package com.jingcaicompass.system.infrastructure.persistence;

import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import java.lang.reflect.Field;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;

/**
 * 将 Jackson JSON 以 PostgreSQL OTHER 类型绑定，让数据库按目标列推断为 JSONB。
 *
 * <p>MyBatis-Plus 的 {@link JacksonTypeHandler} 使用 {@code setString}，PostgreSQL 不会把
 * VARCHAR 隐式转换为 JSONB；读取逻辑继续复用其字段泛型感知的 Jackson 反序列化。</p>
 */
@MappedJdbcTypes(JdbcType.OTHER)
public class PostgresJsonbTypeHandler extends JacksonTypeHandler {

    public PostgresJsonbTypeHandler(Class<?> type) {
        super(type);
    }

    public PostgresJsonbTypeHandler(Class<?> type, Field field) {
        super(type, field);
    }

    @Override
    public void setNonNullParameter(
            PreparedStatement preparedStatement,
            int index,
            Object parameter,
            JdbcType jdbcType
    ) throws SQLException {
        preparedStatement.setObject(index, toJson(parameter), Types.OTHER);
    }
}
