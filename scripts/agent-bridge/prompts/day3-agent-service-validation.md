# Day 3 Agent Service 集中验证

请一次性审查当前完整工作区并执行以下验证，不要中途只反馈少量问题：

1. 在 `services/agent-service` 运行 `python --version`，必须满足 3.12–3.14。
2. 创建本目录 `.venv`，执行 `.\.venv\Scripts\python.exe -m pip install -e ".[test]"`。
3. 执行 `.\.venv\Scripts\python.exe -m pytest -q`。
4. 回到仓库根，再进入 `services/core-api`，设置仅用于当前进程的 `AGENTFORGE_AGENT_INTERNAL_TOKEN=test-only-internal-token`，执行 `.\mvnw.cmd verify`。
5. 执行 `git diff --check`，并只读核验 Java/Python JSON 字段、内部 token header、request ID、400/401/403/503、项目授权先于下游调用，以及禁止的 V2/V3 组件未被引入。
6. 扫描当前差异中的真实密钥、私钥、Bearer/JWT、生产连接串；测试占位值不得误报为真实密钥。
7. 删除本次创建的 `services/agent-service/.venv`、Python cache、pytest cache、`*.egg-info` 与 `%TEMP%\agentforge-review-test-*`；Maven target 可保留且被 Git 忽略。

输出首行必须精确为 `VALIDATION_RESULT: PASS` 或 `VALIDATION_RESULT: NEEDS_FIX`。一次性列出全部可确认问题（最多十项），记录每条命令、退出码、测试数量、跳过项和清理结果。禁止修改源码、文档、配置或 Git 状态。
