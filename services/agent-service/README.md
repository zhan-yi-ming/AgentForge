# AgentForge Agent Service

- 状态：Planned
- 计划天次：V1 / Day 3
- 技术：Python + FastAPI + LangGraph

该服务将负责 LLM、Agent 状态、RAG、上下文构建、文本格式化和 Tool Calling 意图。当前只建立边界，不提前引入 Python 依赖。

它不能直接写 Core API 的业务表；任何修改都必须通过 Java 的业务、权限和后续审批校验。开始实现前先补充 AI Chat 功能文档、服务契约和相关 ADR。
