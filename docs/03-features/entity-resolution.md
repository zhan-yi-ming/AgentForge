# Entity Resolution

- 状态：Implemented（V3-06；V3-07 GraphRAG Retrieval Implemented）
- 前置：[Graph Domain Model](graph-domain-model.md)、[Graph Extraction Pipeline](graph-extraction.md)
- ADR：[ADR-0034](../02-architecture/decisions/ADR-0034-reviewable-entity-resolution.md)

## 目标与对象

V3-05 为每份 Wiki/Task 中显式提到的 Service/API/Issue 创建来源作用域实体。名称相同不证明同一实体，不同名称也不证明不同实体。V3-06 对同一项目、同一类型且当前来源有效的 Service/API/Issue 建立规范实体映射。Project/Wiki/Task 不参与消歧；原始实体、关系和 evidence 均不改写。规范映射由 Java 按权限、来源有效性和版本检查后写入 PostgreSQL；Neo4j 仍是可重建的来源图。

## 候选与建议

项目 owner/admin 可对当前有效实体请求候选建议。Java 从本项目图中有界检索同类型实体，并验证候选当前来源；不得返回跨项目实体或失效证据。规则阶段使用大小写、Unicode 空白及标点规范化后的名称/已确认 alias 做精确与 token 相似度比较。Python Agent 可对 Java 给出的有界候选计算 embedding 相似度并调用配置的 LLM 做消歧建议；只回传候选 ID、有限 0..1 confidence 和简短理由，无法判断时明确 abstain。LLM 不生成新实体 ID，不写数据库，失败时建议降级为“需要人工审阅”。规则/embedding/LLM 分数只能排序和提示，不能自动确认或 merge；低置信度标为 reviewRequired，实际所有合并均需人工确认。

## 规范映射与人工确认

规范实体记录 `canonicalName`、类型、受限 metadata、来源与 CAS version；成员映射记录 aliases、来源图实体 ID、规范 ID、确认人、确认时来源版本、confidence、状态和时间。一个成员在同项目最多有一个当前映射，规范 ID 只能指向同项目同类型的当前有效实体。aliases 关联到确认的成员和来源，不可因同名自动扩散到其他项目或类型。metadata 不得携带原文正文、凭据或任意模型推断事实。Java 对人工确认重新验证两个实体、来源版本、项目权限、预期版本和所选目标；保存审计事件。重复的相同请求幂等返回当前结果，冲突返回 409。

取消错误合并时，Java 通过 CAS 撤销该成员的当前映射并记录撤销事件；原始图节点/关系/evidence 从未重接线，因此可立即恢复未合并视图。重新确认不同规范 ID 用新的人工决策，不复用旧建议。false split 可将两个有效成员确认到同一规范 ID；false merge 可撤销并重新指向另一个规范 ID。删除或更新来源后，读取映射与候选时重新验证来源；失效成员/alias 隐藏，不能继续作为确认目标。锚点来源同 ID 更新后，旧成员确认不得自动恢复；人工使用新来源版本重新确认一个成员时，以 CAS 刷新规范锚点来源版本并追加审计，其他成员需逐一重新确认。已作为规范锚点的实体不得再被映射为另一规范实体成员，规范锚点本身也不做自映射。建议与确认之间的并发来源变化须拒绝，而非提交过期结果。

## 公共接口与权限

路径均在 `/api/v1/projects/{projectId}/graph/resolution` 下，Bearer 且要求项目 owner/admin。`GET/PUT /canonicals/{canonicalId}` 读取/修改当前有效规范实体的 canonicalName 和受限 metadata；PUT 使用来源版本与规范 CAS，变更追加审计。`POST /suggestions` 接收 `{entityId}`，返回当前候选、评分、可选推荐 ID、confidence 与 reviewRequired；不持久化、不执行合并。`PUT /decisions/{entityId}` 接收 `{canonicalEntityId,canonicalName,aliases,metadata,confidence,sourceVersion,canonicalSourceVersion,expectedVersion}`，由人选择并确认，返回规范映射；`DELETE /decisions/{entityId}?expectedVersion=N` 撤销当前映射并返回 204；`GET /decisions/{entityId}` 查看当前映射或未映射状态。所有输入有大小、类型、项目与来源约束；Graph 关闭/Neo4j 不可用时图相关入口返回通用 503，模型建议不可用不得阻断已持有有效人工决策的读取。

## 验收与限制

通过真实 Graph HTTP 与 PostgreSQL/Neo4j 验证项目隔离、同名异物不自动合并、不同名同物可人工关联、alias 污染隔离、来源更新/删除后失效、CAS 冲突、重复确认和撤销恢复；Python 端验证内部鉴权、候选白名单、低置信度 abstain、模型故障降级与输出边界，并做 Java↔Python 契约 smoke。启用模型的真实 HTTP 路径必须消费 FastAPI 注入的同一 responder，不能绕过 dependency override 或另建不受统一生命周期管理的 provider。锚点来源更新后必须由人工逐一重新确认成员；来源已失效的成员读取会隐藏，撤销入口因找不到当前图实体返回 404，历史行保留用于审计。候选扫描最多 500 个图节点，向 Python 送最多 20 个候选，当前 Hash embedding 可能漏掉弱相似实体。当前节点不改 `/wiki/graph`，不在 GraphRAG 回答中消费规范映射；V3-07 才建立混合检索与引用链。
