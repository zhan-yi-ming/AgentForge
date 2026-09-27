# V3-04 Neo4j Graph Domain Model

- 状态：Implemented（2026-09-28 完成最终机器验收与 Pi Milestone Review；等待提交/推送核验）
- 用户于本会话明确确认 V3-04 Start Gate；沿用已确认开发计划和 Java API / Neo4j adapter 公共 seams。
- Git preflight：fetch origin 成功；前置修复 46856cd97f03c4dc050a35445de17d2829acd7b1 已推送 origin/codex/v3-model-review-fixes，ls-remote 核验一致。当前 codex/v3-04-graph-domain-model 从该提交创建。
- 保留用户已有 docs/07-changes/2026-09-05-disable-pi-and-day1-day4-audit.md 修改、.worktrees/ 和规划 docx；旧 .pytest-p304 权限 warning 未处理。

## Scope / Start Gate

只实现项目图实体、类型化关系、可验证 evidence 的写入/查询和显式项目清理入口。PostgreSQL 业务事实不变，Java 控制授权与证据校验，Neo4j 是可重建投影。无自动抽取、生命周期流水线、消歧、GraphRAG 回答或新业务 CRUD。

Start Gate = YES（用户已确认）。目标、实体/关系方向、风险、展示价值及下一 Node 边界见 docs/01-product/v3-04-development-plan.md。

## 文档与实现设计

先建立 graph-domain-model 功能/API 契约与 ADR-0032，更新数据/后端架构和运维说明，再写公开 HTTP 与真实 Neo4j adapter 测试。关系保存为唯一 GraphRelation 节点并连接 FROM/TO 与 Evidence；业务 type 为有限枚举，避免关系属性唯一约束在 Community 的限制。所有写入在单次 Neo4j 事务内，项目锁序列化 CAS，避免并发创建重复实体/证据。证据引用 Wiki content 或 Task description 的 UTF-16 [start,end) 原文，版本不符拒绝；读取失效 evidence 不输出摘录。

## 风险与门禁规划

L3，CoreApi / Deployment / Docs，Milestone Review。规划器 explicit paths fingerprint 69bb8a027f7ec97307675815e0b33bab4269ffca69bbebee855c2f9029cef18e；gates compose-config、diff-check、docs-consistency、gitleaks-final、java-clean-verify。修改共享 Compose 须执行完整 11-stage 回归，另补真实 Neo4j 验证及跨进程 smoke。测试由本机 Codex 执行，旧报告不充当证据。

## TDD 与验证

以下时间线记录实际 red/green、工具版本、退出码、数量、warning/skip 和清理，最终汇总见记录末尾。

## Pi / Close Gate

最终本机验证与扫描汇总见末尾；Pi Milestone Review 和 Close Gate 在机器验证后执行，未过审核前不提交。
首切片实际 red：core-api 工作目录 .\mvnw.cmd -Dtest=GraphApiIntegrationTest test -q，退出 1，1 failed/0 errors/0 skipped，owner PUT graph/entities 返回 404 而非 200。首次 Neo4j 镜像下载无持续进展，终止本次两 Java 测试进程，先用 PostgreSQL + HTTP 取得业务红灯，再恢复 Neo4j Testcontainers 绿灯。Mockito 动态 agent/CDS warning 如实保留。驱动依赖会触发 Boot 默认 Neo4j 自动健康检查，显式排除 Neo4jAutoConfiguration，由 graph adapter 独占懒驱动，保证非图健康隔离。

## 早期环境阻塞（已恢复，保留诊断证据）

首切片构建：core-api 工作目录 `.\mvnw.cmd -DskipTests package -q` 首次退出 1（Spring @Value 与 driver Value 重名），限定 driver Value 类型后同命令退出 0。此命令跳过测试，不作为 green 或最终 clean verify 证据。

