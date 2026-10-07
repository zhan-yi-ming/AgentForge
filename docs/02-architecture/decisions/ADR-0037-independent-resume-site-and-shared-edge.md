# ADR-0037：独立简历产品与共享域名入口

- 日期：2026-10-07
- 状态：Proposed
- 范围：独立新产品与未来同机入口迁移；本次不修改部署
- 不取代：AgentForge 现有 Java/Python 职责、认证和数据边界
- 实施蓝图：[简历模板网站与管理后台](../../01-product/resume-platform-implementation-plan.md)

## 背景

用户希望将 AgentForge 保留为技术作品，迁至 agentforge 子域名，主站建设免费简历模板工具。模板需要后台动态发布，并为未来支付、AI 生图与多轮对话生成 HTML 保留边界。本次仅授权技术计划。

现有生产 Compose 的 gateway 独占 80/443；TLS 脚本对非 www 开头的域名自动追加 www。直接复制第二套公网网关或只替换 PUBLIC_HOST 会产生端口争用、错误证书域名和续期所有权不清的问题。

## 提议决定

1. 简历网站使用独立仓库、React 前端、FastAPI 管理后端。后台保存管理员、模板版本、站点配置及有限运营数据；访客简历正文留在浏览器。
2. P1 使用 SQLAlchemy/Alembic + SQLite 持久卷，限定单 API worker、低并发写入与一致性备份；付费/多实例前迁 PostgreSQL。此选择不变更 AgentForge 的 PostgreSQL/Java 架构。
3. 模板采用版本化声明式 JSON，前端执行已发布的受控渲染组件；后台不能发布脚本或任意 HTML。模板样式修改无需前端构建，新组件需发布代码。
4. 草稿与发布快照分离，发布原子切换目录版本。ETag + 活跃页面 30 秒再验证满足首版动态更新；编辑中的本地文档锁定模板版本，用户确认后升级。
5. 同机部署时由独立 site-edge 独占公网 80/443、TLS/ACME 与续期；将旧 gateway 改为可选 standalone 模式，共享入口完整保留旧 API、SSE、MCP、Grafana 和限流策略。
6. 不共享用户身份、数据库表、会话、密钥或业务接口。旧 AgentForge Java 确定性执行 / Python Agent 边界不变。
7. 支付、AI、任务队列、普通用户账号只记录扩展点；P1 不建表、不接 SDK、不开放伪功能。

## 替代方案与取舍

- 纯静态站：不能满足用户新增的后台动态更新需求。
- 任意 HTML 模板热更新：增加同源脚本、隐私与渲染执行风险，不适用于 P1。
- 复用 AgentForge 的管理员/数据库：耦合两个产品发布与权限，收益不足。
- P1 新增 PostgreSQL/Redis：对低并发模板发布不必要；SQLite 的单实例限制需要在文档中明确。
- 在旧 gateway 前再串一个 HTTPS 网关：双层限流、真实 IP 和双重续期复杂，选直接共享边缘分流。

## 后果与验证

共享 edge 是共同故障点，配置需版本化、nginx -t、快速回滚，并验证一个应用停止不影响另一站。旧 deploy/update/rollback/health-check 和证书 timer 必须整体适配 external-edge 模式，不能仅手改 ports。

正式实施时重新做风险评估：新站认证/数据/发布契约为 L3；旧站迁移按部署、TLS、认证与跨服务影响运行原仓库相称门禁。现有运行配置与 Accepted ADR 继续生效，直到新迁移独立验收并正式接受此决策。
