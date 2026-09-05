# Day 5 Tool Calling 与人工确认

- 状态：Implemented
- 日期：2026-09-05
- 阶段：V1 / Day 5

## 背景

Day 4 已能从当前项目检索 Wiki / Task 并返回来源。Day 5 需要跑通“模型提出 create/update task 意图，但业务写入必须等待用户确认并由 Java 执行”的闭环。用户同时明确恢复 Pi 的只读代码审核职责；测试仍全部由 Codex 亲自执行。用户已持续授权把本阶段及后续阶段经本地测试、敏感信息扫描后的源码、配置、测试与文档 diff 发送给外部 DeepSeek Pi V4-pro。

## 范围

- Python Chat graph 在检索后生成可选的结构化 `toolProposal`，只支持 `CREATE_TASK`、`UPDATE_TASK`。
- Java 对 proposal 做白名单、字段、项目、actor 和 Task version 校验，并把有效提案保存为待确认 `agent_task_action`。
- 公共 API 返回 `pendingAction`，并提供 confirm/reject 入口；未确认前不得修改 `task_item`。
- confirm 复用 `TaskService` 执行创建/更新；同一 action 重复确认不重复写入。
- Codex 增加 Python、Java、真实 PostgreSQL 和双服务 E2E 测试，记录全新执行证据。
- Pi 在测试完成后对本次 Git diff 做一次只读审核；不恢复 monitor、OnCodexWake、自动推进或 Pi 测试。

不在本次范围：任意 Tool 注册、删除任务、Redis checkpoint、完整风险引擎/审计日志/idempotency key、LLM provider、Web 确认界面、MCP。

## 影响

- 新增 Flyway V4 迁移与 `agent_task_action` 业务表。
- Chat 内部/公共响应兼容性新增可空 `pendingAction`。
- 新增确认与拒绝公共 API。
- Agent Service 增加确定性 V1 Tool planner；普通问答和 RAG 来源行为保持不变。
- 项目协作规则变为“Pi 只读审核，Codex 测试”。

## 验证计划

1. 按仓库 TDD skill 逐个纵向切片运行失败测试，再做最小实现。
2. Python pytest：普通 Chat 无 proposal；中英文创建意图；显式 UUID + version 更新意图；歧义输入不生成 proposal。
3. Java clean verify：HTTP DTO、proposal 校验、owner/admin 权限、未确认不写 Task、confirm/reject、版本冲突、重复确认不重复写入、Flyway/JPA。
4. 双服务 E2E：创建提案→确认落库；更新提案→确认；拒绝不写；跨用户拒绝；重复确认无副作用。
5. 记录 Java/Python/Docker 版本、实际命令、退出码、测试数、失败/跳过与容器/进程清理证据。
6. Pi 只读审核当前 Day 5 diff；Codex 判断并处理每项 finding，修复后重跑相关测试。
7. Pi 审核以实质风险为交付门槛：可复现严重缺陷、无法运行、重大安全/数据一致性问题、真实冲突、架构边界破坏或明显目标偏离才要求返工；纯风格或未来优化建议不阻断。
8. 用户已授权在 Day 5 审核通过、提交、推送并核验远端后开始 Day 6；在远端核验前不混入 Day 6 实现。

## 回滚思路

- 应用层可回退为忽略 `toolProposal`，Chat 继续作为只读 RAG。
- 已执行的 Task 是用户明确确认后的业务事实，不随功能回滚自动删除。
- 已进入共享环境的 V4 迁移不修改；若需要撤销能力，追加迁移停用/归档 action 表并保留追溯数据。

## 实施与验证回填

2026-09-05：产品、架构、数据、功能、API 与测试目标文档已先行完成。用户核对 record 语法后已明确恢复 Day 5 开发；当前进入 TDD 实施，尚未形成最终测试或 Pi 审核结论。

- 首次 Day 5 E2E 已走到 reject 断言后失败：全局 non-null JSON 策略会省略空 `resultTask`，而 PowerShell StrictMode 直接读取缺失属性。容器、网络、卷、进程与临时日志均由 finally 清理。修复只调整脚本为验证属性不存在，不改变产品契约。

### 已实现文件与链路

- Agent Service：`schemas.py`、`tool_planner.py`、`graph.py`、`api.py` 增加可空 `ToolProposal` 与确定性中英文 create/显式 update planner。
- Core API：新增 action domain/application/API/repository adapter，Chat 将 Python proposal 转为 Java 管理的 pending action；V4 迁移创建 `agent_task_action`。
- 公共 confirm/reject 复用 `TaskService`；事务锁与 action 状态保证重复确认不重复写入，update 继续使用 Task version。
- `scripts/validation/day5-e2e.ps1` 使用独立 Compose project、端口、进程与日志验证双服务闭环，并在 finally 精确清理。

### 当前真实验证证据（Pi 审核前）

