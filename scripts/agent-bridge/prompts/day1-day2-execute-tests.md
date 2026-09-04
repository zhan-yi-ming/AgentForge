# Day 1 / Day 2 测试执行

请检查刚完成的实现，并亲自执行以下命令：

1. `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\agent-bridge\Test-ReviewBridge.ps1`
2. `pwsh.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\agent-bridge\Test-ReviewBridge.ps1`
3. 在 `services/core-api` 执行 `.\mvnw.cmd verify`

允许清理范围仅限：测试脚本自己创建的 `%TEMP%\agentforge-review-test-*`、Maven `target` 和 Testcontainers 自己创建的容器。测试脚本正常情况下会自行清理临时目录；不要为了整洁额外删除 `target`，以便保留 Surefire 证据。禁止 Git 命令和源码写入。

请重点核验：

- bridge 输出 `Passed=True`、`Checks=12`；
- JWT secret 三个新测试真实执行；
- PostgreSQL 集成测试是否执行还是跳过；
- 失败时给出精确文件/行号和最小修复建议；
- 最终列出每条命令、退出码、测试数、跳过数和清理结果。