Neo4j 5.26-community Testcontainers 首次下载已取得部分层但剩余层长时间无进展；终止本次测试进程后以 `docker pull neo4j:5.26-community` 单独诊断，同样停在剩余层。`Test-NetConnection registry-1.docker.io -Port 443 -InformationLevel Quiet` 和 production.cloudflare.docker.com 同命令都返回 False，并记录 IPv4/IPv6 TCP warning。不能把 TCP 故障推断为密钥或业务逻辑缺陷；需要恢复 Docker 下载网络/代理。已请求用户调整 TUN/全局或 Docker Desktop 代理。长时间无进展的本次 pull CLI 已终止，不自动换镜像或修改系统 DNS。

现有 agentforge-web/agent-service/core-api/postgres 四容器仍健康，临时 Testcontainers 容器已由 Ryuk 清理。已取得 1 个 HTTP 业务 red，尚无 Neo4j green、最终回归、敏感扫描、Pi 或 Close Gate。工作保存于当前分支，未暂存/提交 V3-04，未进入下一 Node。
环境恢复：直接 docker pull registry-1.docker.io/library/neo4j:5.26-community 退出 0，Downloaded newer image；digest 5eb12ad77fa46ab73e23df9ea1f43f5c0f2a79523435577648e046be042b9b93。增加同镜像本地短标签。首切片 GraphApiIntegrationTest 1 passed/0 failed/0 skipped。第二切片同命令实际 3 tests、2 failed，Service 409 != 200，跨项目来源 409 != 404，记录业务红灯。apply_patch 因 reparse point 拒绝，继续使用已说明 .NET UTF8 编辑。测试编辑首次重复字段编译失败已修正，不算红灯证据。

第二切片 green：core-api .\mvnw.cmd -Dtest=GraphApiIntegrationTest test -q，退出 0，3 passed/0 failed/0 skipped。关系切片 red：.\mvnw.cmd -Dtest=GraphApiIntegrationTest#relationRetainsIndependentEvidenceAndRetriesDoNotDuplicate test -q，退出 1，1 failed/0 errors/0 skipped，PUT relations 404 != 200。

关系 green：.\mvnw.cmd -Dtest=GraphApiIntegrationTest test -q 退出 0，4 passed。人工清理 red：.\mvnw.cmd -Dtest=GraphApiIntegrationTest#manualCleanupRequiresConfirmationAndLeavesBusinessSourcesIntact test -q 退出 1，1 failed（缺确认路径 404 != 400）。补充显式清理并使 schema DDL 同样使用 5 秒事务 timeout。

人工清理 green：core-api .\mvnw.cmd -Dtest=GraphApiIntegrationTest test -q 退出 0，5 passed。L3 契约回归同命令退出 0，12 passed（权限/原文/非法方向与跨项目/CAS/来源更新删除/分页/并发/全部合法方向含 Task evidence）。Compose prod 初次 profile service 放入 networks，quiet config 真实失败；纠正层级后双 --profile graph config --quiet 退出 0。补独立 adapter 关闭与故障隔离、20 evidence 有界及真实 socket JWT smoke。

图定向回归：core-api .\mvnw.cmd '-Dtest=GraphApiIntegrationTest,GraphDependencyIsolationTest' test -q 退出 0，15+2=17 passed，含真实 TCP/JWT、并发实体与关系、21 证据有界及图故障/关闭隔离。收口补校验整数 version 契约，避免 Jackson 小数截断到有效 CAS/source version。

整数契约 red：fractionalOrStringVersionsCannotBeCoercedToAnotherVersion 退出 1，1 failed（expectedVersion 0.5 被截断后返回 200 != 400）。局部严格 Long 反序列化后图定向回归退出 0，16+2=18 passed/0 failed/0 skipped；拒绝小数与数字字符串，不更改全局 Jackson。Mockito 动态 agent/CDS warning 保留。开始共享部署影响的完整 11-stage 回归。

