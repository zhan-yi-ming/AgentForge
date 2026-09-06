# AgentForge 双 Agent 审查与调度桥接工具 (Codex <-> Pi Agent)

本目录主要保留历史 **Codex 与 Pi Agent** 异步协同工具供审计。当前仅 `run-review.ps1` 恢复为一次性只读审核入口；所有 monitor、loop、validation 与自动推进入口仍直接返回 `DISABLED`。

## 目录文件说明

- `run-review.ps1`：执行单次、带超时的 Pi V4-pro 只读审查，输出独立报告。
- `review-loop.ps1`：唯一的阶段状态机入口；负责历史补审、修复后复审、三次上限和人工接管。
- `review-state.ps1`：无 Git、文件或 Pi 副作用的 review outcome 纯函数，供编排器和回归测试共同使用。
- `bridge-monitor.ps1`：常驻后台守护脚本，周期性调用 `review-loop.ps1`；当 Codex 额度耗尽时自动进入 5 小时倒计时休眠，并在恢复后生成续跑指令。
- `Test-ReviewBridge.ps1`：不调用 Pi 的回归入口，验证脚本语法、BUSY 互斥、异常释放锁、DryRun 不写状态和 JSON 状态输出。
- `run-validation.ps1`：以 V4-pro 启动受限验证会话；允许 read/grep/find/ls 与 PowerShell 执行 Prompt 明确列出的测试和清理动作，禁止 edit/write、业务文件写入和 Git 改写，并复用状态与实时日志。
- `Start-BridgeMonitor.ps1`：在当前开发工作树中隐藏启动并复用 monitor。
- `Show-ReviewStatus.ps1`：一次性或持续显示 monitor、锁、阶段队列、Pi 进程和日志进度。
- `review-stages.json`：Day 1、Day 2 的版本化历史审查边界。
- `pi/AGENTS.md`：显式注入 Pi 会话的版本化职责、模型和权限边界。
- `prompts/stage-review-system.md`：审查提示词与审查标准定义。

守护进程生成的 `.review-loop-state.json`、`.bridge-monitor.pid`、`.bridge-monitor.log`、`.bridge-monitor.error.log`、根目录 `.codex-quota-exhausted` 与
`.codex-resume-signal.md` 都属于本机运行时状态，已由 `.gitignore` 排除，不得提交到公开仓库。

## 审查模型硬约束

代码审查只能使用完整模型选择器 `deepseek/deepseek-v4-pro`。桥接脚本不允许降级为 `deepseek-v4-flash` 或其他 Flash 模型；每次调用前检查模型目录，并在 Pi 非零退出或没有返回报告时明确失败。失败的提交不会被标记为已审查，不能用速度换审查质量。

## 常见使用场景

首次使用、Codex 新会话找不到 `pi.cmd` 或模型目录失败时，先阅读 `../../docs/06-operations/pi-review-connection.md`。只检查 `AGENTFORGE_PI_CMD` 和 PATH 两处；均不可用就立即请用户处理，禁止全盘搜索、安装尝试或循环重试。

### 当前场景：提交前一次性审核

```powershell
.\scripts\agent-bridge\run-review.ps1 -StageName day-5-tool-calling-hitl -BaseRef HEAD -TargetRef WORKTREE -Attempt 1
```

`WORKTREE` 模式审核当前未提交差异。新增文件需先通过 `git add -N <path>` 进入 diff 视图；这不会暂存文件内容。审核失败立即交还用户或 Codex 处理，不自动轮询。

审核必须在创建 Git 提交前完成；只有用户针对本次变更明确豁免时可以跳过，并在变更记录中记录。

### 场景一：Codex 消息唤醒或手动处理待审阶段

Codex 每个新消息会调用以下命令。它补审 Day 1/Day 2，或处理当前阶段的下一次复审：

```powershell
.\scripts\agent-bridge\review-loop.ps1 -OnCodexWake
.\scripts\agent-bridge\review-loop.ps1 -DryRun
```

### 场景二：启动后台监听守护进程

在实际创建提交的工作树中运行一次：

```powershell
.\scripts\agent-bridge\Start-BridgeMonitor.ps1
.\scripts\agent-bridge\Show-ReviewStatus.ps1 -Watch
```

它会为新提交触发同一循环；每次 attempt 使用独立报告，第三次仍为 `NEEDS_FIX` 时写出人工介入记录并停止。

若状态显示 `STALLED`，运行 `.\scripts\agent-bridge\Start-BridgeMonitor.ps1 -Restart`。修复某份报告时，在提交正文加入 `Review-Fixes: <stage-id>`；该 trailer 是下一次 Attempt 的唯一 Git 级关联依据。

### 场景三：Codex 5 小时额度用尽与自动等待续跑
当 Codex 触发 OpenAI 5 小时速率限制或额度耗尽时：
1. **触发等待**：
   - 方式 A（推荐）：在项目根目录创建一个标记文件即可（任何命令或 AI 均可触发）：
     ```powershell
     New-Item .codex-quota-exhausted
     ```
   - 方式 B：直接带参数运行脚本：
     ```powershell
     .\scripts\agent-bridge\bridge-monitor.ps1 -TriggerQuotaWait
     ```
2. **休眠倒计时**：脚本自动进入 5 小时（18000 秒）倒计时，控制台实时显示剩余时间。
3. **期间并行审查**：在 5 小时等待期间，Pi Agent 可先将已写好的代码审查完毕，不浪费时间。
4. **到期自动唤醒**：
   - 倒计时结束时，发出蜂鸣提示。
   - 自动生成 `.codex-resume-signal.md`，内含额度恢复通知、最新审查报告链接和续跑 Prompt。
   - Codex 恢复后即可直接读取信号文件继续推进。

## 2026-09-05 当前状态

用户已重新授权 Pi 执行一次性只读代码审核；测试仍由 Codex 执行。`run-review.ps1` 之外的旧入口继续停用，不得凭 Pi 报告替代机器测试或自动推进。
