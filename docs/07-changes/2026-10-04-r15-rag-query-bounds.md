# R15 RAG 查询路径容量边界

- 日期：2026-10-04
- 状态：Implemented
- 风险：L3（Java↔Python 内部契约、RAG 派生索引 schema 与检索一致性）
- 范围：Core RAG 来源接口、Agent 来源同步/文本召回、PostgreSQL 派生索引

## 问题核实

报告问题成立。当前每次 Chat 都从 Core 搬运项目全部 Wiki/Task 正文；即使来源代际没有变化，Agent 仍进入同步调用。RagStore.search 还会先读取项目全部 Chunk，再在 Python 内存中计算 BM25。top_k/candidate_k 只限制最终排名，不限制查询路径传输的正文量。

## 目标契约与公共 seam

本次确认的公共 seam 是 POST /internal/v1/rag/sources、RetrievalService.retrieve 与真实 PostgreSQL RagStore.synchronize/search。

来源请求新增可选非负 knownSnapshotVersion，响应新增 sourcesChanged。Core 始终先验证内部 token、用户和项目访问，再读取当前来源代际；当客户端已应用代际相同时返回 sourcesChanged=false,sources=[]，且不读取 Wiki/Task 正文。代际不同或客户端没有本地代际时返回完整授权来源并标记 sourcesChanged=true，保留全量集合的删除传播语义。Agent 只对变化快照执行同步，因此不能用分页集合误删未出现的来源。

rag_chunk 增加由 title/content 生成的 tsvector 与 GIN 索引。词法与向量查询都在 PostgreSQL 内限制为 candidate_k，Python 只接收两个候选集合的并集再做 RRF，不再读取项目全部 Chunk。Graph evidence 只按 Core 返回的至多 40 个来源身份、版本和 excerpt 在相同索引代际中做有界匹配；不依赖每次携带完整来源正文。

## TDD 与验证计划

1. Java 服务/API 测试先要求匹配 knownSnapshotVersion 时仍授权、但不读取来源，并返回明确未变化响应。
2. Python retrieval/core client 测试先要求复用本地代际且只在变化时同步；Graph evidence 在未携带全量来源时仍从同代际索引完成有界验证。
3. 真实 pgvector 测试先要求候选正文数量受两个候选集合上限约束，并执行新词法索引迁移。
4. 最小实现后运行 Core clean verify、Agent 全量 pytest、包含 V18 生成列读写的数据库角色门禁、真实跨进程契约、diff check、Gitleaks 与 Pi Milestone Review。

## 验证回填

### TDD 红灯与最小实现

- Java 红灯：`services/core-api/.\mvnw.cmd -q -Dtest=RagSourceServiceTest clean test` 因 `RagSourceService.snapshot` 尚无 `knownSnapshotVersion` seam、响应尚无 `sourcesChanged` 而编译失败；实现授权后代际短路与响应契约后，`-Dtest=RagSourceServiceTest,RagSourcesApiTest clean test` 退出 0。
- Python 红灯：新增“复用本地代际”和“仅变化时同步”两个契约测试后，旧 `CoreApiClient.fetch_sources` 不接受 `known_snapshot_version`，且 retrieval 未传递本地代际；最小实现后定向运行结果为 `2 passed, 3 deselected`。
- 真实 PostgreSQL 红灯：新增有界词法预选集成测试首次运行时暴露 V18 与查询 SQL 的引号转义错误（`''simple''`，PostgreSQL `SyntaxError`）；按可证伪假设检查生成 SQL 后修正为 `'simple'`，复跑为 `1 passed`。随后受影响 GraphRAG/Repository 组先发现无图匹配仍读取 evidence 的多余调用，收窄条件后在安全 basetemp 下为 `13 passed`。

### 当前机器绿灯

