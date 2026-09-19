# Pi 代码审查报告：pre-v3-history-lifecycle / Attempt 1

- 日期：2026-09-18
- 审查阶段：pre-v3-history-lifecycle
- 审查对象：INDEX@c04f3fc（基线：c04f3fc553030ac9b5218f0a7aa50d95220ef4bb）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: PASS

# AgentForge 独立代码审查报告

- 审查阶段：pre-v3-history-lifecycle（P3-02 历史生命周期与消息）
- 审查模式：Milestone Review（第 1 / 3 轮）
- 审查目标：`c04f3fc553030ac9b5218f0a7aa50d95220ef4bb .. c04f3fc`
- 审查性质：完全只读；本轮未执行命令、未修改文件、未改变 Git 状态。
- 证据来源：本次 Git Diff、启动器提供的显式上下文、`docs/` 中的 ADR/API/功能文档、变更记录中的测试证据（仅作为证据，未改写为 Pi 执行结果）。

## 一、概述与总体结论

结论：**通过（PASS）**。未发现具备明确证据的“必须修改”问题。

本轮改动实现了 P3-02 的目标范围：

1. **单会话删除**：新增 `DELETE /api/v1/projects/{projectId}/agent/conversations/{conversationId}`，`ConversationHistoryService.delete` 在同一事务内锁定会话行、校验 project/user 作用域、检查未决 Action、物理删除 `agent_message` 并写入 `deleted_at` tombstone。
2. **tombstone 不可复用与不可继续写入**：`findByScope`/`findAllByScope` 增加 `DeletedAtIsNull` 过滤；`appendCompletedExchange`、`requireWritable`、`createPending` 均拒绝已删除会话；旧 `conversationId` 继续 Chat 在调用 Agent Service 与消耗配额之前即被拒绝。
3. **并发串行化**：删除与待审批 Action 创建都先对 `agent_conversation` 主行取 `PESSIMISTIC_WRITE` 锁，锁顺序一致，无死锁环路。
4. **前端**：历史抽屉逐条删除按钮 + 单次确认对话框，删除当前会话后进入空白 `/chat`；新消息不再折叠旧 AI 回复。

从 Milestone 视角看，该阶段与 `docs/01-product/v2-v3-node-roadmap.md` 中 P3-02 的节点定义（删除与关联状态安全处置、旧回复可见、流失败不影响其他轮次）方向一致；ADR-0025 已把“tombstone、终态 Action/审计保留、Python checkpoint 不清理”的边界写清楚，没有越界到 V3 组件（未引入 Neo4j/GraphRAG、Langfuse、LiteLLM、MCP）。模块边界仍在 Java Core 的会话/审批职责之内。

未发现阻断性问题。以下 9 项均为建议修改或无需修改。

## 二、详细发现清单

| ID | 严重级别 | 文件 | 行号/位置 | 核心问题 |
|----|----------|------|-----------|----------|
| R-01 | 建议（中） | `services/core-api/src/main/java/com/agentforge/core/agent/application/AgentChatService.java` / `AgentChatServiceTest.java` | `prepareStream` 新增守卫；测试类 | 流式路径的 tombstone 守卫无自动化覆盖，Web 主路径正是 SSE 流 |
| R-02 | 建议（中） | `ConversationHistoryService.requireWritable` | 新增方法体内 `ForbiddenException` | 他人作用域会话在 Chat 路径返回 403、在 get/delete 返回 404，存在存在性探测不一致 |
| R-03 | 建议（中） | `ConversationHistoryService.appendCompletedExchange` / `AgentActionService.createPending` | tombstone 守卫 | 在途 Chat 与删除交错时，可能在 Agent 已应答、配额已消费后抛 409 并丢弃该轮回答 |
| R-04 | 建议（中） | `apps/web/src/App.tsx` | `deleteSelectedConversation` | 依赖点击时闭包的 `conversationId`/`route`，DELETE 在途时路由变化会被 `newChat()` 劫持 |
| R-05 | 建议（低） | `apps/web/src/App.tsx` | `deleteSelectedConversation` → `newChat()` | 首页（非 chat 模式）删除仍保留在 state 的会话会被强制切换到 `/chat` |
| R-06 | 建议（低） | `AgentActionService.java` | 6 参构造器 `this(..., null)` | `conversations == null` 时静默跳过 tombstone 守卫，是潜在的安全旁路 |
| R-07 | 建议（低） | `AgentActionService` / `ConversationHistoryService` | 包依赖 | `agent` ↔ `conversation` 双向包依赖，运行期无环但模糊模块边界 |
| R-08 | 建议（低） | `MessageRepositoryAdapter` | `deleteAllByConversationId` | 派生删除逐条加载再删除，长会话下不如 bulk delete |
| R-09 | 建议（低） | `docs/01-product/v2-v3-node-roadmap.md`、`docs/07-changes/2026-09-18-p3-02-conversation-lifecycle.md` | P3-02 条目 / 状态字段 | 路线图 P3-02 未标 `Implemented`，变更记录仍为 `In Progress`，与功能/API 文档已描述交付存在状态落差 |

