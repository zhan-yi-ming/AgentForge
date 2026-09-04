# State-only 审查阶段恢复验证

请执行并记录以下命令：

1. `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\agent-bridge\Test-ReviewBridge.ps1`
2. `pwsh.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\agent-bridge\Test-ReviewBridge.ps1`
3. `.\scripts\agent-bridge\review-loop.ps1 -DryRun`
4. `Set-Location services\core-api; .\mvnw.cmd verify; Set-Location ..\..`
5. `git diff --check`

核验前两条均返回 `Passed=True`、`Checks=14`，其中纯函数测试必须证明显式 state-only 阶段加入、未引用阶段不加入。第三条在修复尚未提交时应保持 `WAITING_FOR_CODEX_FIX`，不得执行实际 Pi 代码审查或修改运行时状态；新提交携带 trailer 后才由 monitor 触发 Attempt 3。Core API 必须 0 失败、0 错误，并单独报告本机 Docker 不可用造成的跳过项。只读检查 CI 脚本确实要求唯一 PersistenceIntegrationTest 报告、`tests >= 2` 且 `skipped == 0`，并核验 `OptimisticLockingFailureException` 的 HTTP 409 MVC 用例被实际执行。

只清理本次测试产生的 `%TEMP%\agentforge-review-test-*` 目录。输出首行必须为 `VALIDATION_RESULT: PASS` 或 `VALIDATION_RESULT: NEEDS_FIX`，并记录退出码、断言结果与清理结果。