完整回归中 Java clean verify 退出 0：165 tests，157 passed/0 failed/0 errors/8 skipped。8 项 AgentServiceHttpContractIntegrationTest 依赖显式运行开关，常规 clean verify 默认跳过；跨服务链路由后续真实进程 RAG/Tool/HITL/restart-resume 和完整 Compose 验收覆盖。Mockito agent/CDS、Git CRLF 转 LF 及既有 .pytest-p304 权限 warning 均未隐藏。

完整回归首次于 python-test 退出 1：181 passed/1 failed/6 warnings，本地 LiteLLM SDK 连接失败；定向单项同样失败。当前测试进程继承 HTTP_PROXY/HTTPS_PROXY 而无 NO_PROXY。仅设置子进程 NO_PROXY=127.0.0.1,localhost,::1 后同项退出 0，1 passed，确认本地请求被代理影响。不修改系统代理或应用实现；后续回归沿用该本地测试环境。pytest 缓存目录权限 warning 保留，后续使用任务专属缓存。已通过且源码/base 未变化的 Java clean verify 与前两 gate 复用本次证据。

后续回归使用 root .\\scripts\\validation\\v2-release-regression.ps1 -Only python-test,web-test,web-build,rag-cross-process,tool-hitl-cross-process,restart-resume-cross-process,evaluation,full-stack-acceptance；测试子进程 NO_PROXY 绕过 loopback，PYTEST_ADDOPTS=-p no:cacheprovider 避免既有不可写缓存（未删除用户目录）。目前 Python 182 passed/4 warnings，Web npm test -- --run 73 passed，npm run build 退出 0（295 modules）；npm Unknown user config home warning 保留。RAG 跨进程 PASS，wikiSources/taskSources 各 1，crossProjectRows=0，更新/删除同步通过；隔离容器网络卷已清理。

可选图部署 smoke：仅在测试子进程设置固定测试凭据，root docker compose --env-file .env.example -f infra/compose.yaml -p agentforge-v304-graph-smoke --profile graph up -d --wait neo4j 退出 0，Neo4j healthy，只有容器内部 7473/7474/7687 无主机发布；同项目 down -v --remove-orphans 退出 0，独立图卷/网络/容器均删除，用户四服务未改。local/prod --profile graph config --quiet 先前均退出 0。

Tool/HITL 与 restart-resume 跨进程均 PASS：确认前 0 Task、重复确认后仍 1、拒绝 REJECTED、跨用户 403；重启后 PENDING→EXECUTED、replay EXECUTED、MatchingTaskCount=1、CheckpointCount=3。各独立测试项目 down -v 清理成功。Evaluation 与基线一致：recallAtK/mrr/hitRate=0.75，faithfulness 词项支持代理=0.8333333333333334，toolSelectionAccuracy/taskSuccessRate=1.0；无在线准确率声明。
本机工具：Java Temurin 21.0.12.1 / Maven 3.9.11（wrapper 3.3.4）/ Boot 3.5.16；Neo4j driver 5.28.13、Community 5.26、Testcontainers 1.21.4；Python 3.14.3 / pytest 8.4.2；Node 24.14.0 / npm 11.9.0 / Vitest 3.2.7 / Vite 7.3.6；Docker 29.5.3 / Compose 5.1.4。图定向命令在 services/core-api 执行；完整回归 root 执行，各内层命令及工作目录由脚本明确指定。

完整栈首次构建退出 1：Maven Central netty-handler 4.1.135.Final 下载 Content-Length 587351 bytes，仅收到 3628 bytes，依赖下载被截断；其余图 driver/Neo4j 依赖下载完成。失败尚未运行验收，不计为 PASS；finally 已执行隔离项目清理。源码与 base 未变，只重跑 full-stack-acceptance。