## 三、逐个 Issue 展开

### R-01 流式路径的 tombstone 守卫缺少测试

- Severity：建议（中）
- File & Line：`services/core-api/src/main/java/com/agentforge/core/agent/application/AgentChatService.java`（`prepareStream` 新增 `requireWritable`）；`services/core-api/src/test/java/com/agentforge/core/agent/application/AgentChatServiceTest.java`
- Evidence：

```java
public AgentChatCommand prepareStream(...) {
    projectAccess.requireAccess(projectId, actor);
    if (conversationId != null && conversationHistory != null) {
        conversationHistory.requireWritable(projectId, conversationId, actor);
    }
    aiUsageQuota.consume(actor.userId());
    ...
}
```

- Description：新增的 `AgentChatServiceTest.deletedConversationIsRejectedBeforeForwardingToAgent` 只覆盖了同步 `chat(...)`；Web 实际使用的是 SSE 流式接口（`chatStream` → `prepareStream` + `stream`），该路径上的“删除后旧 ID 必须 409、不得消耗配额”没有被任何测试断言。`ConversationHistoryApiTest` 也只测 DELETE，未测“带已删除 conversationId 发 Chat 返回 409”。这是本轮安全相关的新增分支，属于关键路径测试缺口，但代码本身按检查即可判为正确（同一 service 方法），因此不构成必须修改。
- Suggested Fix：补一条 `prepareStream` 的 `ConflictException` 用例（并断言 `verify(quota, never()).consume(any())`），或在 `ConversationHistoryApiTest`/聊天 API 测试中断言 409。

### R-02 Chat 路径对他人作用域会话返回 403，与 get/delete 的 404 不一致

- Severity：建议（中）
- File & Line：`ConversationHistoryService.java` → `requireWritable`
- Evidence：

```java
conversations.findByIdForUpdate(conversationId).ifPresent(conversation -> {
    if (!conversation.belongsTo(projectId, actor.userId())) {
        throw new ForbiddenException("The conversation belongs to another scope.");
    }
    if (conversation.isDeleted()) {
        throw new ConflictException("The conversation history was deleted.");
    }
});
```

对比同一 Service 的 `get`/`delete`：

```java
if (!conversation.belongsTo(projectId, actor.userId()) || conversation.isDeleted()) {
    throw new ResourceNotFoundException("Conversation not found: " + conversationId);
}
```

- Description：`docs/03-features/conversation-history.md` 与 `docs/04-api/core-api.md` 明确要求“不匹配返回 404/403 且不泄露正文”“不属当前用户……为 404”。Chat 路径对不属于当前 project/user 的 conversationId 返回 403，构成跨作用域会话 ID 的存在性预言机（虽然 UUID 难以枚举，泄露面有限）。该改动本身是安全改进（此前会把外部 conversationId 转发给 Python），但状态码口径不统一。
- Suggested Fix：`requireWritable` 中作用域不匹配时统一抛 `ResourceNotFoundException`，仅 `isDeleted()` 抛 `ConflictException`，并在 API 文档注明。若有意保留 403，则应在 `docs/04-api/core-api.md` 显式记录 Chat 路径的 403 语义。

