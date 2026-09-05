# 停用 Pi 并重新验证 Day 1–4

- 状态：Implemented
- 背景：用户报告 Java IDE 大量报错，质疑 Pi 测试证据与 token 成本，要求停止 Pi，由 Codex 全面复核。
- 范围：停用所有 Pi 审查/验证启动入口与自动推进；复核当前工作树的 Day 1–4 Java/Python、格式、构建、测试和历史证据；引入 mattpocock/skills 工程规范。
- 影响：Codex 直接执行验证；历史 Pi 报告仅保留追溯，不再作为交付门禁。保留当前 Day 4 未提交工作与用户 Word 文件。
- 验证：Java clean verify、Python pytest（真实容器与契约）、格式检查；记录命令、退出码、数量、跳过、清理。缺少证据不能报告通过。
- 回滚：可回退本次代码与工具配置；未经用户重新授权不得恢复 Pi 或自动推进。

## 初步证据

- 磁盘 CreateProjectRequest.java 是闭合的 Java record；聊天样例中的反斜杠与 HTML 实体不在该文件内。
- target/surefire-reports 存在 2026-09-04 22:03 的 Java XML/TXT；需先统计保留，再 clean 重建。
- run-review.ps1 使用截断证据与无工具模式；run-validation.ps1 只检查进程退出码和输出非空，没有机器校验测试产物。
- Day 4 Attempt 2 明确为 NEEDS_FIX，报告声称测试通过但同时发现数据库不可用时错误契约不一致；不能把该轮称为阶段通过。

## 实施与最终验证

- 本地 Java 爆红诊断确认是 `services/core-api/.idea` 与 `core-api.iml` 错配：Maven 要求 Java 21，但 IDE 元数据将项目语言级别、项目 JDK 和字节码目标均固定为 Java 8。该配置会使所有 `record` 语法、Java 21 API 及 Maven/Jakarta/Spring 依赖的解析出现连锁错误。
- 本次把本地 IDEA 项目与模块语言级别改为 Java 21，并移除过时的 Java 8 字节码覆盖，交由 Maven `pom.xml` 的 `<java.version>21</java.version>` 作为唯一构建目标。用户在 IDE 重新加载 Maven 项目后应使用本机已安装的 Temurin JDK 21。
- 已删除当前任务的 Pi heartbeat，并停止旧验证流程；五个 Pi/monitor 启动入口均直接返回 `DISABLED`。
- `AGENTS.md` 已记录 mattpocock/skills 工程规范；仓库内引入 `diagnosing-bugs`、`tdd` 及其测试/Mock 指南原文，来源为 <https://github.com/mattpocock/skills>。
- Day 4 约定 seam：Agent Service HTTP Chat、Core API 公共/内部 HTTP 契约、`RagStore.synchronize/search` 对真实 pgvector 的公开适配器接口。范围已由用户授权，不再重复确认。
- Day 4 跨进程验收固化为 `scripts/validation/day4-e2e.ps1`，失败时保留诊断日志，成功或失败都清理专用服务 PID 与隔离 Compose project/卷。
- 首次 E2E 运行在 Agent 健康检查处失败且已完成隔离资源清理；发现脚本 catch 内的 `Write-Error` 受全局 Stop 策略影响，提前阻止日志回显。先改为非终止错误摘要并输出服务 stderr，再复现实际启动问题。
- 日志化复现确认 `AGENTFORGE_AGENT_EMBEDDING_DIMENSIONS=384` 被 `Literal[384]` 拒绝，健康端点返回 500；配置已改为上下界均为 384 的整数 Field，并由同一公共 HTTP E2E 回归通过。
- Red/green：真实 pgvector 测试先以旧 list 参数复现 1 failed 和 `<=> double precision[]`，改用 pgvector `Vector` 后 1 passed。
- Python：3.14.3；全量 pytest 12 passed、0 failed/error/skipped，`pip check` 无依赖冲突。
- Java：Temurin 21.0.12.1；真实 uvicorn 契约开启后 `clean verify` 为 60 tests、0 failures/errors/skipped，含 5 项 Java→Python HTTP 和 2 项 pgvector/Flyway 集成测试。
- E2E：隔离双服务与 pgvector 公共 HTTP 闭环通过 Wiki/Task 召回、两项目隔离、版本替换、删除同步和无匹配来源断言。
- 安全与范围：`git diff --check` 和敏感模式扫描通过；未引入路线图后续组件。五个 Pi 启动入口均返回 `DISABLED`。
- 清理：测试端口、E2E 容器/卷、本轮临时日志和 Python 虚拟环境/缓存均已清理；Maven target 为本轮 clean 产物且被忽略。
