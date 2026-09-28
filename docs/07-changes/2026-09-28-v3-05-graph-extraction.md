# V3-05 Graph Extraction Pipeline

- 日期：2026-09-28
- 状态：Implemented（最终门禁与 Pi 复审收口中；提交/远端核验待完成）
- 阶段：V3-05
- Base：`1160428c72e6c2eeda559160bc47e1676f8dff05`，HTTPS fetch 的 `codex/v3-04-graph-domain-model` 与本地 HEAD 一致。
- 分支：`codex/v3-05-graph-extraction`，独立托管 worktree；主检出的已有文档修改、`.worktrees/` 和规划 docx 未触碰。
- Start Gate：用户已明确确认。公共测试 seams 为 Wiki/Task HTTP 与 Graph HTTP；Neo4j 物理清理使用真实 Bolt 端口补证。

## 背景、范围与非目标

V3-04 仅有手工图写入/查询。V3-05 从当前项目 Wiki/Task 中的显式声明行生成 Service/API/Issue 目标及 DESCRIBES/MODIFIES 关系；evidence 带来源类型/ID/version、UTF-16 原文区间、行号和固定 1.0 confidence。按来源范围稳定 ID，不在同名之间自动合并。Wiki/Task CRUD 同一 PostgreSQL 事务登记待办；后台按当前业务事实替换 Neo4j 自动投影，失败保留待办并重试。来源删除清除所有引用该来源的失效 evidence 和以该业务对象为端点的关系。重建与状态 API 仅对项目 owner/admin 开放。

未实现任意自然语言或 LLM 关系推断、V3-06 实体消歧/merge、V3-07 GraphRAG Retrieval/Answer；既有 `/wiki/graph` 未改。PostgreSQL/Neo4j 保持最终一致，图关闭时 Wiki/Task 业务写入照常进行。

## 文档与设计

先创建本记录，再建立 [功能契约](../03-features/graph-extraction.md)、[ADR-0033](../02-architecture/decisions/ADR-0033-durable-graph-source-sync.md)，更新数据/后端架构与 Core API 契约；实施后回填 README、文档索引和 [路线状态](../01-product/v2-v3-node-roadmap.md)。Flyway V12 建 `graph_source_sync`，回填现有 Wiki/Task；Java worker 对待办行使用 `FOR UPDATE SKIP LOCKED`，仅在图开启时处理，每轮最多 20 条，Neo4j 单次项目锁事务原子替换。成功后删除待办，失败保留并 30 秒退避，公开状态仅返回当前项目 pending/retrying 数量。生成实体来源作用域化；手工证据在来源更新时保留，来源删除后失效证据物理清理。设计不引入 Python 图写入或新的业务事实所有者。

## 实现与风险

实现位于 `services/core-api/src/main/java/com/agentforge/core/graph/`，并在 `WikiPageService`、`TaskService` 的业务事务内登记来源；新增 V12 migration、Graph API rebuild/status 和真实 PostgreSQL/Neo4j 集成测试。风险 L3，影响 Core API、Neo4j 派生数据及文档；规划器 `-BaseRef HEAD -TargetRef WORKTREE -Milestone -Json` 输出 L3、CoreApi/Docs、database-integration、java-clean-verify、docs-consistency、diff-check、gitleaks-final、Milestone Review。无 Python/Web/共享构建变更，未触发无关模块全仓回归。

## Codex 当前机器验证

下列命令由 Codex 在当前 worktree 实际运行。工作目录未特别说明时为 `services/core-api`；工具 Java Temurin 21.0.12.1 / Maven 3.9.11 / Testcontainers 1.21.4 / Neo4j Community 5.26 / PostgreSQL pgvector:pg17。