### R-03 在途 Chat 与删除交错时该轮回答被丢弃且配额已消费

- Severity：建议（中）
- File & Line：`AgentChatService.chat` / `ConversationHistoryService.appendCompletedExchange` / `AgentActionService.createPending`
- Evidence：

```java
// AgentChatService.chat
conversationHistory.requireWritable(projectId, conversationId, actor);   // 短事务，锁随即释放
aiUsageQuota.consume(actor.userId());
AgentChatResult result = agentServiceClient.chat(...);
... agentActionService.createPending(projectId, actor, result.conversationId(), ...) // 再次取行锁，tombstone 时 409
persist(command(...), finalized);                                        // append 时第三次取行锁
```

- Description：`requireWritable` 结束后锁释放，删除事务可以在 Agent 调用期间完成。此后 `createPending`/`appendCompletedExchange` 会因 tombstone 抛 `ConflictException`，导致：Agent 已产生回答、配额已消费、Python 侧可能已记录 exchange，但 Java 返回 409 且本轮回答不落历史。变更记录只写了“在途流与删除交错仍由持久化及 Action 创建时的锁和 tombstone 守卫约束”，未说明调用方会看到 409 且结果被丢弃。属可接受的取舍，但应显式化。
- Suggested Fix：在变更记录/API 文档补充“在途请求遇 tombstone 返回 409 且不落历史”的语义；或在 `chat()` 中捕获 `ConflictException` 于 append 阶段并记录/返回稳定的用户可读错误，避免配额与用户感知完全脱节。

### R-04 前端删除依赖点击时闭包，路由在途变化会被 `newChat()` 劫持

- Severity：建议（中）
- File & Line：`apps/web/src/App.tsx` → `deleteSelectedConversation`
- Evidence：

```tsx
await api.deleteConversation(requestedProjectId, selectedId);
if (activeProjectId.current !== requestedProjectId || sessionStorage.getItem(TOKEN_KEY) !== requestedToken) return;
setConversationSummaries((current) => current.filter((item) => item.conversationId !== selectedId));
setConversationToDelete(undefined);
if (conversationId === selectedId || route.conversationId === selectedId ||
    loadingConversationId.current === selectedId) newChat();
```

- Description：`conversationId`、`route` 是点击渲染闭包中的值，仅对 project 与 token 做了新鲜度校验，未校验路由/会话是否仍是同一目标。若请求在途时用户用浏览器前进/后退改变了 `/chat/:id`（确认遮罩不拦截浏览器导航），响应对旧值判定为“当前会话”，进而 `newChat()` 把用户当前正在看的另一会话替换为空白 `/chat`。需要并发导航才能触发，影响为 UI 状态错乱，不涉及数据。
- Suggested Fix：在响应回来时重新读取 `parseRoute(window.location.pathname)` 与最新会话状态再决定是否 `newChat()`；或引入与 `activeConversationLoad` 类似的 generation ref 一同校验。

### R-05 首页删除当前会话会强制进入 chat 模式

- Severity：建议（低）
- File & Line：`apps/web/src/App.tsx` → `deleteSelectedConversation` → `newChat()`
- Evidence：`newChat()` 内包含 `setChatMode(true); chatModeRef.current = true; navigate("/chat");`
- Description：非 chat 模式的悬浮导航同样提供“历史”入口。若首页仍保留着上一次会话的 `conversationId`（离开 chat 模式时未清空），在首页删除该条历史会命中 `conversationId === selectedId`，导致界面被强制切到 `/chat` 空白聊天页。与“删除后不可见”目标不冲突，但体验突兀。
- Suggested Fix：仅在 `chatMode` 为真时执行“进入空白会话”的逻辑；首页场景只更新 `conversationSummaries` 并清空相关内存态。

### R-06 `conversations == null` 静默跳过守卫

- Severity：建议（低）
- File & Line：`AgentActionService.java`
- Evidence：

