# Day 3 Agent Service 集中验证

请一次性审查当前完整工作区并执行以下验证，不要中途只反馈少量问题：

1. 在仓库根运行 `docker info`，必须成功确认 Docker daemon 可用；失败时本轮直接判定 `NEEDS_FIX`，不得跳过容器测试。
2. 在 `services/agent-service` 运行 `python --version`，必须满足 3.12–3.14。
3. 创建本目录 `.venv`，执行 `.\.venv\Scripts\python.exe -m pip install -e ".[test]"`。
4. 执行 `.\.venv\Scripts\python.exe -m pytest -q`。
5. 设置当前进程的 `AGENTFORGE_AGENT_INTERNAL_TOKEN=test-only-internal-token`，用 `.venv` 的 Python 在 `127.0.0.1:18000` 隐藏启动 uvicorn；轮询 `/health`，最多等待 30 秒，失败则停止。
6. 回到仓库根，再进入 `services/core-api`，设置 `AGENTFORGE_AGENT_SERVICE_URL=http://127.0.0.1:18000` 与 `AGENTFORGE_AGENT_CONTRACT_TEST=true`，执行 `.\mvnw.cmd verify`；必须确认 `AgentServiceHttpContractIntegrationTest` 实际执行且未跳过，并核验 `PersistenceIntegrationTest` 两项 PostgreSQL/Testcontainers 测试均执行、0 跳过。任一测试跳过都不得判定通过。
7. 在 finally 中按精确 PID 停止本次启动的 uvicorn，确认 Testcontainers 容器已释放，再执行 `git diff --check`，并只读核验 Java/Python JSON 字段、内部 token header、request ID、400/401/403/503、项目授权先于下游调用，以及禁止的 V2/V3 组件未被引入。
8. 扫描当前差异中的真实密钥、私钥、Bearer/JWT、生产连接串；测试占位值不得误报为真实密钥。
9. 删除本次创建的 `services/agent-service/.venv`、Python cache、pytest cache、`*.egg-info` 与 `%TEMP%\agentforge-review-test-*`；Maven target 可保留且被 Git 忽略。

输出首行必须精确为 `VALIDATION_RESULT: PASS` 或 `VALIDATION_RESULT: NEEDS_FIX`。一次性列出全部可确认问题（最多十项），记录每条命令、退出码、测试数量、跳过项和清理结果。禁止修改源码、文档、配置或 Git 状态。
