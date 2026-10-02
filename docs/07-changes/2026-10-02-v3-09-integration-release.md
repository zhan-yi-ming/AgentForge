# V3-09 Integration / V3 Release Gate

- 日期：2026-10-02
- 状态：Implemented
- 阶段：V3-09
- 交付目标：`codex/v3-09-integration-release` / `origin`
- Base：`aceb35527a9ac614624a535c2d49938e22963f5e`
- Start Gate：用户已于 2026-10-02 确认节点 Scope 与公共测试 seam。

## 背景

V3-01 至 V3-08 已分别实现 MCP、LiteLLM Model Gateway、确定性任务路由、图领域模型、图抽取、实体消歧、GraphRAG 与可选 Repository Context，但验证证据按节点分散。现有 V2 Release Regression 会运行当前 Java、Python 与 Web 全量测试，却没有把 V3 条件式跨进程契约和 V3 能力覆盖声明作为失败关闭的独立 Release Gate。

## 目标

- 建立统一、可选择阶段、失败关闭的 V3 Release Regression 入口。
- 复用 V2 的 RAG、HITL、Risk、Approval、Audit、Retry/Resume、Trace、Evaluation 与完整栈回归。
- 显式验收 MCP、LiteLLM/Fallback、Multi-model Routing、图模型/抽取、Entity Resolution、GraphRAG 与 Repository Context。
- 显式执行默认测试中会条件跳过的真实 Java → Python Chat/Resume、Entity Resolution advisor 与 Repository citation 契约。
- 完成全仓回归、公开文档真实性检查、敏感扫描与 Pi V3 Release Milestone Review；全部通过后才允许关闭节点并考虑 `v3-stable` 标签。

## 非目标

- 不新增 Tool、模型路由策略、图能力、Evaluation 指标、业务 API 或数据库迁移。
- 不改变 Java 业务执行 / Python Agent 的确定性边界。
- 不把历史 Node 报告、旧构建产物或默认 skip 当作当前 Release 证据。
- 不进行生产部署，不自动创建或推送 `v3-stable` 标签。
- 不纳入用户已有的 `2026-09-05` 记录修改、`.worktrees/` 或规划 DOCX。

## 受影响文档

- `README.md`：把当前变更指向 V3-09，并在门禁完成后回填真实 PASS 状态。
- `docs/01-product/v2-v3-node-roadmap.md`：把 Current Node 更新为 V3-09，并在 Close Gate 前回填 Implemented。
- `docs/02-architecture/system-overview.md`：修正 V3-08 状态并说明 Release Gate 只验证、不改变信任边界。
- `docs/02-architecture/decisions/ADR-0032-neo4j-derived-graph.md`：记录发布回归发现的 Neo4j 瞬时事务有界重试修订。
- `docs/03-features/README.md`：更新 V3-08 状态并登记 V3-09 Release Gate。
- `docs/03-features/graph-domain-model.md`：把图事务从零重试修订为最多 2 秒的 Neo4j 瞬时事务重试，最终失败仍为 503。
- `docs/05-development/testing-strategy.md`：定义 V3-09 公共 seam、覆盖矩阵和失败关闭要求。
- `docs/07-changes/README.md`：加入本记录索引。

## 设计决定

- V3 runner 复用现有 V2 Release runner 作为 V1/V2 基线，不复制其跨服务编排；随后运行 V3 专项契约阶段。
- Java `clean verify`、Python 全量 pytest、Web test/build、Compose、V2 跨进程与完整栈必须来自本次 runner，不复用历史节点报告。
- V3 专项阶段必须显式打开条件式测试；若预期测试被 skip，阶段失败。
- Release runner 只编排公共测试入口，不重新实现生产断言或读取真实外部模型密钥。
- 补测试或修复时只在已确认公共 seam 上一次一个纵向切片执行 TDD red → green。
- Release Regression 若稳定复现既有公共契约失败，不以缩小 runner 覆盖规避；本节点已把 Graph 清理/邻接竞争的零重试 503 收敛为 Neo4j 瞬时事务最多 2 秒的有界重试，最终失败仍为通用 503。
- 每个可选择阶段必须可独立重放：子 runner 非零退出码显式失败，部分执行报告 `PASS_PARTIAL`，跨进程环境在成功或失败后恢复原值，loopback proxy 不依赖前一阶段泄漏；测试凭据均在运行时生成。

