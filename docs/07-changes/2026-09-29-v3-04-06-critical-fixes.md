# V3-04～V3-06 严重缺陷修复

- 日期：2026-09-29
- 状态：Implemented
- Base：`ebafcfd6991436a8f561c9a31d2cd6da4afb7d26`（`origin/codex/v3-06-entity-resolution`，fetch 后一致）；独立分支 `codex/v3-04-06-critical-fixes`；开始时工作树干净。
- 依据：2026-09-29 对 V3-04～06 的代码审查；本次只处理阻断与高风险四项，不启动 V3-07。

## 问题与目标

1. 并发清图时邻接读取偶发 503；审查复测 48 个 Java 图测试中 1 个失败，失败用例只接受 200/404。
2. 清图只删除 Neo4j 派生节点，既有 PostgreSQL 人工合并在同稳定 ID 和版本重建后复活；旧来源待办也可能在清图后重新填图。
3. 候选建议会把已映射成员当成规范锚点，并可能推荐最终确认接口必然拒绝的 ID。
4. 消歧请求的 Long 版本字段允许 Jackson 把 JSON 小数截断为整数，绕过严格 CAS 输入类型。

## 范围与文档顺序

先记录本变更，更新 Graph Domain、Entity Resolution、Core API、数据架构和跨存储清理 ADR；再在项目 Graph HTTP 与 resolution HTTP 公共 seam 逐片写红灯和最小实现。图清理采用项目串行化与持久 generation：清理时删除旧 member 映射；canonical 行与事件保留审计，但只有当前 generation 的 canonical 有效，新的人工确认必须显式发生。同步待办与清理使用同一项目锁，避免清理前的任务晚于清理重填。依然不宣称 Neo4j/PostgreSQL 原子提交；依赖失败优先保持旧决策不可复活。

## 风险与门禁

门禁规划器 `-Paths`：L3，CoreApi/Docs，要求 database-integration、java-clean-verify、diff-check、docs-consistency、gitleaks-final、Pi Milestone Review。现有并发测试先作为红灯；其余问题在真实 PostgreSQL/Neo4j HTTP seam 新增可观测回归。完成后回填实际命令、统计、warning/skip、敏感扫描、Pi findings、清理和限制。


## 实现结果

- V14 新增项目图 `generation/resetting` 状态；clear 先在 PostgreSQL 提交新代次、删除旧成员映射和旧同步待办，再清 Neo4j，并在结束时恢复同步。解析写入和 worker 对项目状态取共享锁，clear 以排他更新等待在途写入，重叠 clear 返回 503。
- 旧 canonical 行通过 generation 隔离；稳定实体 ID 重建后旧合并保持 `UNMAPPED`，人工可用 `expectedVersion=0` 重新确认。跨库仍不声明原子提交；Neo4j 清理失败时旧映射已失效并可重试。
- Neo4j 只读事务最多尝试三次，清理并发导致的瞬时驱动异常不再直接升级为 503；持续故障仍映射为 503。
- 建议阶段把已确认成员解析为其 canonical 并按 ID 去重，过滤确认接口不接受的锚点；canonical 本身仍可承接其他成员。
- resolution 请求的三个版本字段及 canonical 编辑版本字段使用严格整数反序列化，小数和数字字符串返回 400。

## 验证证据

- 红灯：`GraphResolutionIntegrationTest#resolutionVersionsRequireJsonIntegers` 首次运行退出 1，`expectedVersion: 0.5` 被错误接受为 200；修复后退出 0。
- 红灯：`GraphResolutionIntegrationTest#suggestionExcludesMappedMembersThatCannotBeAnchors` 首次运行退出 1，候选包含已确认 member；修复后退出 0。
- 红灯：`GraphResolutionIntegrationTest#clearInvalidatesResolutionAfterStableIdsAreRebuilt` 首次运行退出 1，重建后状态仍为 `CONFIRMED`；修复后退出 0，并验证旧待办删除、canonical 失效及版本 0 重新确认。
- `mvnw.cmd -Dtest=GraphResolutionIntegrationTest#clearInvalidatesResolutionAfterStableIdsAreRebuilt,GraphApiIntegrationTest#concurrentCleanupNeverTurnsNeighborReadIntoServerError test -q`：退出 0；并发清理用例完成 8 轮。
- `mvnw.cmd -Dtest=GraphResolutionIntegrationTest,GraphExtractionIntegrationTest test -q`：退出 0；两套各 14 个测试，0 failure/error/skip。
- `mvnw.cmd clean verify -q`：Java 21.0.12.1，退出 0；33 个报告、198 tests、0 failure、0 error、9 skipped。8 个 Agent Service HTTP contract 因未设置 `AGENTFORGE_AGENT_CONTRACT_TEST` 跳过，1 个 Python live contract 按环境假设跳过。
- 完整 clean verify 保留了既有 warning：Mockito 动态 agent 提示、故障注入用例的 deferred warning，以及不同 `@DirtiesContext` 图测试容器切换期间旧 scheduler 对已关闭 PostgreSQL 连接的一次超时日志；它们未造成测试失败，本次未把该既有测试生命周期问题混入严重缺陷修复。
- `git diff --check`：退出 0；仅报告 Windows 工作树的 CRLF→LF 提示。
- `gitleaks v8.30.1 stdin` 扫描最终完整 diff：退出 0，no leaks found。
- DeepSeek Pi Milestone Review Attempt 1：NEEDS_FIX；唯一阻断项为失效 resetting 无接管路径。已实现五分钟租约接管、generation 条件 finish，并同时修复审查指出的锁升级、worker 队首阻塞、409 文档、失败日志与异常掩盖问题。
- 修复后复测：mvnw.cmd -Dtest=GraphResolutionIntegrationTest,GraphExtractionIntegrationTest,GraphApiIntegrationTest#concurrentCleanupNeverTurnsNeighborReadIntoServerError test -q 退出 0；第二次 mvnw.cmd clean verify -q 退出 0，33 个报告、198 tests、0 failure、0 error、9 skipped。
- DeepSeek Pi Milestone Review Attempt 2：首次连接以退出码 1 和 Connection error 结束；用户恢复后重试得到 NEEDS_FIX。唯一阻断项是消歧写路径在项目校验前创建 lifecycle state，使不存在项目由 404 回归为 409。
- 阻断项红灯：GraphResolutionIntegrationTest#resolutionWriteForMissingProjectReturnsNotFound 首次运行退出 1，期望 404、实际 409；三条消歧写路径先执行 ProjectAccess 后，单测退出 0。
- 阻断项修复后相关图集成套件退出 0；最终 mvnw.cmd clean verify -q 退出 0，33 个报告、199 tests、0 failure、0 error、9 skipped。
- 已知限制：五分钟 stale-reset 租约没有心跳，单次 Neo4j 清理若超过租约可能与接管请求重叠；图为可重建派生投影。本次没有真实大图超时证据，不引入后台续租机制，运行中通过 graph_project_state.updated_at 观察。
- DeepSeek Pi Milestone Review Attempt 3：PASS，无必须修改项。S1/S5 的租约与失败重试恢复限制已同步 ADR 和运行手册；其余为有界重试、候选解释与额外覆盖建议，不阻断本次严重缺陷交付。
