# V3 全量跨功能影响审计

- 状态：Implemented
- 风险等级：L3（V3 累计差异、跨服务公共契约、权限/状态/恢复与 Release Gate）
- 审计基线：`61626ed`（V3-01 前）
- 审计目标：`7dd6868`（V3-09 Release Gate / `v3-stable`）
- 工作分支：`codex/v3-full-impact-audit`

## 背景

V3-01 至 V3-09 已分别实现并通过节点审核，`7dd6868` 已推送且 `v3-stable` 指向该提交。用户要求在节点完成后再以整个 V3 累计差异为单位进行一次 Pi 全量只读审计，特别检查前一项修复或共享组件修改是否破坏另一项能力。历史节点 PASS、旧构建产物和旧 Pi 报告仅作定位线索，不作为本次工作树证据。

## 目标与范围

- 审查 `61626ed..7dd6868` 的 146 个累计文件变化以及当前审计文档，不把审计虚构为 V3-10。
- 从公共 seam 检查 MCP、Model Gateway/Fallback、确定性模型路由、图领域模型/抽取/消歧、GraphRAG、Repository Context 与既有 V2 安全/审批/恢复/评测链路的交叉影响。
- 在当前机器重新执行 V3 Release Regression，必须覆盖 Java clean verify、Python 全量 pytest、Web 全量 test/build、V2 smoke/full-stack 以及 V3 条件式跨进程契约。
- 在测试、清理和敏感扫描完成后，用 `deepseek/deepseek-flash` 对累计 V3 差异执行一次 Milestone Review；仅修复可复现的严重缺陷、不可运行、安全/数据一致性、真实契约冲突、架构边界破坏或明显偏离目标。

## 非目标

- 不新增 Tool、模型策略、图能力、Repository 解析能力、API 或数据库迁移。
- 不改变 Java 业务执行 / Python Agent 的职责边界。
- 不移动或重写已经发布的 `v3-stable` 标签；若审计发现需要代码修复，先在本分支形成独立修复提交，再另行评估发布动作。
- 不纳入用户已有的 `docs/07-changes/2026-09-05-disable-pi-and-day1-day4-audit.md` 修改、`.worktrees/` 或规划 DOCX。

## 交叉影响矩阵

| 变化源 | 必须保持的相邻能力 | 重点失败模式 | 主要公共证据 |
| --- | --- | --- | --- |
| V3-01 MCP | RBAC、Risk、Approval、Idempotency、Audit | MCP 绕过 Java 策略、重复写入、来源混淆 | MCP HTTP + Tool/HITL smoke |
| V3-02/03 Gateway 与 Routing | Chat/stream、Fallback、usage/cost、显式 Tool | 路由改变错误语义、重试重复调用、fallback 泄漏凭据 | Python gateway/routing + Java→Python 契约 |
| V3-04/05 图模型与抽取 | Wiki/Task 生命周期、项目隔离、CAS、清理 | 来源更新恢复旧图、跨项目污染、清理竞态 5xx | PostgreSQL/Neo4j 集成测试 |
| V3-06 消歧 | 来源版本、人工确认、撤销、GraphRAG | 过期映射复活、错误合并、advisor 影响确定性决策 | Resolution 集成 + advisor 契约 |
| V3-07 GraphRAG | 文本 RAG、Context 预算、citation、降级 | 图故障拖垮文本检索、无证据引用、权限错误被吞 | GraphRAG Python + 跨进程 Chat |
| V3-08 Repository Context | 项目授权、统一预算、secret/路径过滤、同步/流式一致性 | 仓库资料挤占其他来源、工作树/密钥泄漏、伪造 citation | Repository 单元 + Java/Web 契约 |
| V3-09 Release 修复 | 上述全部能力与 runner 隔离 | Neo4j 重试改变延迟/错误、环境变量泄漏、阶段顺序依赖 | 4 阶段失败关闭 runner |

## 计划验证

1. 运行 `scripts/validation/plan-change-gates.ps1`，以累计 V3 base/target 和 Release Gate 取得门禁建议。
2. 统计多提交热点并核对直接调用方、条件式测试和完整 runner 的覆盖关系。
3. 在当前机器运行 `scripts/validation/v3-release-regression.ps1`，记录版本、退出码、数量、warning/skip 和资源清理。
4. 对仅包含累计 V3 与本次审计文档的暂存视图进行 diff/sensitive scan；用户无关工作树内容不得进入 Pi 输入或提交。
5. 执行 Pi V3 cumulative Milestone Review；逐条复核 finding，必要时按文档先行和 TDD 修复并重跑受影响门禁。
6. 回填本记录、审查报告与路线状态，完成 Close Gate 后创建独立提交并推送核验。