## 实现

- 新增 `scripts/validation/v3-release-regression.ps1`：按固定顺序执行 runner contract、完整 V2 Release Regression、Java→Python/GraphRAG 条件契约和 Repository Context 条件契约；支持 `-Plan`、`-Only` 与 `-Json`，未知阶段失败关闭。
- 新增 `scripts/validation/test-v3-release-regression.ps1`：固定 4 个阶段、8 类 V3 能力声明、L3/fail-closed、11 个 Java→Python 条件测试、1 个默认跳过的 GraphRAG 条件测试，以及非法阶段拒绝。
- Java/Python 专项阶段使用隔离 PostgreSQL、真实 Python Agent 进程与 Testcontainers Neo4j；显式验收 Chat/Resume、Entity Resolution advisor 和 `livePythonProcessConsumesGraphRetrievalContract`，Surefire 汇总必须精确为 12 tests、0 failure/error/skip。Repository Context 阶段精确为 1 test、0 skip。
- runner 为本地 loopback 跨进程阶段临时补充 `NO_PROXY=127.0.0.1,localhost,::1`，结束后恢复原值；Agent 进程、Compose 容器/网络/卷和临时日志在 `finally` 中清理。
- 发布回归稳定复现 `GraphApiIntegrationTest#concurrentCleanupNeverTurnsNeighborReadIntoServerError` 的 503。根因是 Neo4j driver 将可重试瞬时事务失败在 `maxTransactionRetryTime=0` 下直接映射为通用 503；`Neo4jGraphStore` 现启用最多 2 秒的驱动重试，每次事务 5 秒 timeout 与最终 503 保持不变。

## 验证结果

本节点固定为 L3 Release Gate。最终生产相关输入变化后执行 `./scripts/validation/v3-release-regression.ps1`，exit 0，4/4 stages PASS：

