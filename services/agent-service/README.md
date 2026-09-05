# AgentForge Agent Service

- 状态：Day 4 In Progress
- 计划天次：V1 / Day 3–4
- 技术：Python + FastAPI + LangGraph

该服务负责 LangGraph 状态流、RAG 和后续 LLM/Tool 意图。Day 4 的 graph 为 `prepare -> retrieve -> respond`：从 Core API 获取已授权 Wiki/Task，按版本同步 `rag_chunk`，执行 Embedding + BM25 + RRF 并返回来源。

它不能直接读取或写入 Core API 的业务表；Python 只写可重建的 `rag_chunk` 派生索引，任何业务修改仍必须通过 Java 的权限和后续审批校验。默认 hash Embedding 无需 key；可选 OpenAI-compatible provider。安装：`python -m pip install -e .[test]`；启动：`uvicorn agentforge_agent.main:app --reload --port 8000`；测试由 Codex 直接执行 `pytest` 并记录机器结果。
