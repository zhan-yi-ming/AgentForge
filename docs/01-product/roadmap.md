# 三阶段路线图

- 状态：Accepted
- 当前焦点：V1 / Day 4 已完成并通过 Day 1–4 全面复核；等待用户明确授权 Day 5

## V1：能用

目标周期为 5–7 天，跑通 Agent + RAG + Tool + Human-in-the-loop + Web 的业务闭环。

| 天次 | 主要任务 | 必须理解 |
| --- | --- | --- |
| Day 1 | Spring Boot + PostgreSQL；User / Project 基础；项目结构 | Controller、Service、Repository、Entity 的职责 |
| Day 2 | Wiki / Task CRUD；Spring Security + JWT 基础 | JWT 鉴权与服务端再次校验权限的原因 |
| Day 3 | FastAPI + LangGraph Chat；Java ↔ Python 接口 | State、Node、Edge，以及 Java/Python 拆分原因 |
| Day 4 | Wiki / Task RAG；Embedding + BM25 + RRF | Chunk、召回、融合、Context 进入模型的方式 |
| Day 5 | Tool Calling；create/update task；人工确认 | 模型生成意图，Java 决定并执行 |
| Day 6 | React 联调；AI 文本整理与 Markdown 预览 | Component、state、请求和渲染 |
| Day 7 | 测试、异常、Compose、README、演示数据 | 从请求到落库的完整链路与故障位置 |

V1 明确不做：Neo4j / GraphRAG、完整 Langfuse Trace、LiteLLM、多模型路由、MCP、复杂 OAuth / SSO 和复杂 UI。

## V2：可靠

补充 Context Manager、Memory Namespace、完整 RBAC 与风险分级、审批与审计、幂等键、LangGraph 持久化、Langfuse Trace、评测与回归测试。目标是让 Agent 有状态、可控、可恢复、可解释。

## V3：高级

加入 Neo4j / GraphRAG、实体消歧、关系溯源、MCP、LiteLLM 和多模型策略，并可选接入 Git 仓库上下文。目标是处理复杂关系与更开放的工具生态。

## 路线治理

后续能力只能在进入对应阶段后引入。若确需提前，必须新增 ADR，解释收益、成本、学习负担和为何不能等待。
