# Pi 审查编排运行手册

- 状态：Deprecated

> 2026-09-05 起自动编排部分仅用于历史追溯。用户已重新授权一次性 Pi 只读代码审核；测试仍由 Codex 执行。

## 当前一次性审核入口

在实现与 Codex 测试完成、提交之前运行：

```powershell
.\scripts\agent-bridge\run-review.ps1 -StageName day-5-tool-calling-hitl -BaseRef HEAD -TargetRef WORKTREE -Attempt 1
```

入口会先扫描工作树 diff 中的敏感信息，再固定调用 `deepseek/deepseek-v4-pro` 并生成独立报告。它不启动 monitor、不轮询、不运行测试，也不自动推进阶段。Pi 登录、额度、模型或环境失败时立即停止并请用户处理。

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

Pi 以无会话、无项目上下文自动加载、无工具的非交互模式运行；审查提示词包含完整文件清单、diff 统计和不超过 180000 字符的完整 diff。只有异常超大差异才明确标注截断并保留首中尾证据；Day 5 的 141965 字符 diff 未截断。

包装器以 Pi 的 `minimal` 推理档请求最多十项确定、可操作的发现；这不是模型降级，审查模型仍固定为 DeepSeek V4-pro。若该有界审查无法完成，脚本会保留失败诊断而不消耗审查轮次。

## 排错顺序

1. 运行 `pi.cmd --list-models v4-pro`，必须显示 `deepseek/deepseek-v4-pro`。
2. 运行 `Show-ReviewStatus.ps1`；若为 `STALLED`，使用 `Start-BridgeMonitor.ps1 -Restart` 安全停止 PID 文件对应的旧 monitor 并重启。
3. 运行 `review-loop.ps1 -DryRun` 检查待审阶段、尝试次数和阻断状态。
4. 检查 monitor 日志中的 Pi 退出码或超时信息；不要用 Flash 作为替代。
5. 检查报告是否包含 `REVIEW_RESULT:`；缺失时视为待修复并交由 Codex 研判。
6. 第三次报告仍为 `NEEDS_FIX` 时查看人类介入记录，等待用户决定，不要强行第四次循环。

以下自动轮次与 Pi validation 规则均为历史流程，当前保持停用。现行流程仅由 Codex 在完成真实测试后发起一次性 Pi 只读审核；修复后可定向复审，但不恢复自动循环或由 Pi 执行验证。

## Pi 受限验证模式（历史，已停用）

该模式仅保留历史说明，不得调用。Pi 不运行测试、命令或清理操作；Codex 是唯一测试执行者。当前只读审核固定使用 `deepseek/deepseek-v4-pro`，以无工具模式接收已扫描的 diff。

入口为 `run-validation.ps1 -StageName <name> -PromptFile <path>`。Prompt 必须逐条列出允许执行的测试命令和清理范围；不得授予任意源码、文档或 Git 写入权限。

Pi 的版本化职责与模式边界定义在 `scripts/agent-bridge/pi/AGENTS.md`。由于启动器使用 `--no-context-files` 防止环境提示词污染，`run-review.ps1` 与 `run-validation.ps1` 必须显式把该文件注入临时 Prompt；不得依赖 Pi 自动发现仓库指令。

Pi 启动器从 `AGENTFORGE_PI_CMD` 读取；未设置时使用 PATH 中的 `pi.cmd`。个人绝对路径不得写入公开仓库。

修复提交必须包含 trailer，例如：

```text
fix(review): release orchestration lock after failures

Review-Fixes: review-orchestration-loop
```

自动发现的阶段可能只存在于 `.review-loop-state.json`。后续修复提交使用同名 `Review-Fixes` trailer 时，循环会恢复并调度该阶段；如果 trailer 拼写与注册表、变更记录和运行时状态均不匹配，循环必须报错而不是静默创建阶段。

## 2026-09-05 当前状态

Pi 仅恢复一次性只读审核；Codex 直接运行构建、格式和测试，核对机器产物并记录退出码、测试数量、失败、跳过与清理。旧自动流程继续停用，Pi PASS 不能替代测试证据。
