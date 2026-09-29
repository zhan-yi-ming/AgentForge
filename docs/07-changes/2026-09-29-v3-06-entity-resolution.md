# V3-06 Entity Resolution

- 日期：2026-09-29
- 状态：Implemented（Milestone Review Attempt 2 PASS；提交与远端核验待完成）
- Base：`2a0281df0e2484740b9bbf9b96e4f48586df0e71`，已核对 `origin/codex/v3-05-graph-extraction` 的 FETCH_HEAD 与本地 HEAD 一致。
- 分支：`codex/v3-06-entity-resolution`，复用干净托管工作树；主检出的已有文档修改、`.worktrees/` 与规划 docx 保留。
- Start Gate：用户已明确确认。公共测试 seam：项目 Graph HTTP（候选、确认、撤销和可见映射）及 Java↔Python 内部建议契约；优先真实 PostgreSQL/Neo4j，外部 LLM 仅在系统边界替身。

## 目标与范围

V3-05 的 Service/API/Issue 按来源作用域创建，同一真实实体可能分裂成多节点；重名也不必然代表同一实体。V3-06 提供候选检索、确定性规则与 embedding 评分、LLM 消歧建议、带置信度的待确认提案及人工确认/撤销。只有 Java 在项目权限和 CAS 检查后持久化规范实体与 alias/成员映射。LLM 输出不直接变更图，拒绝“名称相同即合并”。保留原始图节点、关系和 evidence；规范映射为可撤销覆盖层，避免不可逆重接线。GraphRAG 检索/回答留到 V3-07。

## 预期设计与文档顺序

本记录先行；随后更新功能、API、后端/数据架构及 ADR，最后实施。PostgreSQL 保存人工确认的规范实体、别名、来源、metadata 与决策历史；Neo4j 继续保存可重建的来源图。候选和建议必须限定同项目、同可消歧类型，并重新验证当前来源版本。未确认、低置信度、依赖失败均不改变已确认映射；撤销后可依据审计历史人工重新确认。

## 风险与验证计划

初判 L3：Schema、权限、公共 API、跨服务建议契约和可撤销合并均涉及数据一致性。先运行门禁规划器，并按 TDD 在已确认的公共 seam 上一次一个纵向切片：候选可见 → 建议不写入 → 人工确认 → 错误合并撤销 → 权限/并发/来源失效。Core API 运行 Java clean verify 与真实数据库集成；若修改 Agent Service，运行 Python pytest、Java↔Python 契约及跨进程 smoke。文档一致性、diff check、敏感扫描后做 Pi Milestone Review，回填机器证据并经 Close Gate 才提交推送。

## 验证回填

待实施后记录命令、工具版本、退出码、passed/failed/skipped、warning、清理结果与风险限制。V3-05 的历史 PASS 不代替本节点证据。

## Slice 1：候选可见

`GraphResolutionIntegrationTest#suggestionOffersSameProjectCandidateWithoutMerging` 经真实 Wiki HTTP/Neo4j 建立两个来源作用域 Service 后，先红 1 failed（建议入口 404），新增 Java 项目内候选读取及 Graph HTTP 后同命令 exit 0。建议仅排序并固定 reviewRequired=true，无任何合并写入。测试从来源 ID 查真实实体，避免依赖实现生成的稳定 UUID。

## Slice 2 设计：人工确认与持久化

V13 拟建 `graph_canonical_entity`（项目+锚点 ID、类型、名称、受限 metadata、锚点来源版本、CAS version）、`graph_resolution_member`（项目+成员 ID、规范 ID、成员来源版本、aliases、confidence、状态、CAS version）和追加式 `graph_resolution_event`。首次确认创建规范实体；已有规范实体不得因另一个成员的请求悄悄改名/改 metadata。成员的撤销保留 tombstone 与递增版本，防止 expectedVersion=0 的旧请求重建已撤销映射。读取重验成员和锚点来源。
## Slice 2：确认持久化

