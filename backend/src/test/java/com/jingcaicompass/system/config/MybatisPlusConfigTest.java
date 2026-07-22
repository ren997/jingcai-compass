package com.jingcaicompass.system.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.jingcaicompass.system.config.properties.PaginationProperties;
import com.jingcaicompass.system.infrastructure.persistence.AuditMetaObjectHandler;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class MybatisPlusConfigTest {

    @Test
    void capsPostgresqlPageSize() {
        MybatisPlusInterceptor interceptor = new MybatisPlusConfig()
                .mybatisPlusInterceptor(new PaginationProperties(100));

        assertThat(interceptor.getInterceptors()).singleElement()
                .isInstanceOfSatisfying(PaginationInnerInterceptor.class, pagination -> {
                    assertThat(pagination.getMaxLimit()).isEqualTo(100L);
                    assertThat(pagination.getDbType()).isEqualTo(com.baomidou.mybatisplus.annotation.DbType.POSTGRE_SQL);
                    assertThat(pagination.isOverflow()).isFalse();
                });
    }

    @Test
    void fillsCreatedAndUpdatedAuditInstants() {
        AuditMetaObjectHandler handler = new AuditMetaObjectHandler();
        AuditedEntity entity = new AuditedEntity();

        handler.insertFill(SystemMetaObject.forObject(entity));

        assertThat(entity.getCreatedAt()).isNotNull();
        assertThat(entity.getUpdatedAt()).isEqualTo(entity.getCreatedAt());

        Instant insertedUpdatedAt = entity.getUpdatedAt();
        handler.updateFill(SystemMetaObject.forObject(entity));
        assertThat(entity.getUpdatedAt()).isAfterOrEqualTo(insertedUpdatedAt);
    }

    static class AuditedEntity {

        private Instant createdAt;
        private Instant updatedAt;

        public Instant getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(Instant createdAt) {
            this.createdAt = createdAt;
        }

        public Instant getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(Instant updatedAt) {
            this.updatedAt = updatedAt;
        }
    }
}