- 环境：Eclipse Temurin 21.0.12.1、Python 3.14.3、pytest 8.4.2、Node v24.14.0、npm 11.9.0、Docker client/server 29.5.3。
- Java `clean verify`：201 tests / 0 failures / 0 errors / 11 conditional skips，BUILD SUCCESS；随后条件阶段补齐 12 tests / 0 failures / 0 errors / 0 skips，Repository Context 1 test / 0 failures / 0 errors / 0 skips。
- Python：199 passed / 0 failed；保留 5 warnings（AnyIO alias、Starlette 422、Pydantic TypedDict、pytest cache ACL）。
- Web：6 files / 73 tests passed；TypeScript/Vite production build 295 modules，exit 0；npm 报告现有 unknown user config `home` warning。
- RAG smoke：Wiki/Task 来源各 1，更新版本 1，删除后 Task chunks 0，跨项目 rows 0；隔离资源清理成功。
- Tool/HITL smoke：确认前任务 0，重复确认后 1，update version 1，reject=`REJECTED`，cross-user=403。
- Restart/Resume smoke：`PENDING → EXECUTED`，replay 仍 `EXECUTED`，matching task 1，checkpoint 3。
- Evaluation：hitRate/recall@K/MRR=0.75，faithfulness=0.833333，task success/tool selection=1.0。
- Full-stack acceptance：Web 200、Core/Agent `UP`、RAG source 1、confirm executed、reject rejected、final tasks 2；容器/网络/卷清理成功。
- 定向 TDD：并发 Graph 测试在修复前 exit 1（1 failure，邻接 GET 503），2 秒有界重试后 exit 0（1 passed）；runner 条件数量契约先因字段缺失 exit 1，最小实现后 exit 0。
- Pi Attempt 1 为 `NEEDS_FIX`：唯一阻塞 M1 指出 Java/Python 阶段环境变量泄漏和 Repository 阶段顺序依赖。相同 sentinel 公共 seam 修复前以 `NO_PROXY`/`POSTGRES_DB` 未恢复 exit 1，修复后 PASS；Repository 单阶段在 sentinel proxy 下 PASS 且恢复原值。S1/S2/S3/S5/S6 均判定为真实 runner/文档契约问题并同步修复；S4 不成立，因为 `GraphProjectLock.revision` 全仓仅写不读，不参与 CAS/响应/409。
- Pi 修复后的完整 `./scripts/validation/v3-release-regression.ps1` 再次 exit 0，4/4 stages PASS；Java 201 tests（0 failure/error，11 条件 skip）、Python 199 passed、Web 73 passed/build 295 modules、全部 V2 smoke/full-stack 与 V3 条件阶段结果及 warnings 与前述证据一致。条件阶段使用运行时随机 token，12+1 tests 均 0 skip；完整摘要为 `PASS`，定向摘要为 `PASS_PARTIAL`。
- Pi Attempt 2 对阻塞修复后的 14 文件暂存差异给出 `PASS`、0 Must-Fix。4 条 Low 建议不触发复审：状态用词在本次 Node 关闭时统一为 Implemented；`-Plan -Only` 组合、重复 `-Only` 去重和环境清单数据驱动均无当前失败或 CI 消费证据，留待后续 runner 变更时评估。
- 最终 15 文件暂存差异的 Gitleaks CLI 不可用；本机模式扫描无私钥、Bearer、GitHub/OpenAI key 命中。6 个宽泛 assignment 候选均来自 runner 的运行时随机测试变量或 Pi 报告中的同段代码引用，不是真实凭据。最终 `git diff --check`、2 个 PowerShell parser 与 11 文件相对链接检查均通过。
- 诊断红灯：Python 全量最初 198 passed / 1 failed，定向加 loopback `NO_PROXY` 后 1 passed；Java/Python 阶段依次发现 Compose token 初始化、checkpoint schema、固定测试 token 与预期数量问题，均以同一专项门禁复验。GraphRAG 初次整类补跑 34 tests / 1 unrelated concurrency failure，收窄后稳定复现并修复，没有通过删断言或缩小全量门禁规避。
- warnings/限制：Mockito 动态 agent 将在未来 JDK 禁止；当前不影响退出码。Graph 瞬时事务重试耗尽与真实依赖故障仍统一为通用 503，跨 PostgreSQL/Neo4j 不承诺原子快照。

效率记录：最终完整 Release runner 一次约 8 分钟；本节点曾在 GraphRAG 条件契约遗漏修正前完成一次完整 runner，相关生产配置变化后未复用旧证据而完整重跑。Pi Attempt 1 的唯一阻塞修复后触发一次必要复审；Pi 前 fingerprint=`2a488f16ab4e5f88ac83b7aa23fb94bb31a17c11e2b213878aef7b9a0a11941b`，阻塞修复后 L3/Milestone fingerprint=`6f53066931e1d3a111f1147b51ab93b1812416231694aa131f650899e5de618c`。PowerShell parser、Markdown 相对链接和 `git diff --check` 在最终差异上重跑。

## 风险与回滚

主要风险是 runner 漏阶段或误报成功、条件式测试静默跳过、Docker/PostgreSQL/Neo4j/子进程未清理，以及公开声明超过当前证据。runner 必须失败关闭并使用隔离资源；本次创建的诊断目录和两轮全栈 6 个唯一镜像已删除，未发现 V2/V3 容器、网络或卷残留。回滚可删除独立 V3 runner/契约测试，并把 Neo4j 最大事务重试恢复为 0 后同步恢复 ADR/功能文档；不涉及生产 Schema 或业务数据。
