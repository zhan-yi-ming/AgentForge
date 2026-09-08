# V2-07 LangGraph Checkpoint / Interrupt / Resume

- 日期：2026-09-09
- 状态：Implemented
- 阶段：V2-07
- 目标分支：`codex/v2-07-langgraph-checkpoint-resume`
- 基线：`3467662a1e4dc900232c0c882e75578833940c14`
- Start Gate：用户已于 2026-09-09 确认 V2-07 Scope 与六个公共测试 seam。

## 背景与目标

V2-06 已由 Java 持久化 Approval、幂等执行与审计，但 Python 在返回 Tool proposal 后没有可恢复的运行态；现有 LangGraph 每次请求临时编译，进程重启会丢失等待点。V2-07 将为待决 Action 建立 PostgreSQL-backed LangGraph checkpoint，以稳定 conversation/thread ID 暂停并恢复，同时保持 Java 独占权限、审批和业务写入。

目标验收链路为：`User Request → Agent Interrupt → Python restart → User decision → Resume → Java deterministic execution`。恢复或执行任一步响应丢失后，同一 Idempotency Key 可安全重试，Task 最多写一次。

## Scope

- 引入 LangGraph PostgreSQL checkpointer，并在 Agent Service 启动时初始化其自有 checkpoint schema。
- 为同步与流式 Chat 共用的待决 Action 工作流建立动态 `interrupt()`；完整 Namespace 派生物理 thread key，普通无 Tool 回答不创建等待 checkpoint。
- 新增内部 Resume HTTP 契约，按 tenant/workspace/project/user/thread 绑定恢复，拒绝旧 Thread 或错误作用域。
- Java 将 confirm 拆为持久化批准、恢复 Agent、执行已批准 Tool三个可重试阶段；reject 也恢复等待中的 Agent。
- checkpoint state 携带显式 schema version、proposal 指纹、decision/action/idempotency 元数据，不保存 JWT、内部 token、完整 Prompt 或检索正文。
- 对重复 Resume、服务重启、不同 key/decision、旧 schema 和恢复/执行间崩溃建立失败关闭行为。
- 以可空 workflow version 区分升级前 Action；V2-06 旧待决记录不调用不存在的 checkpoint，V2-07 新记录强制 Resume。

## 非目标

- 不把业务 Tool、RBAC、Risk、Approval 或 Audit 的最终控制权移入 Python。
- 不用 checkpoint 取代 Core 会话展示历史或 V2-03 Conversation Summary。
- 不实现 V2-08 Evaluation、V2-09 Release Gate、通用队列、时间旅行 UI 或 checkpoint 管理后台。

## 预确认公共测试 Seam

1. Agent Service Internal HTTP：Chat 产生等待 checkpoint；Resume 的成功、稳定 replay、作用域冲突与旧 schema 失败。
2. LangGraph Action Runtime：从公开 workflow invoke/state 接口观察 interrupt、resume 与完成状态，不测试私有节点。
3. PostgreSQL：第一 runtime 创建 interrupt，释放后由第二 runtime 使用同一数据库恢复。
4. Core HTTP/Application：confirm/reject 的权限、策略、幂等键与三阶段恢复编排。
5. Java ↔ Python：真实 `Interrupt → restart → Confirm → Resume → Tool execute`，Task 只写一次。
6. 回归门禁：Agent pytest、Core `clean verify`、Compose config、跨进程 smoke、文档一致性与敏感扫描。

## 风险与门禁

本节点改变 Agent 状态机、公共 confirm 行为、跨服务内部契约、持久化策略和部署配置，风险为 L3，影响域为 Agent Service、Core API、Deployment、Docs。规划器最低门禁为 Python tests、Java clean verify、Compose config、cross-process smoke、diff/docs check、Gitleaks 与 Pi Milestone Review。

## 实现与验证进展

- 已实现 PostgreSQL checkpointer、动态 interrupt、内部 Resume、Java 三阶段 confirm/reject 编排、完整 Namespace 隔离和 V2-06 旧 Action 兼容标记。
- TDD 红灯覆盖缺失 runtime、缺失 PostgreSQL opener、缺失 HTTP dependency/route、Namespace 串扰、缺失 Java approve/execute seam、缺失 workflow orchestration、错误 Resume 响应和升级前 Action 无 checkpoint。
- Agent Service 最终全量：88 passed、4 warnings；warnings 为既有 Starlette/HTTP 422 deprecation 与 Windows `.pytest_cache` ACL，不含 skip/failure。真实 PostgreSQL 定向测试包含跨 runtime 重启和连接池并发 8 个 interrupt / 8 个 resume，并覆盖恢复完成后同一 Namespace 建立第二轮等待。
- Core API 最终 `mvnw.cmd clean verify`：108 passed、0 failure/error、8 条 `AGENTFORGE_AGENT_CONTRACT_TEST` 条件跳过；Java 21.0.12.1，PostgreSQL 17.11，Flyway V1–V8、JPA validate 与 7 条持久化集成测试通过。真实 Java↔Python 关键 Resume 由下述跨进程 smoke 覆盖。
- 真实跨进程 smoke：`PENDING → Python restart → EXECUTED → same-key replay` 通过，Task 写入 1 次，持久化 checkpoint 3 条，隔离容器与卷清理成功。
- Compose config 退出 0；Docker 因受限沙箱无法读取用户级 `config.json` 输出两条 warning，但配置解析成功。PowerShell parser、`git diff --cached --check` 与文档/影响范围人工核对通过。
- Gitleaks v8.30.1 初次准确识别脚本内两个固定测试 token；改为运行时随机生成全部测试凭据后，重新执行跨进程 smoke 通过。报告回填后的 staged diff 扫描约 145 KB，退出 0、`no leaks found`。
- DeepSeek Pi V4-pro Attempt 1 返回 NEEDS_FIX：唯一 High finding 对单连接并发风险的“写入交错”判断忽略了 saver 内置锁，但串行瓶颈成立，已升级为连接池并新增真实并发回归；5 条建议也已收敛。Attempt 2 返回 PASS、无新的阻塞项；唯一 Low 测试建议已补充且定向/全量 pytest 通过，不触发第三轮。报告见 `../08-reviews/2026-09-09-review-v2-07-langgraph-checkpoint-resume-attempt-1.md` 与 `../08-reviews/2026-09-09-review-v2-07-langgraph-checkpoint-resume-attempt-2.md`。
- Close Gate：规划器 15 个代表性契约检查通过；最终 INDEX 规划为 L3/Milestone，必需门禁均已覆盖。跨进程脚本解析成功，V2-07 Compose 容器/卷和端口 18007/18087/55437 无残留；用户原有 Markdown、`.worktrees/` 与 DOCX 未进入暂存、扫描或 Pi 输入。
