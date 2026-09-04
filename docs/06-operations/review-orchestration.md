# Pi 审查编排运行手册

- 状态：Accepted

## 启动

只在实际进行共享 `main` 提交的权威工作树中运行 `Start-BridgeMonitor.ps1`。它在后台启动隐藏的 PowerShell monitor，并把 PID、日志和状态留在 `scripts/agent-bridge/` 的 Git 忽略文件中。切换工作树前必须停止旧 monitor，并把 heartbeat 目标迁移到新工作树对应的 Codex 任务。

Codex 每次收到用户消息也会调用 `review-loop.ps1 -OnCodexWake`；这保证了无需等待轮询就能处理 Day 1、Day 2 这类历史待审阶段。

## 状态与报告

- `.review-loop-state.json`：本机运行状态，可删除以重建待审队列。
- `.bridge-monitor.pid`、`.bridge-monitor.log`、`.bridge-monitor.error.log`：后台 monitor 诊断文件，不进入 Git。
- `docs/08-reviews/*.md`：Pi 的独立审查报告，应在后续修复或归档提交中保留。
- `.pi-review-status.json` 与 `.pi-live-output.log`：本机可见性状态和 Pi 输出镜像，不提交。

常用观测命令：

```powershell
# 一次性人类可读状态
.\scripts\agent-bridge\Show-ReviewStatus.ps1

# 每 2 秒刷新，按 Ctrl+C 退出；不会停止 monitor
.\scripts\agent-bridge\Show-ReviewStatus.ps1 -Watch -RefreshSeconds 2

# 供其他脚本读取
.\scripts\agent-bridge\Show-ReviewStatus.ps1 -Json

# 不调用 Pi 的 bridge 冒烟回归
.\scripts\agent-bridge\Test-ReviewBridge.ps1
```

输出必须区分 `RUNNING`、`WAITING_FOR_CODEX_FIX`、`IDLE`、`FAILED` 与 `STALLED`。`STALLED` 表示 monitor 进程仍在，但锁/状态长期没有推进且 Pi 未运行，不能把它解释为后台仍在审核。

所有 bridge 脚本以 UTF-8 BOM 保存，以避免 Windows PowerShell 5 将无 BOM 的中文源码按本机代码页解码；脚本仍需满足执行策略、`-NoProfile` 和依赖命令可用等常规条件。`Test-ReviewBridge.ps1` 会同时检查 BOM 与语法；误改编码后可按下面命令恢复：

```powershell
$utf8Bom = New-Object System.Text.UTF8Encoding($true)
Get-ChildItem .\scripts\agent-bridge\*.ps1 | ForEach-Object {
    [System.IO.File]::WriteAllText($_.FullName, [System.IO.File]::ReadAllText($_.FullName), $utf8Bom)
}
```

Pi 以无会话、无项目上下文自动加载、无工具的非交互模式运行；审查提示词包含完整文件清单、diff 统计和有界的首中尾证据采样。这样既能定位大提交的范围，又避免将无界 diff 送入模型导致会话锁或超时。

包装器以 Pi 的 `minimal` 推理档请求最多十项确定、可操作的发现；这不是模型降级，审查模型仍固定为 DeepSeek V4-pro。若该有界审查无法完成，脚本会保留失败诊断而不消耗审查轮次。

## 排错顺序

1. 运行 `pi.cmd --list-models v4-pro`，必须显示 `deepseek/deepseek-v4-pro`。
2. 运行 `Show-ReviewStatus.ps1`；若为 `STALLED`，使用 `Start-BridgeMonitor.ps1 -Restart` 安全停止 PID 文件对应的旧 monitor 并重启。
3. 运行 `review-loop.ps1 -DryRun` 检查待审阶段、尝试次数和阻断状态。
4. 检查 monitor 日志中的 Pi 退出码或超时信息；不要用 Flash 作为替代。
5. 检查报告是否包含 `REVIEW_RESULT:`；缺失时视为待修复并交由 Codex 研判。
6. 第三次报告仍为 `NEEDS_FIX` 时查看人类介入记录，等待用户决定，不要强行第四次循环。

用户明确授权定向修复后，第三轮上限仍不得重置或触发第四次自动审查。修复完成后必须由 Pi 的独立验证会话给出 `VALIDATION_RESULT: PASS`，Codex 将授权决定和报告路径回填人工介入记录，再把本机 `.review-loop-state.json` 中对应阶段同步为 `PASS`。该同步只关闭已经人工处置的历史阶段，不增加 attempt；报告是可审计依据，运行时状态文件不提交。

## Pi 受限验证模式

验证命令固定使用 `deepseek/deepseek-v4-pro`，启用 `read,grep,find,ls,powershell`。Pi 负责执行 Prompt 明确列出的项目测试，并只清理由该次测试明确创建的临时目录、构建测试数据或 Testcontainers 资源。禁止 `edit`、`write`、业务文件写入、Git 写操作、生产环境访问、敏感信息输出和 Flash。Codex 不执行测试，只根据 Pi 报告修改文档、实现或测试代码。执行过程持续写 `.pi-review-status.json` 和实时日志，便于 `Show-ReviewStatus.ps1 -Watch` 查看。

入口为 `run-validation.ps1 -StageName <name> -PromptFile <path>`。Prompt 必须逐条列出允许执行的测试命令和清理范围；不得授予任意源码、文档或 Git 写入权限。

Pi 的版本化职责与模式边界定义在 `scripts/agent-bridge/pi/AGENTS.md`。由于启动器使用 `--no-context-files` 防止环境提示词污染，`run-review.ps1` 与 `run-validation.ps1` 必须显式把该文件注入临时 Prompt；不得依赖 Pi 自动发现仓库指令。

Pi 启动器从 `AGENTFORGE_PI_CMD` 读取；未设置时使用 PATH 中的 `pi.cmd`。个人绝对路径不得写入公开仓库。

修复提交必须包含 trailer，例如：

```text
fix(review): release orchestration lock after failures

Review-Fixes: review-orchestration-loop
```