## 当前事实

- `v3-stable` 与远端 V3-09 分支均已核验为 `7dd6868`；路线图中旧的“待提交推送核验”描述在本次文档阶段纠正。
- 累计差异为 146 files、12,859 insertions、87 deletions；高频共享热点包括 Python 配置/LLM/schema、GraphService/Neo4jGraphStore、Chat API、Compose 与公共 API 文档。
- Pi finding 与最终结论待本次只读审核后回填。

## 当前机器验证证据

- 门禁规划：在仓库根目录执行 `.\scripts\validation\plan-change-gates.ps1 -BaseRef 61626ed -TargetRef 7dd6868 -Milestone -ReleaseGate -Json`，exit 0；判定 L3、Milestone、Release Gate，要求 full-repo regression、Java/Python/Web、跨进程、数据库、Compose/Nginx/TLS、文档、diff 与敏感扫描；fingerprint=`523fe77c1b28151292df8bc664b68b51eec90e37e5bd8a142518ba59ef33492c`。
- 完整回归：在仓库根目录执行 `.\scripts\validation\v3-release-regression.ps1`，exit 0，4/4 stages PASS。环境为 Eclipse Temurin 21.0.12.1、Python 3.14.3、pytest 8.4.2、Node v24.14.0、npm 11.9.0、Docker client/server 29.5.3。
- Java：`clean verify` 201 tests / 0 failures / 0 errors / 11 条件式 skips，BUILD SUCCESS。随后真实 Java→Python/Neo4j 契约显式执行 8 Chat/Resume + 3 Resolution advisor + 1 GraphRAG，Repository 契约 1 test，合计 13 / 0 failures / 0 errors / 0 skips。
- Python：199 passed / 0 failed / 5 warnings；warning 为 AnyIO alias、Starlette 422、Pydantic TypedDict 和 pytest cache ACL，不隐藏失败。
- Web：6 files / 73 tests passed；TypeScript/Vite production build 295 modules，exit 0；保留 npm unknown user config `home` warning。
- 跨功能 smoke：RAG Wiki/Task 各 1，更新版本 1，删除后 Task chunks 0，跨项目 rows 0；Tool/HITL 确认前 0、重复确认后 1、更新 version 1、reject=`REJECTED`、cross-user=403；Restart/Resume 为 `PENDING → EXECUTED`，replay 仍 `EXECUTED`、matching task 1、checkpoint 3；Evaluation hitRate/recall@K/MRR=0.75、faithfulness=0.833333、task success/tool selection=1.0；完整栈 Web 200、Core/Agent `UP`、RAG source 1、confirm executed、reject rejected、final tasks 2。
- 清理：runner 创建的容器、网络与卷均已清除；仅由本轮 `58de7541` 完整栈构建的 core-api、agent-service、web 三个镜像已按精确名称删除并复核无 `58de7541`/`afb6d51d` 残留。

## 跨功能人工核对

- 模型 Gateway/路由：同步与流式共用确定性 taskType；测试覆盖瞬时失败仅一次 fallback、认证失败不 fallback、首 token 后不切换、实际 provider/usage/cost、独立凭据和重复目的地去重。Java 旧 ANSWER 重载继续调用原契约，非 ANSWER 才显式发送 taskType。
- 图来源生命周期：Wiki/Task 写入仍先走既有 ToolRiskEngine；同一事务内标记可恢复图同步。集成测试覆盖来源更新/删除、重建、失败重试、人工 evidence 保留、跨项目、CAS 和并发清理。
- GraphRAG/Repository：两类资料与文本候选统一进入 `rag_context_char_budget`，最终引用仍经过完成答案编号筛选；测试覆盖图依赖仅在依赖故障时降级、图保留一个 context slot、Repository 只读 HEAD、排除工作树/secret、项目绑定唯一性以及同步/流式 citation。
- MCP/审批：MCP 写 Tool 仍只创建 Java PENDING Approval；测试覆盖同意图 retry 复用、不同意图拒绝、并发只建一个 approval、跨项目与 schema 拒绝，以及拒绝时不 resume、不写 Task。