- Python：`.venv\Scripts\python.exe -m pytest -q`，退出码 0，16 passed，0 failed，0 skipped；有 2 项上游 deprecation warning 和 1 项 pytest cache warning，不影响断言。
- Java：`mvnw.cmd clean verify`，退出码 0；Java 21.0.12.1、Maven 3.9.11；从零编译 90 个主源码与 18 个测试源码；Tests run 71、Failures 0、Errors 0、Skipped 6。6 项是仅在显式真实 Agent 进程环境变量下启用的 contract class，用 Day 5 E2E 覆盖实际 HTTP 往返。
- PostgreSQL：Java Testcontainers 在 `pgvector/pgvector:pg17` 空库成功校验并执行 V1–V4，JPA `validate` 通过；Python pgvector 集成测试通过。
- 双服务：`.\scripts\validation\day5-e2e.ps1` 最终退出码 0，`status=PASS`；confirm 前 Task 0、重复 confirm 后 1、update version 1、reject 后总数 1、跨用户 403。
- 工具：Python 3.14.3、pytest 8.4.2、Docker Client/Server 29.5.3、Compose v5.1.4。
- 清理：两次 E2E（含首次失败）都删除专用 PostgreSQL 容器、Compose network、volume、Java/Python 进程和临时日志；最终 `docker ps` 与 `%TEMP%\agentforge-day5-*` 检查无残留。
- 静态：`git diff --check` 退出码 0；敏感模式扫描无匹配（`rg` 退出码 1 表示零匹配）。文档先行脚本只比较提交区间，对未提交工作树显示 0 个文件，因此不把该次输出当作有效 Day 5 证明；提交后将用真实提交区间复核。

### Pi Attempt 1 研判与修复计划

DeepSeek Pi V4-pro 已对未截断的 Day 5 工作树 diff 完成一次性只读审核，报告为 `docs/08-reviews/2026-09-05-review-day-5-tool-calling-hitl-attempt-1.md`。

- AF-D5-01 采纳：无效或超长 proposal 不能把公开 Chat 误报为 503；先以公共 Chat 行为测试复现，再让 Java 忽略不可信 proposal、保留普通回答且不保存 action。
- AF-D5-02 采纳：非法 UUID 应退化为无 proposal；先补 Python HTTP 失败测试，再最小修复解析器。
- AF-D5-03 采纳：补齐 createPending stale version 和 confirm 时并发 version 冲突测试，确认冲突返回且 action 保持 PENDING；E2E 证据不再表述为已覆盖冲突，除非实际增加该场景。
- AF-D5-04 采纳：清理当前审核/测试职责与历史 Pi 自动化说明的矛盾，状态恢复为文档制度定义的 `In Progress`。
- AF-D5-05 部分采纳：无效 proposal 降级测试随 AF-D5-01 补齐；ADMIN 与路径错配 404 属低风险补充覆盖，当前权限实现、跨用户 403 和架构边界已有证据，不作为 Day 5 阻断项，不为纯覆盖率扩张返工。

修复后由 Codex 重跑相关定向测试、Python 全量 pytest、Java clean verify 与必要的双服务 E2E；随后把修复后完整 diff 交给 Pi Attempt 2 复审。

### Attempt 1 修复后的真实验证证据

- TDD 红灯：非法 UUID Python HTTP 用例实际得到 422 而非 200；Java 新测试在实现改动前产生 6 个签名/返回类型编译错误，证明 Optional 降级契约尚未实现。首次 Java 命令因 PowerShell 未引用逗号参数而解析失败，修正后沙箱阻止 Maven Wrapper 联网；均未误记为产品失败。
- 定向绿灯：Python `pytest -q tests/test_api.py -k malformed_task_uuid` 退出码 0，1 passed、11 deselected；Java `mvnw.cmd '-Dtest=AgentActionServiceTest,AgentChatServiceTest' test` 退出码 0，11 tests、0 failures/errors/skipped。
- Python 全量：沙箱内首次运行有 16 个非容器测试通过，但 pgvector 用例因 Docker named pipe 访问被拒而失败；以获批外部权限重跑 `.venv\Scripts\python.exe -m pytest -q`，退出码 0，17 passed、0 failed、0 skipped，3 warnings。
- Java 全量：`mvnw.cmd clean verify` 退出码 0；从零编译 90 个主源码与 18 个测试源码；Tests run 75、Failures 0、Errors 0、Skipped 6；空 PostgreSQL 成功执行 V1–V4 且 JPA validate 通过。
- 双服务 E2E：`scripts/validation/day5-e2e.ps1` 退出码 0，`status=PASS`；confirm 前 0、重复 confirm 后 1、update version 1、reject 后总数 1、跨用户 403；专用容器、network、volume、服务进程和日志已清理。
- 修复结果：非法 UUID 退化为无 proposal；Java 对不可信 proposal 返回 empty 并保留普通 Chat，不再返回 503；提案创建 stale version 与确认期并发冲突均有回归测试，后者断言 action 保持 PENDING。

### Pi Attempt 2 与交付结论

Attempt 2 使用 DeepSeek Pi V4-pro 对修复后未截断 diff 和 Attempt 1 报告完成只读复审。报告确认 AF-D5-01 至 AF-D5-04 已实质修复，AF-D5-05 的低风险覆盖豁免成立；没有发现代码无法运行、重大安全/数据一致性问题、架构边界破坏或偏离 Day 5 目标的问题。

唯一新增 AF-D5-06 为 Low：功能文档仍把 version conflict 归到 E2E，实际由 `AgentActionServiceTest` 覆盖。遵照用户“禁止为小问题反复修改与审核”的明确要求，本项登记为 Day 6 审核时统一处理，不触发 Day 5 第三轮。Day 5 以 `ACCEPTED_WITH_DEFERRED_LOW` 完成交付门禁；提交和远端核验完成后开始已授权的 Day 6。

提交前已精确删除本轮生成的 `services/agent-service/.venv`、`.pytest_cache`、两个 `__pycache__` 与 `services/core-api/target`；删除前逐个解析绝对路径并验证其位于 AgentForge 工作区内，复查无同类残留。路线 DOCX 与 Word 锁文件属于用户本地输入，不纳入提交。
