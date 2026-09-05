# 架构决策记录

ADR 保存影响长期结构的决定。编号只增不减，Accepted 记录不通过覆写“改历史”；若改变决定，新建 ADR 并声明取代关系。

## 索引

- `ADR-0001-documentation-first.md`：所有实现变化必须先有文档变化。
- `ADR-0002-monorepo-multiple-applications.md`：采用单仓库、多应用目录。
- `ADR-0003-java-modular-monolith.md`：Java 采用按业务能力分包的模块化单体。
- `ADR-0004-versioned-database-migrations.md`：PostgreSQL schema 由 Flyway 版本化迁移管理。
- `ADR-0005-security-jwt-and-passwords.md`：V1 使用 Spring Security、短期 HS256 JWT、BCrypt 与服务端所有权校验。
- `ADR-0006-review-orchestration-loop.md`：Pi 审查循环使用本机状态、Git 报告和三次人工接管。
- `ADR-0007-autonomous-codex-pi-loop.md`：以 heartbeat 恢复 Codex 与 Pi 的跨回合协作。
- `ADR-0008-review-worker-observability-and-fix-correlation.md`：单工作树 worker、异常安全锁、可观测状态与修复提交 trailer。
- `ADR-0009-java-to-python-agent-boundary.md`：Java 鉴权后同步调用内部 Python Agent Service。
- `ADR-0010-day-4-rag-boundary-and-ranking.md`：Java 提供已授权来源，Python 管理派生索引并用 Embedding、BM25 与 RRF 检索。
