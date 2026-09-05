# 系统架构总览

- 状态：Accepted
- 当前实现范围：Day 1–3 已实现，Day 4 混合 RAG 正在实施

## 系统组件

```text
Browser
  |
  v
React Web (apps/web)
  | REST / JSON
  v
Java Core API (services/core-api) ------> PostgreSQL + pgvector
  | 业务对象、权限、审批、确定性写入          业务事实
  |
  | internal REST / JSON
  v
Python Agent Service (services/agent-service) -> Embedding / Retrieval
  概率性决策、上下文、RAG、Tool 意图

Redis：V1 后续用于短期会话/状态辅助，不作为业务事实来源。
```

## 组件职责

### React Web

负责 Project、Wiki、Task、AI Chat、确认交互和 Markdown 预览。浏览器不直接访问数据库，也不持有可以绕过 Core API 的业务权限。

### Java Core API

系统的业务与信任边界。它拥有用户、项目、Wiki、Task、权限、审批及最终写入。任何来自 Web 或 Agent 的修改请求都使用相同业务校验，不能因为调用方是内部服务而跳过。

### Python Agent Service

负责 LLM、LangGraph、RAG、上下文构建和 Tool Calling。Day 4 可写入可重建的 `rag_chunk` 派生索引，但不读取或修改 Wiki、Task、用户等业务表；它生成答案或结构化操作意图，没有绕过 Java Core API 修改业务事实的权限。

### PostgreSQL

保存业务事实，并通过 `pgvector` 保存可重建的 RAG Chunk 向量。业务表只由 Java 访问；Python 只访问 `rag_chunk` 派生索引，避免过早增加独立向量数据库。

### Redis

仅保存可重建的缓存、短期状态或会话辅助。关键审批和业务事实不得只存在 Redis。

## 关键请求边界

- 查询：Web → Core API；需要 AI 时由 Core API 在完成身份与项目授权后调用 Agent Service。Day 4 的 `retrieve` 节点再用独立内部 token 回调 Core API 读取已授权 Wiki/Task DTO，按版本同步 `rag_chunk` 后执行向量 + BM25 + RRF，详见 ADR-0009 与 ADR-0010。
- 修改：Agent 只能返回 Tool 意图；Web 展示确认；Java 再鉴权、校验、执行并落库。
- 数据隔离：Day 2 所有项目资源按已认证 user + project owner 校验，ADMIN 仅作为受控运维角色；后续演进为 membership。
- 身份：Core API 签发短期 JWT，所有业务入口默认认证；JWT secret 只通过运行环境注入。
- 可观测：所有跨服务请求逐步携带 `request_id`；V1 使用结构化日志，V2 接入完整 Trace。

## 部署策略

V1 在一个单仓库内维护三个可独立运行的应用，但只把 Java 业务服务做成模块化单体。这样既保留清楚的语言和信任边界，也避免第一周承担微服务治理成本。

## 成熟项目参考

- [Backstage architecture overview](https://github.com/backstage/backstage/blob/master/docs/overview/architecture-overview.md)：借鉴“app 负责装配、backend 提供能力”和单仓库内显式包边界，不复制其插件复杂度。
- [Backstage getting started repository layout](https://github.com/backstage/backstage/blob/master/docs/getting-started/index.md)：借鉴根配置与可部署应用入口分离。
- [JHipster React sample](https://github.com/jhipster/jhipster-sample-app-react)：验证 React + Spring Boot 在同一产品仓库中的成熟工程组合。
- [Spring PetClinic](https://github.com/spring-projects/spring-petclinic)：借鉴可直接运行、带构建包装器和本地数据库配置的开发体验。

这些参考提供结构原则，不意味着复制其所有工具或框架。V1 只引入当前闭环必需的组件。
