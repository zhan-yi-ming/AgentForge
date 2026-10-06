# R01–R18 累计深层修复审计

- 日期：2026-10-06
- 状态：Implemented
- 风险：L3 / Release Gate（18 个独立修复累计触达权限、审批/恢复、事务、历史、RAG、数据库角色、备份恢复、部署、Web 并发和 Agent Memory 状态机）
- 审计基线：`ff1a0a52317c69dba3d51e61e12e56dbf46f2bca`（R01 前）
- 审计目标：`cb46f932626f479c019b4fda4166412107a534db`（R18，已推送并经 `ls-remote` 核验）
- 分支：`codex/main-review-fixes`

## 背景与目标

R01–R18 已分别核实、修复、测试、Pi 审核、独立提交并推送。用户要求在 R18 完成后再扫描全部累计修改，确认前一问题的修复没有破坏另一能力，并重点寻找仍可复现的严重正确性、安全、数据一致性、契约或架构边界问题。历史单项测试和 Pi PASS 只用于定位，不能替代本次当前机器的累计证据。

本次审计固定检查 `ff1a0a5..cb46f93` 的 18 个提交、168 个文件、9,573 insertions / 475 deletions；审计记录和最终只读报告另计。若发现严重且可复现的问题，先在本记录写明证据与目标契约，再按 TDD 修复并重跑所有受影响输入；非阻断建议只记录，不扩大实现。

## 范围与交叉影响矩阵

| 累计范围 | 必须保持的相邻能力 | 重点失败模式 | 当前公共证据 |
| --- | --- | --- | --- |
| R01–R06 Action workflow | Java 权限/Approval/Task 写入、Python checkpoint、管理员代审批、重试/删除/恢复 | 错轮恢复、孤儿 WAITING、越权 actor、重复写入、删除后假成功、恢复泄漏 | Core service/API/Persistence + Python runtime + Java↔Python contract |
| R07–R08 Chat 收口与 task mode | 展示历史、流式 complete、只读 FORMAT/REWRITE/REVIEW | complete 先于持久历史、受限模式创建 Action、失败后半成功 | Core Chat API/service + Web/API + Python Chat/stream |
| R09/R12 Graph resolution | 配额、管理员角色、事务与并发 | advisor 绕过额度、数据库角色串扰、同项目并发脏状态 | Graph integration + Resolution cross-process + DB role gate |
| R10/R13 数据库角色与灾备 | Core/Agent 最小权限、迁移、PostgreSQL/Neo4j 备份恢复 | Python 获得业务写权、恢复 ACL 错误、手工图事实丢失 | clean verify + role boundary + backup/restore contract + Compose config |
| R11 Voice budget | Nginx/Core/Python ASR 大小与速率 | 网关提前拒绝合法音频、额度不一致或绕过 | Web voice + ASR Nginx contract + Python/Core API |
| R14–R16 RAG/Repository | 来源代际单调性、有界查询、同 commit 读取、citation | 旧请求回滚索引、全量正文搬运、混合 Git commit、来源泄漏 | PostgreSQL RAG + Retrieval + Repository cross-process |
| R17 Web async scope | 登录/项目/操作代际、Chat/历史/Action/Wiki/Task | A→B→A 或重登录后迟到响应污染新状态 | Web DOM/API tests + production build |
| R18 Conversation Memory | 同会话 revision、checkpoint 顺序、LRU/取消/实例边界 | 双提交、孤儿 WAITING、全局阻塞、claim 泄漏 | Memory/API tests + Java↔Python contract |

## 非目标

- 不重新改写、压缩、rebase 或回滚 R01–R18 已推送提交；不开始新的 R19/产品能力。
- 不因代码风格、未来扩展、低风险性能建议或无法复现的理论竞态修改生产实现。
- 不改变 Java 业务写入 / Python Agent、Core 授权、数据库角色或现有公共 HTTP schema，除非累计审计发现并证实严重缺陷且先更新本记录与目标文档。
- 不发布生产、不移动稳定标签、不 force push。

## 审计与验证计划

