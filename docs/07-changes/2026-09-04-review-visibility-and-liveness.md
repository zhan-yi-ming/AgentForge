# Pi 审查可见性与常驻性修复

- 日期：2026-09-04
- 状态：Implemented
- 阶段：V1 开发治理

## 背景

用户无法判断 Pi 是否仍在运行、何时停止或为何失败；当前 monitor PID 已失效。Pi 子进程输出被临时重定向，Codex 也没有可读的实时运行状态，导致自主循环不可观察。

## 目标与范围

- 每次 Pi 调用写入本机忽略的运行状态：阶段、轮次、模型、PID、开始/结束时间与结果。
- 将 Pi stdout/stderr 持续镜像至本机日志；可选的可见 monitor 窗口实时显示新增输出。
- 新增只读 `Show-ReviewStatus.ps1`，展示 monitor 存活、循环状态、当前 Pi 状态和日志尾部。
- monitor 异常退出必须留下结构化失败状态，启动器可以识别失效 PID 后重启。

## 安全、验证与回滚

- 运行状态与日志均被 Git 忽略；不记录 Pi 提示词或密钥扫描原文。
- 验证 PowerShell AST、状态脚本、可见窗口/后台窗口启动与日志写入。
- 回滚时停止 monitor 并删除忽略的状态与日志；审查报告不删除。

## 实现与验证结果

- `run-review.ps1` 写入 `.pi-review-status.json`、`.pi-live-output.log` 与错误日志；报告仍是唯一 Git 归档产物。
- 新增 `Show-ReviewStatus.ps1`；验证可显示失效 monitor PID 和无 Pi 运行状态。
- `Start-BridgeMonitor.ps1 -Visible` 支持打开 monitor 窗口；默认仍为隐藏后台模式。
- 启动器固定使用 `pwsh.exe`，避免 Windows PowerShell 5 对无 BOM UTF-8 中文脚本解码损坏后立即退出。
- 全部 bridge 脚本 PowerShell AST 通过；`bridge-monitor.ps1 -Once` 返回 `WAITING_FOR_CODEX_FIX`。