完整栈重跑 root .\\scripts\\validation\\v2-release-regression.ps1 -Only full-stack-acceptance 退出 0：新源码 Core 容器实际构建成功，Web 200、Core/Agent UP、RAG 1 个来源、确认与拒绝正确、最终 2 Task；项目 1a4523c4 down -v 清理成功。汇总全部 11 个 stage 当前证据均 PASS（最初三 stage 与剩余八 stage，最后单项网络重跑），没有宣称首次失败的整次运行成功。新增功能 18 项含真实 Neo4j/socket/JWT 测试通过。
文档链接检查与 diff check 退出 0；公开 README 只声明领域图，不声明抽取/消歧/GraphRAG 已完成。风险 L3，Neo4j 默认关闭、没有生产部署；跨库只保证验证时快照。Milestone Review 尚待本次扫描后运行；Close Gate 暂不关闭。

提交前 Gitleaks v8.30.1 staged 扫描退出 1，4 个 generic-api-key 命中，均为两新图测试复用既有 Base64 JWT fixture 字符串（非真实凭据）。保留脱敏定位，不加 allowlist；改为 32 个零字节的低熵测试专用 Base64，占位值绝不用于运行配置。按测试输入变化重跑 Java clean verify；生产实现与跨服务配置未变，其余本次通过证据保持有效。

最终 Java fixture 复验：services/core-api .\\mvnw.cmd clean verify -q 退出 0，读取本次新建 Surefire XML：165 total /157 passed/0 failed/0 errors/8 skipped（显式 Agent Python HTTP 开关未开启，原因同前）；图 18 项全部 passed。Gitleaks v8.30.1 staged 复扫退出 0，无泄漏。脱敏报告 .data/v304-sensitive-scan 已验证目标范围后删除，独立本次测试镜像 5 个 tag 已清理；用户四容器 healthy，未残留本任务容器/卷/basetemp/evaluation。无真实厂商调用或生产部署。

Pi Attempt 1 NEEDS_FIX 事实判断：R1 不成立，ProjectService.requireAccess/getProject 均调用 private requireOwnerOrAdmin，当前无成员模型；只读访问权也就是 owner/admin，不引入新权限。澄清计划。R2 当前 driver javap 确認 NoSuchRecordException extends java.util.NoSuchElementException，未捕获路径成立；先通过真实 HTTP 并发 clear/neighbors stress 取得红灯，再改读取缺失行为。R3 无已复现死锁/重复写入缺陷，6 并发实测通过；瞬态/锁超时也允许通用 503，保留零重试有界策略，明确调用者可幂等重试。R4 Jackson getLongValue 有范围校验，无截断实现；R5 已文档化；R6 有界 N+1 是性能限制，当前不扩大接口；R7 成员不存在、现有外部 actor 403 用例有效，其余建议不当作阻塞。R8-R10 无需改。

R2 真实 red：core-api .\\mvnw.cmd '-Dtest=GraphApiIntegrationTest#concurrentCleanupNeverTurnsNeighborReadIntoServerError' test -q 退出 1，1 test/1 error/0 skipped；真实并发 DELETE 与邻接 GET 触发 ServletException: NoSuchRecordException（空结果 single），公共 HTTP seam 已复现。R4 同当前测试 XML classpath 的 Jackson JsonFactory 实际解析 9223372036854775808，getLongValue 抛 InputCoercionException，无截断，JShell 退出 0。

R2 green：core-api .\\mvnw.cmd '-Dtest=GraphApiIntegrationTest#concurrentCleanupNeverTurnsNeighborReadIntoServerError' test -q 退出 0，1 passed/0 failed/0 skipped；8 轮 × 30 关系，3 并发邻接读取与清理，读取仅 200/404、清理 204。adapter relation 返回 Optional，扫描后消失的关系跳过，候选游标仍向前；写事务持项目锁，缺失关系 fail-closed 404。实现变化重跑 Java clean verify + full-stack-acceptance；Python/Web/其他跨服务依赖、配置与 base 未变，本次证据复用。SSH github.com:22 与 ssh.github.com:443 只读查询 timeout；同仓库 HTTPS ls-remote 退出 0，新目标分支不存在；origin 不修改，最后尝试同仓库 HTTPS 推送。