1. 以累计 base/target 运行 `plan-change-gates.ps1 -Milestone -ReleaseGate`，取得 L3 全仓门禁计划。
2. 逐域阅读生产 diff、迁移、脚本、直接调用方与新增/变更测试；使用 `git diff --check`、冲突标记/TODO/敏感输出扫描和契约字段对照建立静态信号。
3. 在当前工作树运行完整 `v3-release-regression.ps1`，要求 runner contract、V2 当前源码全量、13 项 Java↔Python 契约与 Repository 跨进程契约全部通过；记录 Java/Python/Web/数据库/Compose/smoke 的计数、warning、skip 与清理。
4. 对 `ff1a0a5..HEAD` 累计生产/测试/文档 diff 与本审计记录执行 Gitleaks；对完整累计差异做一次 `deepseek/deepseek-flash` Pi Milestone Review。若默认 Pi 长度会截断，按既有审计方式排除重复历史 change/review 报告并保留全部生产源码、配置、迁移、测试和权威文档，明确记录输入边界。
5. 逐条判断 finding；只修复可复现严重问题并在相关输入变化后重跑。最终回填结果、限制、未运行项、资源清理和远端核验，创建独立审计提交并非 force 推送。

## 当前事实

- 基线是当前 HEAD 的祖先；18 个提交顺序完整，R18 本地/远端均为 `cb46f932626f479c019b4fda4166412107a534db`，开始审计时工作树干净。
- 累计影响为 168 files、9,573 insertions / 475 deletions。热点集中在 Core Action/Chat/Conversation/RAG/Graph、Python Action runtime/RAG/Context、Web 工作区、Flyway V14–V18、Compose/备份恢复和验证 runner。
- `plan-change-gates.ps1 -BaseRef ff1a0a5... -TargetRef WORKTREE -Milestone -ReleaseGate -Json` 判定 L3，影响 Agent Service、Core API、Deployment、Docs、Release、TLS Deployment、Unknown 与 Web，要求全仓回归和 Milestone Review；计划指纹为 `ae5c8306ac3a0e8e27e0b404771406423498199f09641edc2e75f537a9ea89d6`。
- Codex 已逐域复核 Action/Chat/Conversation/RAG/Graph 的 Java 实现、Python Action runtime/RAG/Context/Repository、Web 异步状态、Flyway V14–V18、数据库角色、Compose 与备份恢复脚本；未发现可复现的严重正确性、安全、数据一致性、契约或架构边界问题。
- 当前机器 Release Regression 与额外角色/灾备/限流门禁已通过；累计 Gitleaks 无泄漏。Pi Attempt 1 返回 NEEDS_FIX，但唯一阻塞项经代码与基线证据核对不成立，其余均为 Low 建议；未发现经复现确认的严重问题。

## 验证与 finding 回填

### 当前机器完整回归

- `scripts/validation/v3-release-regression.ps1`：退出码 0，`V3 Release Regression PASS: 4 stage(s) completed`；runner contract、V2 当前源码全量、Java↔Python 契约与 Repository 跨进程契约全部通过。
- Core API `clean verify`：242 tests，0 failures，0 errors，12 skipped；在新 PostgreSQL 上从 V1 到 V18 完整迁移成功。skip 是条件式跨进程测试，由随后真实跨进程阶段覆盖；JDK 仅报告 Mockito/Byte Buddy 动态 agent 的未来兼容 warning。
- Agent Service：233 passed，5 warnings；warning 为 anyio BlockingPortal 弃用、Starlette `HTTP_422_UNPROCESSABLE_ENTITY` 弃用和 Pydantic `ReadOnly` 注解提示。
- Web：6 个 test files、86 passed；production build 成功、295 modules transformed。npm 仅报告未知用户配置 `home`。
- Java↔Python 真实跨进程：13/13；Repository citation 真实 JSON/stream 契约：1/1。
- RAG、Tool HITL、restart/resume、evaluation 与完整 Compose stack smoke 全部通过；评测得到 faithfulness `0.833333`、hitRate/recall/mrr `0.75`、taskSuccess/toolSelection `1.0`。
- `scripts/validation/database-role-boundary.ps1`：PASS；Core 业务 DML、Agent RAG/checkpoint 权限允许，Agent 业务写入与 public DDL 拒绝。
- `scripts/validation/backup-restore-contract.ps1`：`Backup/restore CLI contract: PASS`。
- `scripts/validation/asr-nginx-rate-limit.ps1`：nginx 配置检查成功；同 IP 两个 ASR session 与普通 API 持续 60 秒通过，generic API burst 被拒绝且 ASR 仍可用。

