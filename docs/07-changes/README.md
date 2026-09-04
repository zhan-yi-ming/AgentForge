# 变更记录

此目录保存“为什么改、改了什么、如何验证”的时间线。它不同于 Git 提交信息：Git 记录文件差异，这里记录设计与验证上下文。

## 命名

使用 `YYYY-MM-DD-short-topic.md`。同一天同一主题的连续调整更新同一记录；主题明显不同则新建文件。

## 状态

- `Proposed`：文档阶段，尚未开始实现。
- `In Progress`：目标文档已完成，正在实现或验证。
- `Implemented`：实现和验证已完成，AI 已创建或即将创建阶段提交并推送；推送后等待用户确认下一阶段。

## 索引

- `2026-09-04-day-3-agent-service-chat.md`：FastAPI/LangGraph Chat 与 Java-Python 调用契约。
- `2026-09-04-review-state-only-stage-resume.md`：修复仅存在于运行时状态的阶段无法消费显式 `Review-Fixes` trailer 的调度缺口。
- `2026-09-04-day1-day2-final-review-evidence.md`：补齐第三轮 Pi 报告要求的验证证据，并定义 Pi 受限测试执行职责。
- `2026-09-03-repository-bootstrap-and-day-1.md`：项目治理、整体仓库骨架与 Day 1 Java 基础。
- `2026-09-03-day-2-security-wiki-task.md`：公开仓库安全、JWT / RBAC、Wiki 与 Task。
- `2026-09-03-codex-pi-bridge-and-code-review.md`：Codex 与 Pi Agent 代码审查桥接机制与 5 小时限额调度。
- `2026-09-04-review-orchestration-loop.md`：Pi 审查自动触发、历史补审、修复复审与三次人工接管。
- `2026-09-04-review-automation-recovery-and-observability.md`：修复 Windows 永久锁、阶段关联、权威工作树和 Pi 实时进度展示。