```java
public AgentActionService(..., Clock clock) {
    this(actions, auditEvents, projectAccess, riskEngine, taskService, clock, null);
}

@Autowired
public AgentActionService(..., AgentConversationRepository conversations) { ... }

// createPending
if (conversations != null) {
    conversations.findByIdForUpdate(conversationId).ifPresent(...);
}
```

- Description：生产环境由 `@Autowired` 7 参构造器注入，守卫生效；但 6 参构造器把 `conversations` 设为 `null` 并静默跳过安全校验。未来若有任何组件或配置改走 6 参构造器，tombstone 守卫会无提示失效。作为安全相关分支，建议避免“为兼容测试而置 null”。
- Suggested Fix：收敛为单一构造器（测试直接传 mock repository），或将 `conversations` 声明为 `Objects.requireNonNull` 并在测试中注入空 `Optional` 语义的 mock。

### R-07 `agent` 与 `conversation` 包双向依赖

- Severity：建议（低）
- File & Line：`AgentActionService.java`（依赖 `AgentConversationRepository`）与 `ConversationHistoryService.java`（依赖 `AgentTaskActionRepository`）
- Evidence：两边新增 import 形成 `agent.application → conversation.domain` 与 `conversation.application → agent.domain`。
- Description：运行期没有 bean 循环（`AgentActionService` 与 `ConversationHistoryService` 互不注入彼此），Spring 启动无风险；但包级双向依赖会让模块边界逐渐模糊，后续 V3 拆分 MCP/Tool 层时增加迁移成本。
- Suggested Fix：可将“会话可写性/tombstone 判定”下沉为 conversation 模块对外暴露的查询/校验能力，或抽出共享的 `ConversationGuard` 端口给 agent 模块依赖，保持单向。

### R-08 `deleteAllByConversationId` 使用派生删除

- Severity：建议（低）
- File & Line：`ConversationRepositoryAdapter.java` / `MessageRepositoryAdapter`
- Evidence：

```java
public void deleteAllByConversationId(UUID conversationId) { repository.deleteAllByConversationId(conversationId); }
...
void deleteAllByConversationId(UUID conversationId);
```

- Description：Spring Data 派生删除会先 select 实体再逐个 delete，当前每条会话消息量很小，行为正确（在 `@Transactional` 内执行）。仅在未来历史很长时有性能影响。
- Suggested Fix：如担心消息量，可改为 `@Modifying @Query("delete from AgentMessage m where m.conversationId = :id") int deleteAllByConversationId(...)`。

### R-09 文档状态标记与交付状态不完全同步

- Severity：建议（低）
- File & Line：`docs/01-product/v2-v3-node-roadmap.md` P3-02 条目；`docs/07-changes/2026-09-18-p3-02-conversation-lifecycle.md` 首部 `- 状态：In Progress`
- Evidence：路线图中 P3-01 标注 `Implemented`，P3-02 条目未标注；变更记录仍为 `In Progress`，而 `docs/03-features/conversation-history.md`、`docs/04-api/core-api.md` 已按交付语义描述删除行为。
- Description：属于文档一致性，非实现问题；按项目流程，`In Progress → Implemented` 通常在阶段提交/推送后回填，本轮为 Milestone Review，稍后再改也可。
- Suggested Fix：阶段被接受并提交后，将变更记录状态改为 `Implemented`，并在路线图 P3-02 后补 `Implemented` 标记，使路线图、变更记录、功能/API 文档一致。

## 四、无需修改（已核验的正确点）