## 已观察但未阻断的输出

- Java 全量套件在一个 Graph Testcontainers 上下文关闭后，后台 GraphSyncScheduler 曾记录一次访问已释放 PostgreSQL 的 connection-refused 堆栈；Surefire 仍为 201/0/0，随后独立的 V3 图契约使用全新 PostgreSQL/Neo4j 并 0 skip 通过。该现象当前只证明测试上下文关闭期的调度噪声，不能当作生产故障或吞错结论；已明确交由累计 Pi 审阅其生命周期风险。
- Mockito 动态 agent 将在未来 JDK 默认禁止；当前不改变 exit code。Graph/Repository 的外部依赖降级与 Context 上限仍沿用各功能文档中的已知限制。

## Pi 完整输入边界

- 原始 `61626ed..INDEX` 的统一 20 行上下文 diff 为 938,018 字符；默认 180,000 字符上限会只保留首/中/尾，不能满足本次全量审计。
- 历史 `docs/07-changes/` 与 `docs/08-reviews/` 报告不是运行实现，且包含各节点重复 diff。Pi 使用独立临时 Git index 将这些历史记录恢复到基线，只重新加入本次审计记录与变更索引；生产源码、配置、迁移、测试、V3 runner、架构/功能/API/开发/运维文档均保持完整。该视图约 66 万字符并再次执行敏感扫描。
- 仅为本次单次调用，把工作树中 `run-review.ps1` 的 diff 截断阈值临时提高到 750,000；该脚本不加入临时 index，也不进入 Pi diff，审核结束立即恢复为 180,000，不纳入提交。

## Pi Milestone Review 与处置

- 十秒预检确认 `deepseek/deepseek-flash` 存在且为 1M context；Attempt 1 对 117 文件、668,062 字符、未截断的累计输入返回 `PASS`，0 Must-Fix。报告：`../08-reviews/2026-10-02-review-v3-full-impact-audit-attempt-1.md`。
- S-1（GraphSyncScheduler 关闭期异常）不在本次改实现：Pi 明确判定为非阻断健壮性/日志建议；本机只有测试上下文释放后的噪声，没有生产失败、数据丢失或待办被删除证据。若生产观测复现，按 diagnosing-bugs 另立变更。
- S-2/S-3 为真实文档一致性缺口，同批补齐 ADR-0030/0031/0035/0036 索引并统一链接，修正 GraphRAG 与 Repository Context 的已实现状态；纯文档修正不触发复审。
- S-4（MCP `search_wiki` 无分页）与 S-5（GraphRAG 根扫描 N+1）接受为已知、有界的容量/性能限制，不在无失败证据时扩公共契约或 GraphStore API。
- Pi 的 N-1 至 N-5 与 Codex 证据一致：MCP 不绕过 Java、图来源/项目/CAS 边界成立、taskType/StrictVersion 契约一致、GraphRAG/Repository fail-closed 与只读边界成立、核心异常分支覆盖充分。

## 最终验证与 Close Gate

- Pi 后只修改文档；生产源码、配置、依赖、迁移、公共契约和测试没有变化，因此本任务内已通过的完整 V3 runner 证据继续有效，不重跑无关代码套件。
- 最终文档门禁：过期状态搜索 0 命中；ADR-0029 至 ADR-0036 各有且仅有一个目标文件；7 个本次 Markdown 文件在忽略 fenced code 后相对链接 0 broken；`git diff --check HEAD` exit 0；6 类私钥/API key/GitHub token/Bearer/AWS/JWT 模式均 0 命中。Gitleaks CLI 不可用，已如实记录为限制。
- 最终提交范围仅含路线状态、ADR/架构/GraphRAG 文档真实性修正、本审计记录、变更索引和 Pi PASS 报告；用户已有 9 月记录修改、`.worktrees/`、规划 DOCX 与不可访问的 pytest cache 均排除。
- V3 全量跨功能影响审计 Close Gate：**YES**。`61626ed..7dd6868` 的完整实现视图已由本机全量回归和未截断 Pi Milestone Review 共同覆盖，0 Must-Fix；未发现“修 A 破坏 B”的可复现严重缺陷。已知容量/性能限制和测试关闭期日志噪声不冒充已修复问题，也不阻断当前 V3 完成结论。