Pi R2 最终门禁：root .\\scripts\\validation\\v2-release-regression.ps1 -Only java-clean-verify,full-stack-acceptance 首次 Java 阶段退出 0，随后完整栈因 docker.1ms.run 对 JDK/JRE/Node manifest HEAD 返回 EOF 而退出 1（未运行验收）。官方 registry-1.docker.io/library 镜像完整地址 pull 三个基础镜像各退出 0，digest 与 Dockerfile 构建所需一致；本地短标签只指向相同镜像。仅重跑 root .\\scripts\\validation\\v2-release-regression.ps1 -Only full-stack-acceptance 退出 0，Web 200、Core/Agent UP、RAG 来源 1、confirm/reject 与最终 Task 2；隔离项目 105d495e 的四容器、网络、数据卷 down -v 成功。此为本次修复后的最终源码证据，不把首次网络失败算成功。
services/core-api 新建 Surefire XML 166 total，158 passed/0 failed/0 errors/8 skipped；图 GraphApiIntegrationTest 17 + GraphDependencyIsolationTest 2 =19 passed。统计脚本首次对 Hashtable.Keys 迭代时修改值引发枚举错误，丢弃该次输出；改为固定 keys 数组后才读取正确 skip=8。旧 Mockito agent/CDS warning、Python 四个 deprecation/Pydantic warning、npm home warning 仍保留。

Pi Attempt 2 PASS，0 必修。收口 Codex 复核 S1 为真实文档一致性缺陷，更新 docs/README 索引。S2 是可证伪公共契约冲突：putRelation 在Neo4j提交后对当前源/端点再验证可能得到 null，框架会返回 200 空响应。虽跨库非原子已文档化，成功状态不应无关系响应；先在真实 HTTP API + 真实 Neo4j 边界通过可控 Wiki 并发更新取得红灯，再改为409，持久投影可由后续重新验证/清理。S3 零重试现有策略，S4建议缺口、S5有界N+1、S6可选未部署容器加固建议不构成当前真实阻塞。修复后重跑受影响 Java/完整栈并执行 Pi Attempt 3。

S2 真实红灯：services/core-api .\\mvnw.cmd '-Dtest=GraphApiIntegrationTest#sourceChangingAfterGraphCommitCannotReturnEmptySuccess' test -q 退出 1，1 failed/0 errors/0 skipped，PUT relation 期望409实际200空body。测试通过 MockitoSpyBean 的 GraphStore 公共端口在真实 Neo4j 提交后、应用响应前调用真实 WikiPageService.update，精确再现跨库来源版本竞争；未 mock Neo4j 存储。按已更新契约把失效可见性转 ConflictException。

S2 定向 green：services/core-api .\\mvnw.cmd '-Dtest=GraphApiIntegrationTest#sourceChangingAfterGraphCommitCannotReturnEmptySuccess' test -q 退出 0，1 passed/0 failed/0 skipped。GraphService.putRelation 在写入后重验 visible 为 null 时改返回409 ConflictException，成功则仍返回有证据的 relation。MockitoSpyBean 仅拦截公开 GraphStore seam 并调用真实 Neo4j，再用真实 WikiPageService 触发来源变化；不依赖时序碰运气。

最终源码本机门禁：root .\\scripts\\validation\\v2-release-regression.ps1 -Only java-clean-verify,full-stack-acceptance 退出 0，2 stages PASS。Java Maven clean verify Surefire 167 total /159 passed/0 failed/0 errors/8 skipped；GraphApiIntegrationTest 18 + GraphDependencyIsolationTest 2 =20 passed。完整隔离 Compose Web 200、Core/Agent UP、RAG 1 source、confirm/reject 与 Task 2 正常；项目 9dae58f2 的四容器、网络和卷均 down -v 清理。旧 Python 182 passed /4 warnings、Web 73 passed 和其余七项跨进程/评估阶段输入未变，本次已通过证据复用；全 11 stages 当前最终源码所需机器门禁已覆盖。