### 静态复核与清理

- `git diff --check ff1a0a5...HEAD`：PASS；未发现冲突标记、严重 TODO、凭据输出或相邻公共 schema 漂移。
- Action 状态恢复仍由 checkpoint owner 与 Java 确定性写入控制；RAG snapshot 代际在事务/锁边界内单调；Repository 单请求固定 commit；Web 迟到响应由 login/project/operation generation 隔离；数据库角色与恢复 ACL 均 fail closed。上述交叉边界没有发现可复现的严重缺陷。
- V3 runner 的临时 PostgreSQL、Neo4j、网络和卷均已清理；临时 `.venv` Junction 已删除且原始 venv 仍存在；本次精确构建的临时镜像不存在；Web `dist` 与两个 `tsbuildinfo` 已安全删除。未删除或重启用户原有容器、网络、卷或镜像。

### 敏感扫描与 Pi Milestone Review

- 累计 ff1a0a5...WORKTREE diff 连同本记录共约 932.72 KB；zricethezav/gitleaks:v8.30.1 detect --pipe --no-banner --redact --exit-code 1 退出 0，no leaks found。
- Pi 使用精确模型 deepseek/deepseek-flash。为避免默认 180,000 字符截断，临时 index 将历史重复的 docs/07-changes 与 docs/08-reviews 恢复到基线后，仅加入本记录和变更索引；所有生产源码、配置、迁移、测试及权威目标文档完整保留。实际输入为 132 files、777,270 characters / 867,704 bytes；临时输入上限随后还原，临时 index 已删除。
- Attempt 1 报告：docs/08-reviews/2026-10-06-review-main-review-r01-r18-cumulative-attempt-1.md，原始结论 NEEDS_FIX。
- R-01（Medium/必须修改）拒绝：Pi 声称没有全局乐观锁映射，但 ApiExceptionHandler 在 R01 基线前已经把 OptimisticLockingFailureException 统一映射为 409，基线前 ResourceApiTest 也通过公共 MVC seam 验证同一行为；本次 Java 全量再次通过。让异常离开 TaskService.update 是为了让 Agent 事务先完整回滚，不会导致公共 API 500。
- R-02 拒绝：公共 X-Request-Id 由 RequestIdFilter 限制为 1–100 安全字符，101–128 字符会被替换为 UUID；Pi 混淆了独立内部 RagSourcesRequest.requestId 的 128 字符 body 上限，不存在所述计费后持久化失败路径。
- R-03、R-04、R-05 接受为 Low 建议并记录：手工 Core 先行升级窗口不保证旧 Agent 写提案、tool_planner.py 顶级函数空行格式、Action 决策期间新建会话的窄 UI 恢复弹层竞态。它们不构成当前同版本发布的安全/数据一致性/契约阻塞，按规则不修改生产实现、不触发 Attempt 2。
- Codex 最终结论：R01–R18 累计修改未发现经复现确认的严重问题。

### 限制与未运行项

- 未部署生产、未访问公网生产域名、未执行真实生产备份覆盖恢复；本次验证使用当前机器的新临时数据库、测试容器与 CLI 合约，不替代上线窗口的备份和公网验收。
- 未调用真实付费模型供应商；评测、跨进程与完整 stack 使用项目可重复的测试配置。已有弃用 warning 与三个 Low 建议不会静默隐藏，均保留在本记录和 Pi 报告。

### Close Gate

- R18 已作为独立实现提交并经非 force 推送核验；本累计审计只提交审计记录、索引与 Pi 报告，不改写 R01–R18 历史。
- 最终暂存差异必须再次通过 diff check 与 Gitleaks 后创建独立审计提交；提交后非 force 推送，并用 git ls-remote 核验远端精确 commit。

## 风险与回滚

审计本身只新增证据文档；若需要修复，风险和回滚按实际模块单独记录。任何修复不得修改历史提交，只能在已核验 R18 之后追加可读提交。若完整门禁或 Pi 发现无法在本批安全收敛的严重问题，停止提交“PASS”结论并向用户报告阻塞。