`GraphResolutionIntegrationTest#humanCanConfirmAndReadCanonicalMapping` 先红 1 failed（PUT 404）；增加 V13 三表、Java 当前来源重验、人工确认 CAS 与追加式事件、GET 当前映射后，同命令 exit 0。测试以两个不同来源 Service 为输入，确认规范名、alias 与受限 metadata 能从真实 PostgreSQL 读回；Neo4j 原始节点保持来源作用域。
## Slice 3：Python 内部建议

`test_entity_resolution.py` 先红 2 failed（内部入口 404），再加内部 token 保护、最多 20 个候选的规则/Hash embedding 评分、可选 REVIEW 模型消歧与白名单校验，5 passed。模型越界或失败明确 abstain；全部建议维持 reviewRequired。现接入 Java HTTP 客户端并完成两侧契约与跨进程验证。

## Slice 4 设计：并发来源检查

确认请求包含建议时所见成员与规范锚点的来源版本 `sourceVersion` / `canonicalSourceVersion`。Java 在写入前对当前实体来源版本逐一比较；不同则 409。候选与读取仍重新检查当前来源。公开决策不能仅以成员 CAS 版本防止“建议后 Wiki/Task 改写”的过期确认。

## Slice 5 设计：规范实体名称与 metadata 管理

除成员确认外，提供 `GET/PUT /canonicals/{canonicalId}`。PUT 限受限 metadata key，并校验锚点当前来源版本与规范实体 CAS version；名称/metadata 变更追加 `graph_canonical_event`。成员决策仍引用同一规范 ID，不改写原始来源图。


## Slice 3–5 与影响域验收

- `GraphResolutionAdvisorContractTest`：先红（缺少客户端类，编译失败），后绿；HTTP 契约校验 Java 只接受白名单候选，依赖 5xx 降级 abstain。
- `GraphResolutionIntegrationTest#graphUsesOnlyAllowlistedAgentAdviceWithoutWritingMapping`：先红（推荐字段为空），后绿；真实 Graph HTTP、PostgreSQL/Neo4j 联动，建议不写入映射。
- `GraphResolutionIntegrationTest#confirmationRejectsStaleSourceVersions`：先红（过期版本错误返回 200），后绿（409，未映射）。
- `GraphResolutionIntegrationTest#canonicalNameAndMetadataCanBeEditedWithAuditAndCas`：先红（入口 404），后绿（GET/PUT、版本递增、映射读取更新、旧 CAS 409）。
- 集成覆盖跨项目目标 404、无权项目 403、来源更新后旧映射隐藏、两个不同名成员确认同一规范 ID 后独立撤销；11 个 V3-06 Graph 测试最终通过。

## 当前机器门禁

工作目录为本变更托管工作树。Java 在 `services/core-api` 使用 Maven Wrapper 3.9.11 / Temurin Java 21.0.12.1；Python 在 `services/agent-service` 使用主检出已有 venv 的 Python 3.14.3 / pytest 8.4.2，但 `PYTHONPATH` 明确指向本工作树 `src`，`--cache-clear` 排除旧缓存。

