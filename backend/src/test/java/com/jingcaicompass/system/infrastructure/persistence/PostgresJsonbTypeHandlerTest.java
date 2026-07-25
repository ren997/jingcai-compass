package com.jingcaicompass.system.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.Map;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;

class PostgresJsonbTypeHandlerTest {

    private final PostgresJsonbTypeHandler handler = new PostgresJsonbTypeHandler(Map.class);

    @Test
    void bindsSerializedJsonAsPostgresOtherType() throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);

        handler.setNonNullParameter(statement, 1, Map.of("provider", "stub"), JdbcType.OTHER);

        verify(statement).setObject(1, "{\"provider\":\"stub\"}", Types.OTHER);
    }

    @Test
    void reusesJacksonDeserializationForReads() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("payload")).thenReturn("{\"provider\":\"stub\"}");

        Object result = handler.getNullableResult(resultSet, "payload");

        assertThat(result).isEqualTo(Map.of("provider", "stub"));
    }
}