- 首切片：`.\mvnw.cmd '-Dtest=GraphApiIntegrationTest#wikiCreateExtractsSourceBackedRelation' test -q`，exit 1，1 failed/0 errors/0 skipped；Wiki HTTP 创建后 Graph HTTP 空列表。构造器编辑首轮同命令 exit 1（编译失败、未运行测试），修正四参 Spring 构造器后同命令 exit 0，1 passed。
- 重建权限切片：`.\mvnw.cmd '-Dtest=GraphApiIntegrationTest#ownerCanQueueRebuildButOtherUserCannot' test -q`，实现前 exit 1，403 预期实际 404；加入项目权限与 202 登记后同命令 exit 0，1 passed。
- 自动/手工图合并回归：`.\mvnw.cmd '-Dtest=GraphApiIntegrationTest' test -q` 首次运行 22 tests/3 failed，旧测试在自动新增 WIKI 源实体后两处精确数量断言过时，另 Wiki delete 使用 USER 而既有 Tool Policy 要求 ADMIN。改为独立 V3-05 测试类，V3-04 类禁用同步；删除用管理员角色。随后 `.\mvnw.cmd '-Dtest=GraphExtractionIntegrationTest' test -q` exit 0，4 passed。
- 同步状态切片：`.\mvnw.cmd '-Dtest=GraphExtractionIntegrationTest#ownerCanObserveSourceSyncCompletion' test -q`，实现前 exit 1（404），实现项目内 pending/retrying 统计后 exit 0，1 passed。
- 语法切片：`.\mvnw.cmd '-Dtest=GraphExtractionIntegrationTest#apiClaimsRequireExplicitMethodAndPath' test -q`，实现前 exit 1（无效 `API: nonsense` 仍入图），加 METHOD/path 校验后 exit 0，1 passed。
- 来源删除缺陷：`.\mvnw.cmd '-Dtest=GraphExtractionIntegrationTest#deletingWikiPhysicallyRemovesManualEvidenceFromThatSource' test -q`，exit 1，真实 Neo4j 查询仍有 1 条手工失效 evidence；第一次修复编译因 Map 泛型签名 exit 1（未运行测试），校正后同命令 exit 0，1 passed、Neo4j 残留 0。
- 旧并发压力信号：`.\mvnw.cmd '-Dtest=GraphExtractionIntegrationTest,GraphApiIntegrationTest' test -q`，24 tests/1 failed，旧并发清理测试在联合运行的图锁压力下收到文档允许的通用 503；定向 `.\mvnw.cmd '-Dtest=GraphApiIntegrationTest#concurrentCleanupNeverTurnsNeighborReadIntoServerError' test -q` exit 0，1 passed。未修改该断言；最终干净验证中旧图类全通过。
- 新生命周期定向：`.\mvnw.cmd '-Dtest=GraphExtractionIntegrationTest' test -q`，加入故障保留/恢复后 exit 0，相关测试通过；`.\mvnw.cmd '-Dtest=GraphExtractionIntegrationTest#refreshingOneSourcePreservesManualRelationWithIndependentEvidence' test -q` exit 0，1 passed；`.\mvnw.cmd '-Dtest=GraphExtractionIntegrationTest#taskUpdateAndDeleteReplaceExtractedClaims' test -q` exit 0，1 passed。
- Java 干净门禁：`.\mvnw.cmd clean verify -q` 先后三次 exit 0；第四次为最终源码与完整 12 项抽取测试，exit 0，179 tests /171 passed/0 failed/0 errors/8 skipped，GraphApiIntegrationTest 18 passed、GraphExtractionIntegrationTest 12 passed。8 项均为需显式开启的 `AgentServiceHttpContractIntegrationTest`，本节点未改 Python/跨服务协议。第三次后仅去掉三个 Java 文件 EOF 多余空行；按源码输入变化第四次完整 `clean verify` exit 0，测试计数如上。
- 文档检查：仓库根目录 `python .data/v305-check-links.py` 首次因临时目录未建立 exit 1（脚本未运行）；建立忽略目录后 exit 0，13 份 Markdown、38 个相对链接、0 broken。仓库根目录 `git diff --check` 首次 exit 1（三个 Java EOF 空行），修正后 exit 0；CRLF→LF 提示保留。
- Neo4j 故障用例故意产生 1 条 `Graph source sync deferred` warning，待办保持且重建后恢复。最终 Java 运行保留 Mockito 动态 agent/CDS warning 和测试容器关闭时 Hikari connection warning；未把 warning 隐藏或当成功证据。Testcontainers 容器由 Ryuk 清理；仓库根目录 `docker ps -a --filter label=org.testcontainers` 与 `docker volume ls --filter label=org.testcontainers` 均为 0。

## Pi、敏感扫描与收口

