# 架构决策记录

ADR 保存影响长期结构的决定。编号只增不减，Accepted 记录不通过覆写“改历史”；若改变决定，新建 ADR 并声明取代关系。

## 索引

- `ADR-0001-documentation-first.md`：所有实现变化必须先有文档变化。
- `ADR-0002-monorepo-multiple-applications.md`：采用单仓库、多应用目录。
- `ADR-0003-java-modular-monolith.md`：Java 采用按业务能力分包的模块化单体。
- `ADR-0004-versioned-database-migrations.md`：PostgreSQL schema 由 Flyway 版本化迁移管理。