| 项 | 说明 |
|----|------|
| tombstone 主行保留 + `deleted_at` 过滤 | `findByScope`/`findAllByScope` 均带 `DeletedAtIsNull`，列表/详情不会泄露已删除会话；`get` 内二次 `isDeleted` 判断为冗余但无害 |
| 删除与待审批 Action 创建的串行化 | 两条路径都先对 `agent_conversation` 主行取 `PESSIMISTIC_WRITE`，锁顺序一致；PENDING/APPROVED 检查使用已提交状态，不存在“删除时看不到未提交 APPROVED”的漏洞（PENDING 已包含在检查集合内） |
| 配额与转发顺序 | `requireWritable` 位于 `aiUsageQuota.consume` 与 `agentServiceClient.chat/stream` 之前，符合文档“在调用 Agent Service 和消耗日配额之前返回 409” |
| 跨用户删除防护 | `delete` 先 `projectAccess.requireAccess`，再 `belongsTo` 校验 `projectId + userId`，越权返回 404；Service 层与真实 DB 集成测试都有覆盖 |
| 迁移与索引 | V9 新增 `deleted_at` 可空列与 `(project_id, requested_by_user_id, conversation_id) WHERE status IN ('PENDING','APPROVED')` 部分索引，与 `existsBy...StatusIn` 查询谓词匹配，可被使用 |
| 数据一致性 | 删消息 + 写 tombstone + 清空 preview/message_count 在同一事务；会话主行不删除，避免 ID 重用；终态 Action/审计保留，Python checkpoint 明确不清理 |
| API 契约 | DELETE 成功 204、匿名 401、未决审批 409、不存在/越权 404；Web typed client 使用 `Promise<void>`，与 204 无 body 匹配，测试已断言 method 与鉴权头 |
| 测试有效性（已覆盖部分） | Service 层覆盖删除成功、未决 Action 阻塞（且不删消息）、他人会话不可删、旧 ID 不可复用；集成测试用真实 PostgreSQL 验证消息清零、preview 清空、`deleted_at` 非空、复用被拒 |
| 旧 AI 回复可见性 | `setExpandedChatIds((current) => new Set(current).add(historyId))` 保持既有轮次展开，测试已同步为“First answer 仍可见 + 手动收起生效”，与 P3-02 目标一致 |

## 五、主开发（Codex）评估回填区

| Issue ID | Codex 判定（采纳/部分采纳/不采纳） | 处理说明 | 关联文件/提交 |
|----------|-----------------------------------|----------|----------------|
| R-01 | 采纳 | 补流式 `prepareStream` 对 tombstone 的前置拒绝测试；配额和 Agent 均未调用。 | Core 119 tests：111 executed，8 skipped，0 failed |
| R-02 | 采纳 | 跨 project/user 的 Chat 会话 ID 统一返回 404；测试先红后绿。 | Core `clean verify` 退出码 0 |
| R-03 | 不采纳本阶段修改 | 跨设备在途流可能已消耗配额；改变 SSE 完成/持久化顺序涉及独立契约与计费设计，已在功能及变更记录中明示限制。 | 文档已更新 |
| R-04 | 采纳 | 使用完成时当前 URL 判断是否重置聊天；A 删除未决时切 B 的回归先红后绿。 | Web 50/50 通过 |
| R-05 | 采纳 | 首页删除当前后台会话仅清内存，不跳到 `/chat`；增加 DOM 回归。 | Web 50/50 通过 |
| R-06 | 采纳 | 移除可传空 Repository 的构造器，生产和测试都经必需依赖检查。 | Core `clean verify` 退出码 0 |
| R-07 | 不采纳 | 当前是同一 Java 服务内的应用协作，无运行期循环；拆包超出 P3-02 范围。 | 构建与 Spring 上下文通过 |
| R-08 | 采纳 | 改为事务内 JPQL 批量删除消息，真实 PostgreSQL 集成测试通过。 | PersistenceIntegrationTest 8/8 |
| R-09 | 待提交收口 | 当前仍在审核提交前；最终通过后更新路线状态。 | 变更记录保持 In Progress |

## 六、审查边界声明

- 本轮为只读审查，未运行任何命令、未修改文件、未改变 Git 状态。
- 变更记录中给出的 Web/Core 测试结论仅作为启动器提供的证据引用，不代表 Pi 执行过这些测试。
- 本轮未收到需要重新设计架构或扩展到全项目的证据，审查范围严格限定在本次 Diff、涉及文件及显式必要的接口契约（DELETE 端点、会话作用域查询、Action 创建守卫）。
