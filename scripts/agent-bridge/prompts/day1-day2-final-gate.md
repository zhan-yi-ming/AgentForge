# Day 1 / Day 2 最终提交前门禁

请审查当前工作树相对 HEAD 的全部差异，并执行以下检查与测试命令：

- `git diff --cached --check`
- `git status --short`
- `git diff --cached --stat`
- `git diff --cached -- . ':!docs/08-reviews/*.md'`
- `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\agent-bridge\Test-ReviewBridge.ps1`
- `pwsh.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\agent-bridge\Test-ReviewBridge.ps1`
- `Set-Location services\core-api; .\mvnw.cmd verify; Set-Location ..\..`

核验：文档先行是否成立；第三轮报告问题是否被关闭；review outcome、trailer 识别、状态容错与 UTF-8 是否有明显缺陷；JWT 测试是否真正覆盖现有实现；CI 的 Testcontainers 断言是否可运行；是否包含真实密钥或敏感数据。

不要修改源码、文档或 Git 状态。测试结束后，只删除本次命令明确创建且位于系统临时目录的 `agentforge-review-test-*` 目录；Maven `target` 保留为 Surefire 证据且已被 Git 忽略。输出必须以 `VALIDATION_RESULT: PASS` 或 `VALIDATION_RESULT: NEEDS_FIX` 开头，并记录每条命令的退出码、测试数量、跳过项、清理结果及仍需修复的问题。