| Gate | 实际命令或调用 | 结果 |
| --- | --- | --- |
| 规划 | `./scripts/validation/plan-change-gates.ps1 -Milestone -Json`（工作树根） | L3；CoreApi、AgentService、Docs；cross-process-smoke、database-integration、diff-check、docs-consistency、gitleaks-final、java-clean-verify、python-test；Milestone Review。 |
| Python 全套首次 | `python -m pytest --cache-clear -q`（本工作树 `services/agent-service`，上述 venv 和 PYTHONPATH） | exit 1：181 passed、1 failed、5 errors、4 warnings；系统 temp 目录 WinError 5，localhost 模拟 provider 受代理影响。 |
| Python 全套复跑 | `python -m pytest --cache-clear --basetemp <worktree>/.data/pytest-v306-full-2 -q`（同上，`NO_PROXY=127.0.0.1,localhost`） | exit 0：187 passed、0 failed、0 skipped、4 warnings（Starlette/anyio 弃用 3 条，Pydantic TypedDict 提示 1 条）。 |
| Java clean 首次 | `./mvnw.cmd clean verify -q`（本工作树 `services/core-api`；PATH 排除主机错误的 msedgedriver 条目） | exit 1：192 tests、1 failed、9 skipped；既有 Graph 并发清理测试偶发 503。 |
| 并发清理重跑 | `./mvnw.cmd '-Dtest=GraphApiIntegrationTest#concurrentCleanupNeverTurnsNeighborReadIntoServerError' test -q` | exit 0：1 passed；未能稳定复现首次 503。 |
| Java clean 复跑 | `./mvnw.cmd clean verify -q`（同上） | exit 0：192 tests、0 failed、0 errors、9 skipped。 |
| 新增 false split 测试 | `./mvnw.cmd '-Dtest=GraphResolutionIntegrationTest#twoDistinctNamesCanShareOneCanonicalAndRevertingOnePreservesOther' test -q` | exit 0：1 passed。 |
| Review 前 Java clean | `./mvnw.cmd clean verify -q`（同上） | exit 0：193 tests、0 failed、0 errors、9 skipped；包括 9 个 V3-06 Graph 集成测试。 |
| Java↔Python 真实进程 | 启动本工作树 Python API 的临时 localhost Uvicorn，运行 `./mvnw.cmd '-Dtest=GraphResolutionAdvisorContractTest#livePythonProcessAcceptsJavaContract' test -q`，最后停止服务 | 首次因测试客户端未使用生产 HTTP/1.1 配置导致 body 为空、422；切换至与生产一致的 JDK HTTP/1.1 后 exit 0：1 passed、Python 返回 200、服务已停止。 |

Java 最终成功日志含既有测试模拟数据库不可用时的 `Connection refused` 堆栈和 Mockito 动态 Agent warning；这是测试内预期故障注入，最终测试统计为 0 errors。9 skipped 来自模块既有条件测试，未被隐藏。首次并发测试的间歇性 503 仍作为限制记录，未在本节点无稳定复现时改动 V3-04 清理实现。

## 清理与发布前核对

临时 smoke 服务已停止；本次测试日志、pytest 临时文件保存在被 Git 忽略的 `.data/`，不进入提交。候选有 500 个图节点扫描上限，最多向 Python 送 20 个候选；Hash embedding 和确定性规则可能漏掉弱相似实体，因此所有建议只提示人工，V3-07 才消费规范映射。敏感扫描与 Pi 审核结果见下；最终提交和远端引用核验在 Close Gate 后执行。

## Milestone Review Attempt 1 与修复设计

Pi 报告 `docs/08-reviews/2026-09-29-review-v3-06-entity-resolution-attempt-1.md` 为 NEEDS_FIX。MR-01 证实：`anchor_source_version` 只写，锚点来源更新后规范实体永久 404/409。修复采用人工确认事务内 CAS 刷新锚点版本并追加来源刷新审计；成员记录其确认时锚点来源版本，防止刷新后旧成员自动恢复。MR-02 的自映射和循环链式风险也属于真实数据一致性问题：规范锚点不允许作为成员，已有规范实体不能再映射到另一规范实体。先写公共 Graph HTTP 红灯，再做实现。MR-03 加强来源变更测试；MR-04/05 为建议，按当前范围不因无可复现缺陷扩展测试或重构；MR-06 在最终提交前统一状态；MR-07/08 无需修改。

## Review 修复机器证据与复审输入