- 门禁规划：`.\scripts\validation\plan-change-gates.ps1 -BaseRef HEAD -TargetRef WORKTREE -Json` 退出 0；`L3`，影响域 `AgentService/CoreApi/Docs/Unknown`，要求 cross-process、database、Java clean verify、Python、docs、diff 与 Gitleaks，Milestone Review；fingerprint `d7413b02b8ec495939453dc30b60b262533decf8dfe368370acf50e44a02cd2d`。`database-role-boundary.ps1` 属人工核实的验证脚本增量，不扩大产品运行路径。
- Agent Service 全量：使用主工作树 `.venv` 解释器、工作树 `PYTHONPATH`、`NO_PROXY=127.0.0.1,localhost` 与已验证的工作树内临时 basetemp，结果 `224 passed, 4 warnings`，退出 0；warning 为既有 Starlette anyio alias、HTTP 422 常量弃用两项与 Pydantic ReadOnly 注解提示，没有隐藏失败或 skip。
- Core API 全量：`services/core-api/.\mvnw.cmd -q clean verify` 在最终相关输入上退出 0；Surefire 35 份 XML 合计 `242 tests, 0 failures, 0 errors, 12 skipped`。12 个 skip 是未设置外部进程开关时的 9 个 Agent HTTP 合约、1 个 Repository cross-process、1 个 Graph cross-process 与 1 个在线 advisor；这些关键外部进程路径另由严格门禁执行。第一次全量运行除已修正的 V18 预期版本外，Graph 并发清理用例曾得到一次 503；定向 clean 复跑和第二次全量均通过，未能复现，且 R15 未改图谱路径，因此未做迎合性修改。运行期关闭 Testcontainers 后出现的 Hikari/scheduler 连接告警已保留判断，不影响退出码或断言。
- Java↔Python 真实门禁：仅在确认工作树目标不存在后创建指向主工作树 venv 的临时 Junction，使用工作树 `PYTHONPATH` 执行 `.\scripts\validation\v3-release-regression.ps1 -Only java-python-contracts -Json`，严格 `13/13`、退出 0；finally 核对 LinkType 后只删除 Junction，源 venv 仍存在。
- 数据库角色边界：更新后的 `.\scripts\validation\database-role-boundary.ps1` 在临时 PostgreSQL 17 容器中完整应用 V1–V18，证明 Agent 可写派生 Chunk、读取 V18 生成的 `search_document`，同时仍不能读取 Wiki/Task/来源代际；退出 0、`Database role boundary validation PASS`，finally 删除临时容器。
- 真实 pgvector 定向：集成文件为 `1 passed`；除证明词法候选受 `candidate_k` 限制且查询使用 V18 生成列/索引契约外，还真实执行 `load_graph_evidence` 的命中、来源版本错配、快照代际错配和跨 Chunk excerpt 负例。
- 清洁检查：临时 pytest basetemp 已在路径校验后删除；工作树 Junction 已删除且源 venv 完好；数据库角色临时容器已由脚本清理。`git diff --check` 与首次 `git diff --cached --check` 均退出 0。
- 提交前首次敏感扫描：仅暂存 24 个 R15 文件后，将约 `60.18 KB` 完整 staged diff 传给本机已有 `zricethezav/gitleaks:v8.30.1 detect --pipe --no-banner --redact --exit-code 1`，退出 0、`no leaks found`。加入运维兼容说明和本验证回填后将重跑最终扫描。

### Pi Attempt 1 处置

Pi Attempt 1 返回 `NEEDS_FIX`，报告为 `docs/08-reviews/2026-10-04-review-main-review-r15-attempt-1.md`。唯一必须修改项 M1（新增 `load_graph_evidence` SQL 缺真实 PostgreSQL 证据）成立；补入真实 pgvector 命中及三个失败关闭负例，结果 `1 passed`。建议项 S1 指出的变化/未变化快照证据语义不一致也可在公共 seam 复现：新增断言实际红灯为 `graph_loads == 0`；随后统一为同步后始终从同代际 Chunk 验证，GraphRAG 文件 `5 passed`。

相关生产 Python 输入变化后重新运行：Java↔Python 严格门禁 `13/13`、退出 0；Agent Service 串行全量 `224 passed, 4 warnings`、退出 0。第一次把两者并行运行时，跨进程门禁清理共享 `.tmp` 父目录，使 Agent 全量后段 13 个 fixture setup 得到 `FileNotFoundError`（当时已有 `211 passed`）；确认是验证编排冲突后改用专用 basetemp 串行重跑通过，没有隐藏该失败。

其余 finding：S2 的 CJK `simple` 词法限制成立但为检索质量建议，不在容量/一致性修复中引入新扩展或改写排序；已在功能文档明确无空格 CJK 可能只走向量路，留给独立检索质量切片。S3 的独立代际提示读取不会造成错代际返回，因为 `search` 在可重复读事务内再次校验并失败关闭，不修改。S4 是负参数/异常响应的低风险覆盖建议，现有 Bean Validation 与 Python 一致性检查已实现，不为纯建议扩大本次 Java/Python输入。S5 将在最终暂存内容形成后以 Gitleaks 复扫闭环。

M1 已确认并修复后，Pi Attempt 2 返回 `PASS`，报告为 `docs/08-reviews/2026-10-04-review-main-review-r15-attempt-2.md`，无必须修改项。Attempt 2 S1 指出同一来源的多条 relation 若落在不同 Chunk，当前按来源三元组保存验证 Chunk 会保留其中一条；这是中风险检索完整性建议，不涉及越权、数据写入或不可运行，按“PASS 后纯建议不触发复审”规则记录而不继续扩大 R15。S2 为最多 40 次查询的性能优化，S3/S4 为覆盖建议，均不修改。S5 的状态与扫描闭环在本记录完成。

提交前最终 index 已包含两份 Pi 报告、finding 处置、状态收口和全部 R15 源码/测试；`git diff --cached --check` 退出 0，完整 staged diff 使用 `zricethezav/gitleaks:v8.30.1 detect --pipe --no-banner --redact --exit-code 1` 再次扫描，退出 0、`no leaks found`。该最终扫描后不再修改 R15 内容。

## 风险与回滚

错误的未变化判定可能造成索引不更新；错误的代际匹配可能泄漏另一快照的内容，因此响应关联和搜索代际继续失败关闭。回滚应用提交并移除新增 GIN 索引即可恢复旧查询方式；rag_chunk 仍是可重建派生数据，不改变 Wiki/Task 业务事实。

内部来源响应新增必填 `sourcesChanged`；旧 Agent 会忽略新字段且仍取得完整来源，新 Agent 不能与旧 Core 混用。单机发布按同版本重建；人工分阶段替换必须 Core first，已回填生产运维文档。项目来源发生变化的首个请求仍需一次完整授权快照，本次只消除稳定代际下的重复正文传输和每次全库 Chunk 加载；后台增量索引不在 R15 范围内。
