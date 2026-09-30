# Graph Domain Model

- 状态：Implemented（V3-04 Graph Domain Model；GraphRAG Retrieval Planned）
- ADR：../02-architecture/decisions/ADR-0032-neo4j-derived-graph.md

## 模型与边界

PostgreSQL 保持事实唯一来源，Java graph application service 通过 ProjectAccess 校验 owner/admin；Python 没有图写入入口。Project、Wiki、Task 对应既有对象；Service/API/Issue 使用调用者明确的 externalId，不新增业务系统，不按名称合并。ID 是 projectId + type + externalId 的 UUID name hash，Project 使用 projectId 本身；名称不参与 ID。

关系白名单：CONTAINS Project→Service/API/Wiki/Task/Issue；EXPOSES Service→API；DESCRIBES Wiki→Service/API/Task/Issue；MODIFIES Task→Service/API/Wiki；AFFECTS Issue→Service/API/Task。端点必须属于路径项目并已存在。关系逻辑键为 project/type/from/to。

Project 的业务来源由 id/projectId 表达，source=null（全局 non_null JSON 配置下该字段省略）；其他类型 source 为当前项目 Wiki/Task ID、version。关系 evidence 必填 sourceType=WIKI/TASK、sourceId、sourceVersion、start/end（UTF-16 [start,end)）、excerpt，可选 chunkIndex（仅元数据，不声称已匹配 RAG chunk）及 confidence（有限 0..1）。Wiki 引用 content，Task 引用 description（null 视空串）；excerpt 必须逐字等于该位置原文、长度 1..2000。source 描述由服务端生成，不接受自由来源声明。

Evidence ID 为关系/sourceType/sourceId/version/chunk/start/end 的稳定 hash，多来源独立保存。expectedVersion=0 表示首次写入；新对象 version=1。相同 payload 重试返回原版本；改变 payload 要求 expectedVersion 等于当前版本后 +1；旧版本改变写入 409。实体同样 CAS；来源与证据位置不可通过更新 ID 改写。

## API 与失败语义

参见 ../04-api/core-api.md。图入口均需 Bearer 和项目 owner/admin 权限；不提供内部 Python 写入或任意 Cypher。一次只写一个实体/证据；实体列表 keyset after UUID 字符串、limit 1..100；一跳邻接 limit 1..100，最多每关系 20 个 evidence（稳定 ID 顺序），显式 hasMoreEvidence 标记；无递归遍历。查询事务 timeout 固定 5 秒，驱动连接/获取连接有界。

读 evidence 时重新查询 PostgreSQL 当前来源与版本，失效/删除来源不返回 excerpt，关系没有有效 evidence 时不返回。Wiki/Task 实体删除时不返回投影；名称返回当前业务名称。外部实体 source 失效则隐藏。Project 显示当前项目名。分页游标按扫描候选前进，可能返回空页，必须按 nextAfter 继续；无全局快照承诺；扫描后被并发清理的关系跳过，端点已删除可返回 404。

图写事务不自动重试；只读事务对 Neo4j 驱动异常最多重读三次，最终锁争用、事务 timeout 与依赖失败均 fail-closed 返回通用 503，调用者可按稳定 ID 幂等重试。图功能默认关闭，关闭或 Neo4j 不可用返回通用 503，不暴露连接串/驱动异常。初始化约束只在首次图请求执行，失败不阻止 Chat/Wiki/Task 启动。显式 DELETE graph?confirm=true 清理整个项目投影并保留 schema/project lock，用于清空派生图；V3-05 提供独立的来源待办、后台同步与手工重建入口，见 [Graph Extraction](graph-extraction.md)。业务更新与图事务不原子，图读取只能保证验证时的来源快照，随后并发修改仍可能发生。写入提交后如来源在响应前失效，返回 409 而非空成功响应；图投影可能已提交，后续读会重新验证并隐藏失效证据。

## 验收与非目标

实际 HTTP + PostgreSQL/Neo4j Testcontainers 覆盖授权、非法输入/方向、来源/项目/版本、幂等并发、多证据、CAS、有限查询和依赖隔离。V3-05 显式语法抽取与生命周期见 graph-extraction.md；V3-06 merge、V3-07 检索/回答仍未实现，也不改现有 /wiki/graph。
V3-05 已实现的自动来源生命周期见 [Graph Extraction Pipeline](graph-extraction.md)；V3-04 手工 Graph API 保留，自动投影以独立 origin 标识并只按其来源替换。

## 2026-09-29 严重缺陷修正：清理与并发读取

`DELETE graph?confirm=true` 开始时在 PostgreSQL 记录项目新 generation 并暂时暂停该项目来源同步；旧成员映射在清理开始时删除；旧规范实体与审计事件保留，但仅同 generation 的规范实体可作为有效映射。随后清理 Neo4j，完成后恢复新待办处理。清理前的待办不会重新填图；清理开始后的业务变更可在清理完成后按新来源事实同步。若 Neo4j 清理失败，旧合并仍已失效，客户端可重试清理。五分钟内的重叠清理返回 503；超过五分钟的陈旧 resetting 可由新清理接管，旧请求不能解除新 generation 的暂停。跨库不承诺原子快照。并发邻接读取遇到清理引发的临时图事务冲突时，有界重新读取；实体消失仍返回 404，依赖持续故障仍返回 503。
