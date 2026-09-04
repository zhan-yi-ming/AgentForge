# 审查自动化恢复、修复关联与可观测性

- 日期：2026-09-04
- 状态：Completed
- 阶段：V1 开发治理
- 交付目标：`origin/main`

## 背景与现场证据

Day 1、Day 2 已实现并各生成一份 DeepSeek V4-pro Attempt 1 报告，但均为
`NEEDS_FIX`。随后提交的审查编排改动也生成了独立 `NEEDS_FIX` 报告，尚未形成
任一阶段的 Attempt 2。

2026-09-04 10:36 的只读诊断确认：

- bridge monitor PID 7508 仍存活，但没有 Pi / Node 子进程；
- `.pi-review-status.json` 和 Pi 实时输出日志不存在；
- `.review-loop-state.json.lock` 被长期占用，monitor 每 15 秒只返回 `BUSY`；
- monitor 首次保存已有状态时，`File.Replace(..., $null)` 在 Windows 抛出
  `The path is empty`，异常路径没有释放文件锁，导致同一常驻进程永久自锁；
- Codex heartbeat 虽为 `ACTIVE` 且每 5 分钟运行，但绑定在另一个 detached worktree，
  不能让当前 `main` 工作树成为唯一权威执行位置；
- 自动发现把包含变更记录的修复提交登记成新阶段，原 `WAITING_FOR_CODEX_FIX`
  阶段无法把修复提交作为下一次审查对象，形成阶段膨胀而不是闭环。

因此当前状态是“定时器和 monitor 进程存在，但 Pi 没有在运行，闭环已停滞”。

## 目标

- 修复 Windows 状态替换与异常释放，任何失败都不能遗留由存活 monitor 持有的锁。
- 让修复提交通过 Git trailer 明确关联一个或多个待修复阶段，使 Attempt 2/3 审查边界可追溯。
- 让当前共享 `main` 工作树成为 monitor、状态和 heartbeat 的唯一执行位置。
- 提供人类可读的一次性状态与持续观察模式，显示 monitor、锁、阶段队列、Pi 模型、
  PID、运行时长、日志活跃时间、最新输出和失败原因。
- 保持 Pi 固定使用 `deepseek/deepseek-v4-pro`，保持只读和三次人工接管边界。
- 遵循当前项目规则：审查通过只表示下一阶段可以提交计划，未经用户明确确认不得开始 Day 3。

## 非目标

- 不扩展 Day 1/Day 2 的产品范围；本次仅修复 Pi 复审确认的鉴权边界，并补齐权限与乐观锁回归证据。
- 不让 Pi 或 monitor 修改业务代码，不引入外部队列、数据库或云调度服务。
- 不使用 Flash，不把提示词、密钥或未脱敏 diff 写入状态日志。

## 设计与文档影响

- 新增 ADR-0008，记录单工作树执行、异常安全锁、原子替换和 `Review-Fixes` trailer。
- 更新 `docs/03-features/review-orchestration.md` 的修复关联与用户确认边界。
- 更新 `docs/06-operations/review-orchestration.md`，提供状态查看、持续观察和恢复步骤。
- 更新 `scripts/agent-bridge/README.md` 与 Git 工作流的提交 trailer 约定。

## 实现计划

1. `review-loop.ps1` 使用顶层 `try/finally` 释放锁，并为 `File.Replace` 使用同卷非空备份路径；失败临时文件在 `finally` 清理。
2. 从提交正文读取 `Review-Fixes: <stage-id>` trailer；带 trailer 的提交只触发对应阶段下一次审查，不自动注册成新阶段。
3. 扩展 `Show-ReviewStatus.ps1`，默认输出人类可读状态，提供 `-Json` 与 `-Watch`；检测“PID 存活但锁长期占用且无 Pi”并显示 `STALLED`。
4. 扩展启动器的重启能力与 PID 校验，确保旧 monitor 可安全替换且日志不伪装成进度。
5. 将 heartbeat 暂停、迁移到当前共享 `main` 任务并收紧提示词；修复验证完成后再启用。
6. 增加不调用 Pi 的 PowerShell 回归测试，覆盖原子状态替换、异常释放、BUSY、trailer 关联和状态输出。
7. 对 Day 1 Attempt 2 的问题逐项研判：补齐 Wiki/Task 全入口权限顺序测试；缺失 JWT subject 映射为 401；在配置绑定阶段校验 Base64 密钥及 32 字节下限；用 PostgreSQL 集成测试证明 `@Version` 的过期写失败。

