# 变更记录

此目录保存“为什么改、改了什么、如何验证”的时间线。它不同于 Git 提交信息：Git 记录文件差异，这里记录设计与验证上下文。

## 命名

使用 `YYYY-MM-DD-short-topic.md`。同一天同一主题的连续调整更新同一记录；主题明显不同则新建文件。

## 状态

- `Proposed`：文档阶段，尚未开始实现。
- `In Progress`：目标文档已完成，正在实现或验证。
- `Implemented`：实现和验证已完成，AI 已创建或即将创建阶段提交并推送；推送后等待用户确认下一阶段。

## 索引

- `2026-09-06-v2-01-langfuse-base-trace.md`：V2-01 请求、Agent、检索、Tool 与 LLM 的脱敏 fail-open Langfuse 基础 Trace。
- `2026-09-06-centered-demo-production-release.md`：把已验证的居中 Demo 体验合并到 main、部署生产并完成真实发布后核验。
- `2026-09-06-centered-chat-onboarding-and-public-demo-account.md`：V2-01 前的独立体验切片，采用居中单栏聊天、首次新手引导和受限公开 Demo 账号。
- `2026-09-06-v2-v3-development-governance.md`：V2/V3 节点路线、Start/Close Gate、节点边界与 GitHub 维护真实性规则。
- `2026-09-06-risk-based-validation-and-review.md`：L0–L3 风险分级、低风险累计触发和 Pi Diff/Milestone 双模式审核。
- `2026-09-06-v1-2-interview-demo-experience.md`：固定与随机 Demo 账号、面试官欢迎视觉、真实流式 Agent、main 发布与生产更新。
- `2026-09-06-compose-v5-build-compatibility.md`：修复 Compose v5 不支持 `build --no-deps` 导致的首次生产部署阻塞。
- `2026-09-06-pi-review-connection-governance.md`：统一提交前 Pi 只读审核门禁，并固化启动器连接与快速失败方式。
- `2026-09-05-day-7-v1-acceptance.md`：V1 最终验收、完整本地 Compose、演示数据和运行教程。
- `2026-09-05-day-6-react-workspace.md`：React 项目工作区、AI Chat、人工确认与 Markdown 安全预览。
- `2026-09-05-day-5-tool-calling-hitl.md`：Python create/update task 意图、Java 待确认 action、confirm/reject 与确定性写回。
- `2026-09-05-disable-pi-and-day1-day4-audit.md`：停用 Pi、引入工程 skills，并由 Codex 以干净构建和真实跨进程测试复核 Day 1–4。
- `2026-09-04-day-4-hybrid-rag.md`：Wiki/Task Chunk、Embedding、BM25、RRF、Retrieved Context 与来源引用。
- `2026-09-04-day-3-agent-service-chat.md`：FastAPI/LangGraph Chat 与 Java-Python 调用契约。
- `2026-09-04-review-state-only-stage-resume.md`：修复仅存在于运行时状态的阶段无法消费显式 `Review-Fixes` trailer 的调度缺口。
- `2026-09-04-day1-day2-final-review-evidence.md`：补齐第三轮 Pi 报告要求的验证证据，并定义 Pi 受限测试执行职责。
- `2026-09-03-repository-bootstrap-and-day-1.md`：项目治理、整体仓库骨架与 Day 1 Java 基础。
- `2026-09-03-day-2-security-wiki-task.md`：公开仓库安全、JWT / RBAC、Wiki 与 Task。
- `2026-09-03-codex-pi-bridge-and-code-review.md`：Codex 与 Pi Agent 代码审查桥接机制与 5 小时限额调度。
- `2026-09-04-review-orchestration-loop.md`：Pi 审查自动触发、历史补审、修复复审与三次人工接管。
- `2026-09-04-review-automation-recovery-and-observability.md`：修复 Windows 永久锁、阶段关联、权威工作树和 Pi 实时进度展示。
