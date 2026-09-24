# ADR-0029：MCP Adapter 必须复用 Java Tool Policy 与 Approval

- 状态：Accepted
- 日期：2026-09-24
- 决策者：项目维护者

## 背景

MCP 可以把 Tool 发现和调用从具体 Agent/模型中解耦，但若 Adapter 直接访问 Repository/数据库，或把一次 Tool Call 当成人工批准，会破坏 V2 已建立的 Java 权限、风险、审批、幂等和审计边界。当前最新 MCP 规范为 2026-07-28，而官方 Java SDK 2.0.1 的稳定支持停在 2025-11-25。

## 备选方案

- MCP Adapter 直连数据库：实现快，但完全绕过业务服务与安全体系，拒绝。
- 自行实现 MCP 2026-07-28：可以追最新协议，但会承担未由 Java SDK 覆盖的传输、生命周期和兼容风险，拒绝。
- 使用官方 Java SDK 2.0.1 / MCP 2025-11-25，在 Core API 内把 Tool 映射到现有 Application Service：版本较旧且首版认证需预配置 JWT，但边界清晰、可验证、可升级。

## 决策

选择第三种方案。MCP 只提供 Tools 能力和 Streamable HTTP。读 Tool 直接调用已授权 Application Service；写 Tool 创建 `source=MCP` 的持久化 PENDING Approval，不绑定 Chat conversation 或 LangGraph checkpoint，不在 MCP 调用中执行业务写入。人工 confirm/reject 继续使用既有 Core API；执行前再次复核 Java Tool Policy、ProjectAccess、actor、版本和幂等键。 MCP 写 Tool 另要求客户端提供提案幂等键，Java 在 project/user/source 作用域内串行化并复用相同意图的 Approval；同 key 不同意图冲突。MCP Action 禁止进入 Web 的低风险超时自动确认入口。

首版 `/mcp` 接受现有 AgentForge Bearer JWT，明确记录为预配置凭据模式，不宣称完整 MCP OAuth 自动发现。未来引入 OAuth Authorization Server 或 Java SDK 支持 2026-07-28 时另立迁移决策。

## 结果

外部 Agent 获得标准 Tool 接口，业务安全事实仍集中在 Java。代价是写操作需要 AgentForge 侧人工决定，且通用 MCP 客户端必须预配置 JWT；协议升级和 OAuth 互操作留作独立工作。

## 取代关系

扩展 ADR-0019 与 ADR-0021，不取代现有 Java/Python、Tool Policy 或 Approval 决策。