## 最终 Pi 与 Node Close Gate

Pi Attempt 1 NEEDS_FIX，R2 真实并发清理异常已通过公共 HTTP + Neo4j 红灯修复；R1 经 ProjectService 源码核对为误报，R3/R4 等建议有证据裁定。Attempt 2 PASS；Codex 仍以确定性 seam 复现并修复 S2 写后来源变化的 200 空 body，改为 409，同时修正文档索引。Attempt 3 PASS、0 阻塞；五项剩余建议（有界零重试、边界分支测试、N+1、可选生产容器加固、空密码启动期提示）记录为后续评估，不扩大本节点或冒充已解决。

最终范围：Project/Service/API/Wiki/Task/Issue 实体、五种类型关系、来源可验证 evidence、Java owner/admin 授权、确定性 ID/CAS、有限邻接与显式清理、可选 Neo4j 派生投影。V3-05 自动抽取/生命周期、V3-06 消歧、V3-07 GraphRAG 检索与回答均未实现；现有 Wiki 链接图未改动。

当前机器结果：风险 L3，影响 CoreApi/Deployment/Docs；门禁规划器 INDEX@HEAD，Milestone。Java 167 total/159 passed/0 failed/0 errors/8 skipped（显式 AgentServiceHttpContractIntegrationTest 开关未开启），图定向 20 passed；Python 182 passed/4 warnings；Web 73 passed，构建成功；planner 15 checks、双 Compose profile config、RAG、Tool/HITL、restart-resume、evaluation 与完整隔离栈验收各通过，计 11 stages。Neo4j profile 实际 healthy 后清理。初次 Python 本地代理、Maven 依赖下载和 Docker 镜像站 EOF 均保留失败记录，修正测试环境/官方镜像地址后对应阶段通过；没有把失败当通过。完整栈最后运行来自最终源码，Web 200、Core/Agent UP、确认/拒绝与跨服务业务链路成功。

警告与限制：Java Mockito 动态 agent/CDS、Python Starlette/AnyIO/Pydantic、npm home 配置 warning 保留；旧 .pytest-p304 权限 warning 属用户已有目录，未处理。图功能默认关闭，未部署到生产；PostgreSQL/Neo4j 无跨库原子性，写后来源竞争可返回 409 且投影可能已提交，读时重验证隐藏过期证据；有限 N+1 与图故障/锁争用 503 已文档化。无真实厂商模型调用、无 GraphRAG 质量指标声明。本次测试容器/网络/卷、独立镜像和临时报告已清理；用户原四容器健康，用户既有修改保持原样。

【Node Close Gate】
Node：V3-04 Neo4j Graph Domain Model。
Scope 完成情况：已完成，未越过下一节点边界。
本次实际修改：Java 图 API/服务/Neo4j adapter、可选部署、测试、ADR 与公开文档。
明确没有实现的下一节点能力：V3-05 自动抽取与来源生命周期、V3-06 消歧、V3-07 GraphRAG 检索/回答。
Tests：上述当前机器 11-stage 回归与 20 项图测试、最终 Java clean verify 均通过；8 项开关测试跳过已披露。
DeepSeek Review：Attempt 3 PASS，0 阻塞；Attempt 1/2 发现已逐项裁定并修复真实缺陷。
Review Issue 处理：R2 和 S2 红灯→最小修复→回归；R1 核源码排除，其余建议记录。
GitHub Maintenance：README updated；Architecture/ADR updated；Docs updated；Demo/Screenshot not required；Evaluation/Evidence 为上述机器日志摘要和审核报告。
公开描述真实性检查：PASS，Graph Domain Model Implemented，GraphRAG Planned。
推荐 Commit：feat(graph): add source-backed Neo4j domain model。
是否满足进入下一 Node：YES（本节点可提交；V3-05 仍需用户另行明确授权）。