## 预期验证

- Windows PowerShell 5 与 PowerShell 7 AST 均通过。
- 回归测试能复现旧 `File.Replace`/锁泄漏路径，并证明修复后锁可再次独占获取。
- `Show-ReviewStatus.ps1` 的人类模式与 `-Json` 模式正确显示 WAITING、RUNNING、FAILED、STALLED。
- `review-loop.ps1 -DryRun` 不调用 Pi、不消耗 attempt；trailer 夹具只关联目标阶段。
- 启动 monitor 后 PID 存活、日志持续更新，且无工作时显示真实 WAITING/IDLE，不把 `BUSY` 当作进度。
- 本地敏感信息扫描、Markdown 链接与 `git diff --check` 通过。

## 风险与回滚

- 错误 trailer 会把提交送到错误阶段：只接受已存在且处于 `WAITING_FOR_CODEX_FIX` 的 stage ID，否则快速失败。
- watcher 是只读展示，终止 watcher 不影响 monitor；运行日志继续保持 Git 忽略。
- 回滚时先暂停 heartbeat、停止当前工作树 monitor，再回退脚本与文档提交；已生成审查报告保留。

## 实际交付与审查结果

- 修复 `File.Replace` 空备份路径与异常锁泄漏；状态写入使用同卷临时文件和非空备份路径，顶层 `finally` 始终释放锁。
- `Show-ReviewStatus.ps1` 默认提供人类可读输出，并支持 `-Json`、`-Watch`、损坏状态容错及 `STALLED` 判断；`Start-BridgeMonitor.ps1 -Restart` 可替换已验证的 PowerShell monitor。
- `Review-Fixes` trailer 可把一次提交精确关联一个或多个历史阶段，带 trailer 的提交不会被登记为新阶段。
- Pi DeepSeek V4-pro 已完成 Day 1/Day 2 Attempt 2，报告均为 `NEEDS_FIX`；Codex 已逐项研判并回填。采纳项包括缺失 JWT subject 的 401、防回归权限断言、过期删除拒绝、Task 404/403 契约、数据库乐观锁测试和 bridge 冒烟测试；JWT secret 校验问题因现有实现已满足而拒绝。
- Day 3 未开始，仍只有服务说明占位；是否进入 Day 3 等待用户决定。

## 验证结果

- `Test-ReviewBridge.ps1`（PowerShell 7）：6 类检查通过（含全部 bridge 脚本 BOM）。
- `Test-ReviewBridge.ps1`（Windows PowerShell 5）：退出码 0；UTF-8 BOM 兼容通过。
- `mvnw.cmd test`：成功，42 项测试，0 失败，2 项因 Docker 未运行而跳过。
- `mvnw.cmd verify`：成功并生成可执行 jar；42 项测试，0 失败，2 项跳过。
- `git diff --check` 与本地高风险密钥模式扫描通过；本机未安装 Gitleaks，远程 `.github/workflows/secret-scan.yml` 继续执行正式门禁。
- Pi 状态实测可显示 `RUNNING`、固定模型、stage、attempt 与 PID；Day 1 PID 23360、Day 2 PID 26252 均正常结束并写出报告。
- 当前主工作树 monitor PID 32608 已启动；它真实拉起 Pi PID 7256 完成 `review-visibility-and-liveness` Attempt 1，随后状态正确回到 `WAITING_FOR_CODEX_FIX`。该报告的兼容性测试与文档问题已回填处理。
- Codex heartbeat 已迁移到当前主任务并恢复为 `ACTIVE`（每 5 分钟）；提示词明确 PASS 后只报告 `NEXT_STAGE_READY`，未经用户确认不得开始 Day 3。
- 已知限制：本机 Docker daemon 未运行，新增 PostgreSQL 双快照乐观锁测试未在本机执行；需在 Docker 可用环境或 CI 复跑。
