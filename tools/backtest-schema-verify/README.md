# 回测迁移的本地验证

无 Docker 时（本机没有 Testcontainers 依赖的容器运行时），Flyway 迁移的语法与约束行为只能用
生产库「单事务执行 → 断言 → 回滚」来验。这个目录就是那套验证，不参与产品构建。

```bash
# 1) 只验语法
java -cp <pg.jar> tools/backtest-schema-verify/MigrateDryRun.java \
  backend/src/main/resources/db/migration/V29__add_historical_backtest.sql

# 2) 验约束与触发器行为（12 条断言，全部在事务内，结尾 ROLLBACK）
cat backend/src/main/resources/db/migration/V29__add_historical_backtest.sql \
    tools/backtest-schema-verify/v29-verify.sql > /tmp/combined.sql
java -cp <pg.jar> tools/backtest-schema-verify/MigrateDryRun.java /tmp/combined.sql
```

注意：这只能证明「约束与触发器按预期拒绝/放行」，**不能**替代 CI 里的
`BacktestMigrationIT`——`flyway("<target>")` 从空库重放 V1~V29 时才暴露声明矛盾类缺陷
（例如某条 CHECK 与既存数据冲突、约束名重复）。
