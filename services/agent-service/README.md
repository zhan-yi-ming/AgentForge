# AgentForge Agent Service

- 状态：Day 4 In Progress
- 计划天次：V1 / Day 3–4
- 技术：Python + FastAPI + LangGraph

该服务负责 LangGraph 状态流、RAG、国内生成式模型调用和 Tool 意图。graph 为 `prepare -> retrieve -> plan -> respond`：从 Core API 获取已授权 Wiki/Task，按版本同步 `rag_chunk`，执行 Embedding + BM25 + RRF，再由可配置 responder 返回回答。

V2-01 增加可选的 Langfuse fail-open Trace，覆盖 request、Agent、retrieval、tool 和 LLM generation；默认关闭。启用时按 `.env.example` 设置 public key、secret key、host 与 environment，凭据不得提交。

它不能直接读取或写入 Core API 的业务表；Python 只写可重建的 `rag_chunk` 派生索引，任何业务修改仍必须通过 Java 的权限和审批校验。Embedding 固定使用无 key 的 hash provider；生成式回答可选择 DeepSeek、智谱或通义千问，key 只从本地环境读取。安装：`python -m pip install -e .[test]`；启动：`uvicorn agentforge_agent.main:app --reload --port 8000`；测试由 Codex 直接执行 `pytest` 并记录机器结果。
