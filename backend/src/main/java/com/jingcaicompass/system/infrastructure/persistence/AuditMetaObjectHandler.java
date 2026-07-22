package com.jingcaicompass.system.infrastructure.persistence;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;

import java.time.Instant;

public class AuditMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        Instant now = Instant.now();
        if (metaObject.hasSetter("createdAt") && getFieldValByName("createdAt", metaObject) == null) {
            setFieldValByName("createdAt", now, metaObject);
        }
        if (metaObject.hasSetter("updatedAt") && getFieldValByName("updatedAt", metaObject) == null) {
            setFieldValByName("updatedAt", now, metaObject);
        }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        if (metaObject.hasSetter("updatedAt")) {
            setFieldValByName("updatedAt", Instant.now(), metaObject);
        }
    }
}
