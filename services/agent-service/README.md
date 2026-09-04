# AgentForge Agent Service

- 状态：Day 3 Implemented
- 计划天次：V1 / Day 3
- 技术：Python + FastAPI + LangGraph

该服务负责 LangGraph 状态流和后续 LLM/RAG/Tool 意图。Day 3 只实现 deterministic Chat graph 与内部 HTTP 契约，不连接业务数据库。

它不能直接写 Core API 的业务表；任何修改都必须通过 Java 的业务、权限和后续审批校验。安装：`python -m pip install -e .[test]`；启动：`uvicorn agentforge_agent.main:app --reload --port 8000`；测试由 Pi 执行 `pytest`。
