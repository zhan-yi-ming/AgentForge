# AgentForge 双 Agent 审查与调度桥接工具 (Codex <-> Pi Agent)

本目录包含实现 **Codex (主开发) 与 Pi Agent (代码审查员)** 异步协同、自动审查与 5 小时限额等待自动续跑的自动化工具。

## 目录文件说明

- `run-review.ps1`：执行代码审查的核心脚本。调用 Pi Agent (DeepSeek) 对指定 Git 提交或工作区改动进行**只读审查**，将标准报告写入 `docs/08-reviews/`。
- `bridge-monitor.ps1`：常驻后台守护监控脚本。自动感知 Codex 提交阶段代码，自动拉起 Pi 审查；当 Codex 额度耗尽时自动进入 5 小时倒计时休眠，并在恢复后生成续跑指令。
- `prompts/stage-review-system.md`：审查提示词与审查标准定义。

守护进程生成的 `.bridge-state.json`、根目录 `.codex-quota-exhausted` 与
`.codex-resume-signal.md` 都属于本机运行时状态，已由 `.gitignore` 排除，不得提交到公开仓库。

## 审查模型硬约束

代码审查只能使用完整模型选择器 `deepseek/deepseek-v4-pro`。桥接脚本不允许降级为 `deepseek-v4-flash` 或其他 Flash 模型；每次调用前检查模型目录，并在 Pi 非零退出或没有返回报告时明确失败。失败的提交不会被标记为已审查，不能用速度换审查质量。

## 常见使用场景

### 场景一：单次手动触发代码审查
若想立即对当前代码改动或某个提交进行审查：

```powershell
# 对当前最新一次提交进行审查（对比 HEAD~1 .. HEAD）
.\scripts\agent-bridge\run-review.ps1

# 对特定提交或特定阶段进行审查
.\scripts\agent-bridge\run-review.ps1 -StageName "Day-2" -BaseRef "17b43f4" -TargetRef "HEAD"
```

### 场景二：启动后台监听守护进程
让桥接器在后台运行，自动监控 Codex 的开发节奏：

```powershell
.\scripts\agent-bridge\bridge-monitor.ps1
```
- 当 Codex 在完成阶段开发并执行 `git commit` 后，监控器自动检测到新提交，立即调用 Pi Agent 生成报告，无需人工操作。
- 报告自动落盘于 `docs/08-reviews/YYYY-MM-DD-review-<stage>.md`。

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
