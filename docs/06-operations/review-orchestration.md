# Pi 审查编排运行手册

- 状态：Accepted

## 启动

在实际进行 Git 提交的工作树中运行 `Start-BridgeMonitor.ps1`。它在后台启动隐藏的 PowerShell monitor，并把 PID、日志和状态留在 `scripts/agent-bridge/` 的 Git 忽略文件中。

Codex 每次收到用户消息也会调用 `review-loop.ps1 -OnCodexWake`；这保证了无需等待轮询就能处理 Day 1、Day 2 这类历史待审阶段。

## 状态与报告

- `.review-loop-state.json`：本机运行状态，可删除以重建待审队列。
- `.bridge-monitor.pid`、`.bridge-monitor.log`、`.bridge-monitor.error.log`：后台 monitor 诊断文件，不进入 Git。
- `docs/08-reviews/*.md`：Pi 的独立审查报告，应在后续修复或归档提交中保留。
- `.pi-review-status.json` 与 `.pi-live-output.log`：本机可见性状态和 Pi 输出镜像，不提交。运行 `Show-ReviewStatus.ps1` 可查看 monitor 存活、当前阶段/轮次、模型、PID 和日志尾部；用 `Start-BridgeMonitor.ps1 -Visible` 打开实时 monitor 窗口。

Pi 以无会话、无项目上下文自动加载、无工具的非交互模式运行；审查提示词包含完整文件清单、diff 统计和有界的首中尾证据采样。这样既能定位大提交的范围，又避免将无界 diff 送入模型导致会话锁或超时。

包装器以 Pi 的 `minimal` 推理档请求最多十项确定、可操作的发现；这不是模型降级，审查模型仍固定为 DeepSeek V4-pro。若该有界审查无法完成，脚本会保留失败诊断而不消耗审查轮次。

## 排错顺序

1. 运行 `pi.cmd --list-models v4-pro`，必须显示 `deepseek/deepseek-v4-pro`。
2. 运行 `review-loop.ps1 -DryRun` 检查待审阶段、尝试次数和阻断状态。
3. 检查 monitor 日志中的 Pi 退出码或超时信息；不要用 Flash 作为替代。
4. 检查报告是否包含 `REVIEW_RESULT:`；缺失时视为待修复并交由 Codex 研判。
5. 第三次报告仍为 `NEEDS_FIX` 时查看人类介入记录，等待用户决定，不要强行第四次循环。
