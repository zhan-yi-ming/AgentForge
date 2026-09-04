# Day 1 / Day 2 证据补齐任务

请只读检查以下文件：

- `scripts/agent-bridge/review-loop.ps1`
- `scripts/agent-bridge/Show-ReviewStatus.ps1`
- `scripts/agent-bridge/Test-ReviewBridge.ps1`
- `services/core-api/src/main/java/com/agentforge/core/security/SecurityConfiguration.java`
- `services/core-api/src/test/java/com/agentforge/core/security/`
- `docs/08-reviews/2026-09-04-review-v1-day-1-attempt-3.md`
- `docs/08-reviews/2026-09-04-review-v1-day-2-attempt-3.md`

本轮先设计测试，不修改文件，也不执行 Git 命令。输出：

1. JWT secret 非法 Base64、少于 32 字节、合法 32 字节的最小 JUnit 测试代码；
2. review outcome 状态迁移可测试函数的最小接口，以及 PowerShell 冒烟断言；
3. `Show-ReviewStatus` 损坏 JSON 与 STALLED 的可测试方式；
4. PostgreSQL Testcontainers 在 GitHub Actions 上是否可直接运行的判断；
5. 需要 Codex 修改的精确文件清单。

不要执行测试，因为建议测试尚未落盘。不要使用 edit/write 或任何 PowerShell 写命令。
