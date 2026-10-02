# V3-07 GraphRAG Hybrid Retrieval

- 日期：2026-09-30
- 状态：Implemented（2026-10-02；Milestone Review 与 Node Close Gate 待收口）
- Base：`ebafcfd6991436a8f561c9a31d2cd6da4afb7d26`；分支：`codex/v3-07-graphrag-hybrid-retrieval`

## 背景、目标与边界

V3-05 提供有来源的图关系，V3-06 提供可撤销的人工规范映射。现有 Hybrid RAG 只按文本相关性检索，难以稳定找出跨模块关系。本节点让 Agent 在当前项目内检索有证据的图路径，结合既有 Vector/BM25 排序，向模型交付有界上下文和 Wiki/Task 文档引用。

只读消费图。Java 保持项目权限、来源重验和图存储访问；Python 负责查询理解、候选融合、上下文与 LLM。无任意 Cypher、自动实体合并、Git 仓库上下文或 V3 Release Gate。不增加持久化表，若发现 Schema 必须变化，先重审边界。

## 文档与实现顺序

本记录先行；随后更新 GraphRAG 功能、Core/Agent 内部契约、架构和 ADR；最后按已确认公共 seam 做 Java 内部 GraphRAG HTTP、Python `RetrievalService.retrieve` 和真实跨进程契约的逐切片红绿测试。现有用户修改的 2026-09-05 记录、`.worktrees/` 与规划 DOCX 均不纳入。

## 计划验证与风险

实际路径重新运行 `scripts/validation/plan-change-gates.ps1 -Milestone -Json -Paths <本节点23个文件>`：L3，CoreApi + AgentService + Docs，Milestone Review；门禁含 `java-clean-verify`、`python-test`、`cross-process-smoke`、`database-integration`、`docs-consistency`、`diff-check`、`gitleaks-final`。变更路径指纹 `f54f75e648e6b7cb7e1b7ee18c748823e6ac9804ce8d938862486cdffcd97886`；目标文档后续仅作状态与证据回填。

## 实现与红绿证据

- Java 增加 `POST /internal/v1/graph/retrieval`：内部 token、真实用户与项目授权；最多 500 个候选、4 个根、两跳、每节点 12 个邻居、40 条关系/结果；重验实体、关系、来源和 V3-06 已确认映射，返回当前 Wiki/Task evidence。Java 先红：目标 HTTP 测试预期 200 实际 401（入口尚不存在）；最小实现后目标测试绿，并增加越权/过期证据、两跳路径和映射失效测试。
- Python 在来源同步后消费图 DTO，以当前授权来源版本及原文再次过滤，和 Vector/BM25 做三路 RRF；保证有有效图命中时可保留一个 Context 候选，同时沿用总字符预算、统一引用编号和既有 Chat 输出契约。先红：缺少 Graph schema；图候选被双路文本排名挤出时断言失败；相应最小实现后公共 `RetrievalService.retrieve` 3 个新测试通过。503/传输故障仅降级为文本，鉴权和关联错误失败关闭。
- 独立 Python 子进程访问运行中的真实 Java HTTP + PostgreSQL/Neo4j 测试容器，验证图检索返回的 evidence 来源；这是跨进程契约 smoke，不是完整 Chat/LLM 回答断言。

## 当前机器验证（2026-10-02）

- 环境：OpenJDK 21.0.12.1、Python 3.14.3、Docker Engine 29.5.3。在 `services/core-api` 执行 `.\mvnw.cmd clean verify -q`：退出 0；33 个 Surefire suite、200 tests、0 failures、0 errors、10 skipped。其 10 个 skipped 包含未打开 system property 的显式跨进程 smoke；另用目标命令打开该 property 验证，退出 0。
- `services/agent-service/.venv/Scripts/python.exe -m pytest -p no:cacheprovider --basetemp ../../.data/pytest-v307-20261002-final -q`：退出 0，190 passed，4 warnings（Starlette/anyio 弃用和 Pydantic TypedDict 提示）。使用新 basetemp，不沿用旧 pytest 缓存。
- Java clean verify 输出 26 条 warning 行，包含 Mockito 动态 agent 未来 JDK 兼容提示、预期故障注入的 AgentChatService 警告与测试容器关闭时 Hikari 连接失效提示；没有隐藏 warning。目标跨进程 smoke 于当前机器重跑退出 0。
- 曾遇到全套 Java 中 Chat SSE 测试一次间歇性失败；单测和类级重复通过，随后完整 clean verify 通过，未擅自修改无关 Chat 逻辑。环境重启后宿主 PATH 中无效 `msedgedriver` 段及 Docker Desktop 未运行曾导致 Testcontainers 初始化失败；仅过滤本次子进程 PATH 段并启动本机 Docker 后重跑通过，未更改项目配置。第一次 Python basetemp 父目录错误导致 5 个 setup errors，改用仓库 `.data` 新目录后完整通过。

## Milestone Review 与收口

- Pi 送审时暂存仅 23 个 V3-07 文件；审核报告和收口记录纳入后最终暂存 24 个文件，用户原有变更未暂存。`git diff --cached --check` 退出 0，代码块外 56 个受影响文档相对链接均有效。本机 `gitleaks` 不可用；对暂存 diff 执行私钥、云/API Token、密码、Bearer 等本地模式及敏感文件路径扫描，均 0 命中，Pi 入口也执行其输入扫描。未将真实凭据或完整测试日志送审；已核对路径后清理 `.data` 中 13 个本次临时日志及 pytest basetemp，其他用户文件未触动。
- 2026-10-02 DeepSeek Pi `deepseek/deepseek-flash` 对 INDEX 做一次只读 Milestone Review，结果 PASS、无必须修改项；报告见 [审核记录](../08-reviews/2026-10-02-review-v3-07-graphrag-hybrid-retrieval-attempt-1.md)。逐项判断记录在报告的 Codex 评估表。S-1 的 503 映射、S-4 的 Chunk ID 类型、S-7 的 Task description 原文均由当前代码证实；S-2 映射失效时 `decision` 返回 UNMAPPED，只有并发来源变化才隐藏；S-3 是低风险测试覆盖建议，非观察到的契约缺陷；S-5 标题空行已修；S-6 在 Review PASS 后状态一致。无源码/契约修改，无需重跑已通过的机器套件或 Pi。

## 限制与待完成门禁

- 最终暂存 diff、文档链接及敏感复查已完成；Node Close Gate 判定后提交并核验远端。
- 检索采用确定性名称/alias 匹配，短或弱相似查询可能漏召回；当前没有完整跨进程 Chat/LLM 断言，亦不宣称生成答案事实准确率。Neo4j 与 PostgreSQL 无跨库原子快照；证据在读取后并发变化时由后续 Java/Python 来源检查尽量隐藏，但不承诺原子时点。没有实现 V3-08 可选 Git Repository Context 或 V3-09 Release Gate。