`GraphResolutionIntegrationTest#refreshedAnchorRequiresEachMemberToBeReconfirmed+canonicalAnchorsCannotBecomeMembersOrFormCycles`（`services/core-api`，Maven Wrapper 3.9.11 / Java 21.0.12.1）修复前 exit 1：2 failed，分别为刷新 409（期望 200）与自映射 200（期望 400）；在 V13 加成员确认时锚点版本与规范来源刷新审计、Java CAS 刷新和锚点不成成员约束后，同命令 exit 0：2 passed。`#sourceChangeHidesExistingMemberAlias` 等待稳定来源版本并固定断言 UNMAPPED/version 1，exit 0：1 passed。随后 `./mvnw.cmd clean verify -q` 由 Codex 当前机器重新执行，exit 0：195 tests、0 failures、0 errors、9 skipped，其中 V3-06 Graph 11 tests。Python 源码、内部契约与依赖未变，复用本任务此前 187 passed / 4 warnings 的 `--cache-clear` 证据；Java↔Python smoke 传输层未变，复用本任务先前真实进程通过证据。

Attempt 1 Pi findings 逐条事实判断已回填对应报告：MR-01 阻塞与 MR-02 数据一致性风险已修复，MR-03 测试信号已加强；MR-04/05 为无当前缺陷复现的建议，MR-06 统一审核中状态，MR-07/08 不构成可达缺陷。初始 86,070 字符/24 文件 diff 经 `git diff --check` 与 Pi 启动器同款本地敏感模式扫描，0 命中，且无 `.env`/日志/密钥文件。`gitleaks` 可执行程序本机不可用，因此保留此门禁限制并使用仓库审核脚本自带的差异敏感扫描；复审前对修复后的完整 diff 再扫描。


## Milestone Review 结论与最终验证

`run-review.ps1 -StageName v3-06-entity-resolution -BaseRef HEAD -TargetRef WORKTREE -ReviewMode Milestone -ContextFiles docs/01-product/v2-v3-node-roadmap.md,docs/00-governance/v2-v3-node-development-protocol.md -Attempt 1 -TimeoutSeconds 900`（工作树根）exit 0，Pi 结果 NEEDS_FIX，报告保存在 `docs/08-reviews/2026-09-29-review-v3-06-entity-resolution-attempt-1.md`。阻塞项修复和机器重测后，以同一命令加入 `-PriorReportPath docs/08-reviews/2026-09-29-review-v3-06-entity-resolution-attempt-1.md -Attempt 2`，exit 0，Pi 结果 PASS，报告保存在 `docs/08-reviews/2026-09-29-review-v3-06-entity-resolution-attempt-2.md`。Pi 未运行测试；所有 finding 的 Codex 事实判断已回填两份报告。非阻塞的 N+1、历史失效成员不能撤销、Hash embedding 弱相似漏检与全标点名称排序限制已明确记录；本节点未实现 V3-07 GraphRAG 消费。

最终文本规范化之后，由 Codex 在当前工作树再次并行执行 `./mvnw.cmd clean verify -q`（`services/core-api`，Maven 3.9.11 / Java 21.0.12.1；PATH 排除主机错误的 msedgedriver 条目）与 `python -m pytest --cache-clear --basetemp <worktree>/.data/pytest-v306-final -q`（`services/agent-service`，Python 3.14.3 / pytest 8.4.2；PYTHONPATH 指向本工作树 src；NO_PROXY=127.0.0.1,localhost）。前者 exit 0：195 tests、0 failures、0 errors、9 skipped；后者 exit 0：187 passed、0 failed、0 skipped、4 条已有依赖 warning。最终成功日志保存在被忽略的 `.data/`，不入提交。全部本节点 PostgreSQL/Neo4j 容器测试均由当前机器执行并释放；临时 Uvicorn smoke 端口无监听进程。

Git 最终差异执行 `git diff --check`、路径/状态核对与 Pi 启动器同款私钥/Token/JWT/Bearer 等模式扫描（0 命中）；本机 `gitleaks` 不可用，未声称其运行通过。提交范围只包含本节点 26 个源文件/文档及两份审核报告，主检出的用户已有改动未触碰。回填前门禁规划器 fingerprint 为 `6974c057e3d9c4350f6c696f38e962c47fa8eefd996332bdbe601572bbe6a337`，风险 L3、影响域 CoreApi/AgentService/Docs、审核 Milestone；Node Close Gate 在提交前另行核对。
