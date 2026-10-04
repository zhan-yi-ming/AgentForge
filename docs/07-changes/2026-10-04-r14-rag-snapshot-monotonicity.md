# R14 RAG 来源快照单调同步

- 日期：2026-10-04
- 状态：完成
- 风险：L3（跨服务公共契约、派生索引 schema 与授权快照一致性）
- 范围：Core RAG 来源接口、Agent RAG 同步/检索、Flyway、Java↔Python 契约

## 问题核实

报告问题成立。Core 来源获取发生在 Python 的项目索引锁之前；`RagStore.synchronize` 只比较单个来源版本是否相等。旧请求在新请求之后取得写锁时会把 v2 回退成 v1，旧的缺失来源集合也会删除后来新建的来源。项目锁只串行写入，不定义快照先后。随后不带快照版本的搜索还可能读取另一请求刚写入、并非本次授权来源集合的 Chunk。

## 目标契约与公共 seam

`POST /internal/v1/rag/sources` 的成功响应新增非负 `snapshotVersion`。V17 用数据库触发器在 Wiki/Task 已提交增删改时推进项目来源代际；Core 在 PostgreSQL `REPEATABLE_READ` 事务中读取该代际与已授权 Wiki/Task，使每个全量来源集合有可比较的单调版本，且只读请求不会制造虚假新版本。

Agent 的 `RagStore.synchronize(projectId,snapshotVersion,sources,embedder)` 在项目事务锁内读取已应用代际：小于当前代际的旧快照不写 Chunk、不执行缺失删除；等于当前代际幂等跳过；更大的代际完成全部替换/删除后原子推进元数据。`search` 必须携带本次 `snapshotVersion` 并在可重复读事务内核对；索引已被并发推进时返回空文本候选，避免把另一授权快照的内容用于当前请求。后续请求重新获取来源即可恢复召回。

新 `rag_source_generation` 保存 Core 业务来源代际，只允许 Core 读取并由受控触发器推进；`rag_project_snapshot` 只保存项目与已应用代际，是可重建的 Python 派生索引元数据。Agent 角色仅获后者和既有 `rag_chunk` 的 DML，不获得业务表或来源代际表权限。

## TDD 与验证计划

1. Java API 测试先要求 `snapshotVersion`，服务测试要求授权后在同一 seam 返回来源快照。
2. 真实 pgvector 测试先复现 v2 后到达 v1、删除后旧快照复活/误删，以及旧请求搜索较新索引；旧实现应红灯。
3. 加入最小 API/迁移/存储实现并更新调用方与 Java↔Python 契约测试。
4. 运行迁移权限 smoke、Python 全量、Java clean verify、跨进程、门禁、diff check、Gitleaks 与 Pi Milestone Review，最后回填。

## 验证回填

- TDD 红灯：Python 真实 pgvector 测试先因旧 `synchronize` 不接受快照代际而失败；Java API/服务测试先因缺少 `RagSourceSnapshot` 和 `snapshot` seam 编译失败。提交前人工复核又发现事务 ID 代表读取事务而非来源提交版本；新增真实 PostgreSQL 测试在 `rag_source_generation` 不存在时红灯，随后改为由 Wiki/Task 触发器推进项目来源代际。
- 绿灯：RAG API、服务和来源代际定向测试 7 passed；真实 pgvector 乱序同步/搜索测试 1 passed；Agent 受影响检索与仓库上下文测试 11 passed。
- 全量：Agent Service `222 passed`，保留 4 条既有 Starlette/AnyIO/Pydantic warning；Core API `clean verify` 为 `240 tests, 0 failures, 0 errors, 12 skipped`。12 个条件跳过分别是 Agent HTTP 外部进程合约 9 项、Repository cross-process 1 项、Graph cross-process 1 项、在线 resolution advisor 1 项；它们要求 clean verify 未设置的外部进程环境，随后独立发布门禁严格执行 13/13 跨进程契约。Testcontainers 清理后的连接池关闭 warning 未隐藏，不影响测试结论。
- 权限与契约：数据库角色烟测证明 Core 能读取并由受控触发器推进 `rag_source_generation`，Agent 可写 `rag_chunk`/`rag_project_snapshot` 但不能读取业务来源代际或写业务表；真实 Java↔Python 发布门禁按脚本严格预期 13 个契约通过，临时 venv Junction、容器、网络和卷均清理。
- 门禁：生产 Compose config、备份/恢复 shell/CLI 合约、`git diff --check` 通过；最终 `plan-change-gates.ps1` 判定 L3/Milestone，fingerprint `a51460b68e085d0e59c05d48781a57343c48c2ac2645fde16504ef7a4334ca16`。
- Pi：Attempt 1 的唯一 High 属 R13 恢复 ACL，不涉及 R14；修复后 Attempt 2 为 PASS。V17 角色条件、完整灾备自动化与混合版本升级均为建议项；标准 V16→V17 迁移和单机 Compose 同版本重建契约已有验证，本批不因纯建议触发第三轮审核。
