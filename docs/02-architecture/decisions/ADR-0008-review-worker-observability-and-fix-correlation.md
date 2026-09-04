# ADR-0008：单工作树审查 Worker、可观测状态与修复提交关联

- 状态：Accepted
- 日期：2026-09-04
- 关联：ADR-0006、ADR-0007

## 背景

仅凭 monitor PID 不能证明 Pi 正在工作。一次 Windows 状态替换异常未释放独占文件锁，
使存活的 monitor 永久返回 `BUSY`；同时，包含新变更记录的修复提交会被自动发现为新阶段，
无法成为原阶段的 Attempt 2，导致“有定时器、有报告，但闭环不前进”。

## 决定

- 一个共享分支只允许一个权威开发工作树运行 monitor、审查状态和 Codex heartbeat；当前为共享 `main` 工作树。
- 文件锁必须由顶层 `try/finally` 释放。状态更新在同卷临时文件写完后使用带非空备份路径的
  `.NET File.Replace`；首次创建才使用 Move，临时文件和备份始终清理。
- `Show-ReviewStatus.ps1` 是人和自动化共用的只读观测入口：默认人类可读，`-Json` 供程序消费，
  `-Watch` 持续显示。PID 存活但锁长期无进展且没有 Pi 子进程时必须明确显示 `STALLED`。
- 修复提交通过一个或多个 `Review-Fixes: <stage-id>` Git trailer 关联待修复阶段。
  trailer 的 stage 必须已存在且处于 `WAITING_FOR_CODEX_FIX`；否则快速失败，不把提交误注册为新阶段。
- `PASS` 只写入 `NEXT_STAGE_READY`，不会自动开始路线图下一阶段；必须先获得用户明确确认。

## 后果

- 用户可以区分“monitor 存活”“Pi 正在运行”“等待 Codex 修复”和“自动化卡死”。
- 修复轮次在 Git 提交中可追溯，不再依赖工作树或文件修改时间猜测。
- 维护者需要在修复提交正文写 trailer，并在切换权威工作树时停止旧 monitor、迁移 heartbeat。