初次暂存差异经 Gitleaks Docker v8.30.1 扫描无泄漏；Pi Attempt 1 为 PASS、0 blocker、7 建议。修复后最终差异待复扫与 Pi Attempt 2。Pi 仅只读，不运行测试/修改/提交。限制：抽取只接受规定标签；无限重试的错误文档仍显示 retrying 且不发布部分投影；Graph API 返回当前验证快照，不承诺跨库原子一致。回滚可停止图同步并保留 PostgreSQL 待办，修复后重建当前来源。

## Pi PASS 后契约收口

Pi Attempt 1 为 PASS、0 blocker。Codex 将 A-4 的 excerpt >2000 认定为 V3-04 证据契约冲突，并补红绿回归；同时清理来源更新后旧版本手工 evidence 的物理残留，避免失效证据长期堆积。修订文档、实现、干净验证、扫描和复审后再 Close Gate。

## 2026-09-29 复核证据

- 旧版本手工 evidence 定向回归先红：2 tests / 1 failed，真实 Neo4j 仍有 1 条过期证据；清理后同两项定向测试日志无失败，超长 excerpt 触发受控 IllegalArgumentException 并保留待办。
- 第一次 `mvnw clean verify -q` 被宿主 PATH 中 U+202A 前缀的 msedgedriver 项阻断图测试初始化，150 tests / 3 errors；第二次仅过滤该 PATH 项后发现 Docker Desktop 隔夜关闭，150 tests / 3 errors。恢复 Docker Desktop 并在本次命令过滤异常 PATH 项后再次运行 `mvnw clean verify -q`，exit 0，31 份 Surefire XML 汇总 180 tests / 172 passed / 0 failed / 0 errors / 8 skipped。8 项仍为需显式开启的 AgentServiceHttpContractIntegrationTest；未修改 Python/跨服务契约。保留 Mockito agent、容器关闭时 Hikari 和故意模拟同步失败等 warning。Testcontainers 标签容器及卷均为 0。
- 门禁规划器重新确认 L3、CoreApi/Docs，要求数据库集成、Java clean verify、文档一致性、diff check、Gitleaks 和 Milestone Review。Pi Attempt 1 每项判断已回填审核报告；后续复扫与复审完成后补证。
## Pi Attempt 2 契约收口

Pi Attempt 2 为 PASS、0 blocker，B-1 揭示生成目标 externalId 在目标名 200 字符时可超过 V3-04 的 200 字符契约。Codex 判定为真实契约冲突，先补公共 Graph HTTP 红灯，再将来源作用域键改为稳定摘要；随后重跑 clean verify、敏感扫描与复审。B-2 的永久失败统一 retrying 语义已作为限制说明；B-3 测试建议不改变本节点行为，按风险避免重复覆盖同一路径。
- externalId 边界：`GraphExtractionIntegrationTest#maximalTargetNameKeepsExternalIdWithinGraphContract` 先红，真实 Graph HTTP 返回长度 258（契约 ≤200）；改为稳定摘要后定向同测试 exit 0。最终完整门禁、复扫与复审随后重跑。
- externalId 修复后的首轮完整 `mvnw clean verify -q`，181 tests / 1 failed：旧 `GraphApiIntegrationTest#concurrentCleanupNeverTurnsNeighborReadIntoServerError` 在第 8 轮锁竞争收到通用 503，属于 ADR 记录的 5 秒图事务超时语义。未放宽断言；定向同测试 `mvnw -Dtest=GraphApiIntegrationTest#concurrentCleanupNeverTurnsNeighborReadIntoServerError test -q` exit 0。再次 `mvnw clean verify -q` exit 0，31 份 Surefire XML 汇总 181 tests / 173 passed / 0 failed / 0 errors / 8 skipped；Testcontainers 标签容器与卷均为 0。调度线程在测试容器关闭时仍记录 Hikari 连接 warning/error，测试退出码与 Surefire 结果均为成功；保留该诊断信号。
## 最终 Review 与收口

Pi Attempt 3 对最终源码差异给出 PASS、0 blocker，确认 A-4 与 B-1 契约修复；C-1～C-6 均为非阻塞建议。Codex 在最终审核报告逐项回填判断：双向模块依赖补入架构文档；永久失败的 retrying、5 秒跨库持锁、项目级关系清理为已记录限制；其余测试/列名风格建议无当前缺陷信号，不扩大 Node。所有实现相关输入最后一次变化后 `mvnw clean verify -q` exit 0；最终暂存差异需再检查链接、空白及敏感信息，Close Gate 后提交。