# 系统架构总览

- 状态：Accepted
- 当前实现范围：仓库骨架与 Core API Day 1

## 系统组件

```text
Browser
  |
  v
React Web (apps/web)
  | REST / JSON
  v
Java Core API (services/core-api) ------> PostgreSQL
  | 业务对象、权限、审批、确定性写入          业务事实
  |
  | internal REST / JSON
  v
Python Agent Service (services/agent-service) -> LLM / Retrieval
  概率性决策、上下文、RAG、Tool 意图

Redis：V1 后续用于短期会话/状态辅助，不作为业务事实来源。
```

## 组件职责

### React Web

负责 Project、Wiki、Task、AI Chat、确认交互和 Markdown 预览。浏览器不直接访问数据库，也不持有可以绕过 Core API 的业务权限。

### Java Core API

系统的业务与信任边界。它拥有用户、项目、Wiki、Task、权限、审批及最终写入。任何来自 Web 或 Agent 的修改请求都使用相同业务校验，不能因为调用方是内部服务而跳过。

### Python Agent Service

负责 LLM、LangGraph、RAG、上下文构建和 Tool Calling。它生成答案或结构化操作意图，但没有绕过 Java Core API 直接修改业务表的权限。

### PostgreSQL

保存业务事实。V1 后续在同一 PostgreSQL 上扩展 `pgvector`，避免过早增加独立向量数据库。

### Redis

仅保存可重建的缓存、短期状态或会话辅助。关键审批和业务事实不得只存在 Redis。

## 关键请求边界

- 查询：Web → Core API；需要 AI 时由 Core API 或明确受控的 Web 路径调用 Agent，具体在 Day 3 ADR 确定。
- 修改：Agent 只能返回 Tool 意图；Web 展示确认；Java 再鉴权、校验、执行并落库。
- 数据隔离：所有项目资源最终按 workspace/project 维度校验；Day 1 先建立 owner 边界，后续演进为 membership。
- 可观测：所有跨服务请求逐步携带 `request_id`；V1 使用结构化日志，V2 接入完整 Trace。

## 部署策略

V1 在一个单仓库内维护三个可独立运行的应用，但只把 Java 业务服务做成模块化单体。这样既保留清楚的语言和信任边界，也避免第一周承担微服务治理成本。

## 成熟项目参考

- [Backstage architecture overview](https://github.com/backstage/backstage/blob/master/docs/overview/architecture-overview.md)：借鉴“app 负责装配、backend 提供能力”和单仓库内显式包边界，不复制其插件复杂度。
- [Backstage getting started repository layout](https://github.com/backstage/backstage/blob/master/docs/getting-started/index.md)：借鉴根配置与可部署应用入口分离。
- [JHipster React sample](https://github.com/jhipster/jhipster-sample-app-react)：验证 React + Spring Boot 在同一产品仓库中的成熟工程组合。
- [Spring PetClinic](https://github.com/spring-projects/spring-petclinic)：借鉴可直接运行、带构建包装器和本地数据库配置的开发体验。

这些参考提供结构原则，不意味着复制其所有工具或框架。V1 只引入当前闭环必需的组件。
