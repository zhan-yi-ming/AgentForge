# Pi 代码审查报告：pre-v3-history-lifecycle / Attempt 3

- 日期：2026-09-19
- 审查阶段：pre-v3-history-lifecycle
- 审查对象：INDEX@c04f3fc（基线：c04f3fc553030ac9b5218f0a7aa50d95220ef4bb）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: PASS

# AgentForge 独立代码审查报告

- 审查阶段：pre-v3-history-lifecycle（P3-02 历史生命周期与消息）
- 审查模式：Milestone Review（第 3 / 3 轮）
- 审查目标：`c04f3fc553030ac9b5218f0a7aa50d95220ef4bb .. INDEX@c04f3fc`
- 审查性质：完全只读；本轮未执行命令、未修改文件、未改变 Git 状态。
- 证据来源：本次 Git Diff、启动器提供的显式上下文（路线图、功能/API/数据架构/ADR-0025）、Attempt 1/2 审查报告，以及变更记录中的测试证据（仅作证据引用，不代表 Pi 执行过）。

## 一、概述与总体结论

结论：**通过（PASS）**。本轮未发现具备明确证据的“必须修改”问题。

本轮为第 3 轮 Milestone Review。与前两轮相比，本轮新增的主要是围绕已采纳建议的修复与回归：

1. **R2-01 已修复**：`AgentChatService` 移除了可传空 `ConversationHistoryService` 的 4 参构造器，改为单一构造器 + `Objects.requireNonNull(conversationHistory)`，并删除了 `chat`/`prepareStream`/`persist` 中 `conversationHistory != null` 的静默旁路；新增 `conversationHistoryDependencyCannotBeOmitted` 空依赖测试。
2. **R-02（状态码一致性）保持修复**：`requireWritable` 对跨作用域会话返回 404，仅 tombstone 返回 409。
3. **R-01/R-06/R-08 保持修复**：`prepareStream` 前置 tombstone 校验 + 配额/Agent 零交互测试；`AgentActionService`/`AgentChatService` 收敛为必需依赖；消息删除为 JPQL 批量删除并由真实 PostgreSQL 集成测试验证。
4. **R-04/R-05 保持修复**：删除完成后以当前 URL 判定路由，避免旧回调劫持；首页后台删除仅清内存不跳转 `/chat`。

从 Milestone 视角：交付与路线图 P3-02 目标（单会话删除、关联状态安全处置、旧 AI 回复保持可见、流失败不抹掉其他轮次）一致；ADR-0025 明确了 tombstone、终态 Action/审计保留、Python checkpoint 不清理的边界。并发串行化（delete / createPending / append 均以 `agent_conversation` 主行为串行点，锁序一致）自洽，无死锁环路。未越界到任何 V2/V3 组件（无 Neo4j/GraphRAG、Langfuse、LiteLLM、MCP）。

以下 6 项均为“建议修改”，不触发 NEEDS_FIX。

## 二、详细发现清单

### 必须修改

| ID | 严重级别 | 文件 | 行号/位置 | 核心问题 |
|----|----------|------|-----------|----------|
| — | — | — | — | 本轮未发现具备明确证据的必须修改项 |

### 建议修改

| ID | 严重级别 | 文件 | 行号/位置 | 核心问题 |
|----|----------|------|-----------|----------|
| S-01 | 建议（中） | `services/core-api/src/main/java/com/agentforge/core/agent/application/AgentActionService.java` | `createPending` 新增 `conversations.findByIdForUpdate` 分支 | 本轮新增的跨作用域会话判断抛 `ForbiddenException`（403），与 `requireWritable`/`get`/`delete` 的 404 口径不一致 |
| S-02 | 建议（中） | `services/core-api/src/test/java/.../conversation/api/ConversationHistoryApiTest.java` 或聊天 API 测试 | 缺失用例 | “携带已删除 conversationId 发同步/流式 Chat 返回 409”目前仅 service 层覆盖，缺 HTTP seam 契约断言（承接上轮 R2-02） |
| S-03 | 建议（低） | `services/core-api/src/main/java/com/agentforge/core/conversation/application/ConversationHistoryService.java` | `appendCompletedExchange` | 该公开 `@Transactional` 方法跨作用域仍抛 `ForbiddenException`，语义与同模块其余入口不统一（承接上轮 R2-03） |
| S-04 | 建议（低） | `ConversationHistoryService.requireWritable` | `conversations.findByIdForUpdate` | 纯校验路径对每次已有会话的 Chat 取 `PESSIMISTIC_WRITE` 行写锁，锁范围可收敛（承接上轮 R2-04） |
| S-05 | 建议（低） | `docs/01-product/v2-v3-node-roadmap.md`、`docs/07-changes/2026-09-18-p3-02-conversation-lifecycle.md` | P3-02 条目 / 状态字段 | 路线图 P3-02 未标 `Implemented`，变更记录仍为 `In Progress`，与功能/API 文档已描述交付存在状态落差（承接上轮 R2-05） |
| S-06 | 建议（低） | `apps/web/src/App.tsx` | 删除确认弹层 | 复用 `onboarding-*` 样式，未提供 Esc 关闭与初始焦点管理（承接上轮 R2-06） |

### 无需修改（本轮已核验的正确点）

| 项 | 说明 |
|----|------|
| R2-01 修复 | `conversationHistory` 为必需构造依赖，`Objects.requireNonNull` 在启动/构造期暴露缺失；空依赖测试已覆盖；删除 null 旁路后守卫无静默降级 |
| 删除事务一致性 | `delete` 内：`projectAccess.requireAccess` → 主行悲观写锁 → `belongsTo`/`isDeleted` → pending/approved Action 检查 → 批量删消息 → 写 tombstone/清空 preview/count，全部同一事务 |
| tombstone 不可复用 | 会话主行保留、`deleted_at` 非空；`findByScope`/`findAllByScope` 均带 `DeletedAtIsNull`；`delete` 对已删除再删返回 404；`appendCompletedExchange`/`createPending` 均拒绝 tombstone |
| 删除与审批并发 | `delete` 与 `createPending` 都先锁会话主行，锁序一致；先删则 `createPending` 见 tombstone 409，先建则 `delete` 见 PENDING/APPROVED 409 |
| 配额与转发顺序 | `requireWritable` 位于 `aiUsageQuota.consume` 与 `agentServiceClient.chat/prepareStream` 之前，符合“转发与消耗配额前拒绝旧 ID” |
| 批量删除安全 | `@Modifying @Query`（`delete from AgentMessage ...`）在事务内执行，调用链未在持久化上下文加载 `AgentMessage`，无脏实体风险；真实 PostgreSQL 集成测试验证消息清零、preview 清空、`deleted_at` 非空 |
| 迁移与索引 | V9 新增可空 `deleted_at` 与部分索引 `(project_id, requested_by_user_id, conversation_id) WHERE status IN ('PENDING','APPROVED')`，谓词与 `existsBy...StatusIn` 匹配 |
| 前端删除路由判定 | 删除完成以 `parseRoute(window.location.pathname)` + `conversationIdRef` 判定；`/chat/:id` 删除当前会话进空白 `/chat`，非 chat 路由仅清内存；回归覆盖“删除 A 未决时切 B 保持 B”“首页删除保持 `/`” |
| API 契约 | DELETE 204、匿名 401、未决审批 409、不存在/越权/已删除 404；typed client `Promise<void>` 与无 body 响应匹配；Web 测试已处理“删除按钮 aria-label 命中历史条目名”的选择器歧义 |

## 三、逐个 Issue 展开

### S-01 `createPending` 跨作用域返回 403，与 404 口径不一致

- Severity：建议（中）
- File & Line：`services/core-api/src/main/java/com/agentforge/core/agent/application/AgentActionService.java` → `createPending`
- Evidence：

```java
projectAccess.requireAccess(projectId, actor);
conversations.findByIdForUpdate(conversationId).ifPresent(conversation -> {
    if (!conversation.belongsTo(projectId, actor.userId())) {
        throw new ForbiddenException("The conversation belongs to another scope.");
    }
    if (conversation.isDeleted()) {
        throw new ConflictException("The conversation history was deleted.");
    }
});
```

- Description：这是本轮新增的守卫。对比 `ConversationHistoryService.requireWritable`（跨作用域抛 `ResourceNotFoundException`）以及 `get`/`delete`，同一“会话不属于当前 project/user”语义被映射为两种状态码。正常客户端输入无法直接触达：`AgentChatService.chat/prepareStream` 会先对客户端提供的 `conversationId` 调用 `requireWritable`，只有 Agent Service 返回一个属于他人作用域的 `conversationId` 时，`createPending` 才会走到该分支。因此不构成可被客户端利用的越权或存在性预言机（且 UUID 难枚举），但作为安全相关分支，状态码口径应统一。
- Suggested Fix：

```java
if (!conversation.belongsTo(projectId, actor.userId()) || conversation.isDeleted()) {
    throw new ResourceNotFoundException("Conversation not found: " + conversationId);
}
```

若确实需要区分“已删除”，可仅在 `isDeleted()` 分支抛 `ConflictException`，作用域不匹配统一 404。

### S-02 已删除 conversationId 发送 Chat 的 409 缺 HTTP/流式契约测试

- Severity：建议（中）
- File & Line：`services/core-api/src/test/java/com/agentforge/core/conversation/api/ConversationHistoryApiTest.java`；聊天/流式 Controller 测试
- Evidence：本轮已有 `AgentChatServiceTest.deletedConversationIsRejectedBeforeForwardingToAgent` 与 `deletedConversationIsRejectedBeforeOpeningStreamOrChargingQuota`，断言 `ConflictException` 且不消费配额、不与 client 交互；但未见 HTTP 层断言“POST 同步 Chat 或建立 SSE 流时携带已删除 conversationId 返回 409”。
- Description：Web 主路径是流式接口。`ConflictException → 409` 的异常映射已被其它端点测试间接证明，但“发送端携带已删除 ID”这一新增安全分支的端到端契约未固化，未来异常映射或过滤器链调整时可能无声回归。属测试充分性建议，不影响当前实现正确性（service 层已覆盖并验证在扣额度和调用 Agent 前拒绝）。
- Suggested Fix：在聊天 API 测试中 mock `ConversationHistoryService.requireWritable` 抛 `ConflictException`，断言 409 且未调用下游 Agent 客户端。

### S-03 `appendCompletedExchange` 跨作用域语义不统一

- Severity：建议（低）
- File & Line：`ConversationHistoryService.appendCompletedExchange`
- Evidence：

```java
if (!conversation.belongsTo(projectId, actor.userId())) {
    throw new ForbiddenException("The conversation belongs to another scope.");
}
if (conversation.isDeleted()) {
    throw new ConflictException("The conversation history was deleted.");
}
```

- Description：该方法由其上游 `requireWritable` 先拦截，跨作用域在 Chat 主路径上不可达，因此不构成本轮可运行性问题；但它作为公开 `@Transactional` 方法，语义与同模块 `requireWritable`/`get`/`delete` 不一致，未来被其它调用方复用时可能造成口径混乱（同 S-01）。
- Suggested Fix：作用域不匹配统一改为 `ResourceNotFoundException`，或在方法签名/注释中明确“仅接受已通过 `requireWritable` 的输入”。

### S-04 `requireWritable` 以写锁执行纯校验

- Severity：建议（低）
- File & Line：`ConversationHistoryService.requireWritable` → `conversations.findByIdForUpdate(conversationId)`
- Evidence：

```java
@Transactional
public void requireWritable(UUID projectId, UUID conversationId, AuthenticatedActor actor) {
    projectAccess.requireAccess(projectId, actor);
    conversations.findByIdForUpdate(conversationId).ifPresent(conversation -> { ... });
}
```

- Description：该方法的 `@Transactional` 独立于 `AgentChatService`（后者非事务方法），提交后锁即释放，因此对后续 append 的实际保护有限；真正的串行化依赖 append/createPending 阶段的锁与 tombstone 守卫。纯校验却对每次已有会话的 Chat 取悲观写锁，会在同会话并发请求下增加不必要的串行等待。属性能层面建议，无实际瓶颈证据。
- Suggested Fix：若仅需校验，可改用非锁定 scope 查询读取并判断 `isDeleted`，保留写锁于真正需要串行化的写入阶段。

### S-05 文档状态标记未同步

- Severity：建议（低）
- File & Line：`docs/01-product/v2-v3-node-roadmap.md` P3-02 条目；`docs/07-changes/2026-09-18-p3-02-conversation-lifecycle.md` 首部 `- 状态：In Progress`
- Evidence：路线图 P3-01 已标 `Implemented`，P3-02 未标；变更记录仍为 `In Progress`，而 `conversation-history.md`、`core-api.md`、`data-architecture.md` 已按交付语义描述删除行为。
- Description：按项目流程 `In Progress → Implemented` 通常在阶段提交/推送后回填，本轮属审核收口前，不阻塞；但应在收口时统一，避免路线图、变更记录与功能/API 文档状态不一致。
- Suggested Fix：阶段被接受并提交后，将变更记录置为 `Implemented`，并在路线图 P3-02 补 `Implemented` 标记。

### S-06 删除确认弹层缺少 Esc 与初始焦点

- Severity：建议（低）
- File & Line：`apps/web/src/App.tsx` 删除确认弹层
- Evidence：

```tsx
{conversationToDelete && <div className="onboarding-backdrop">
  <section className="onboarding-dialog" role="dialog" aria-modal="true" aria-labelledby="delete-conversation-title">
    ...
  </section></div>}
```

- Description：`role="dialog"`/`aria-modal`/`aria-labelledby` 已具备，核心可访问性达标；复用 onboarding 样式，未提供 Esc 关闭或打开时聚焦“取消/确认删除”。属体验建议，不影响功能与安全。
- Suggested Fix：补充 Esc 关闭与初始焦点管理，或在 P3-03 聊天界面阶段抽出通用 `ConfirmDialog` 组件复用。

## 四、上一轮建议的落地验证

| 上轮 ID | 判定 | 本轮核验 |
|---------|------|----------|
| R-01 / R2-01 | 已采纳并加固 | 流式 `prepareStream` 前置 tombstone 校验有 service 级测试；`conversationHistory != null` 旁路与 4 参构造器已移除，改 `Objects.requireNonNull`；新增空依赖测试。残留 HTTP 契约建议见 S-02 |
| R-02 | 已采纳 | `requireWritable` 跨作用域 404；`chatDoesNotRevealAnotherUsersConversationExistence` 覆盖。新增 `createPending` 分支存在 403 不一致，见 S-01 |
| R-03 | 未采纳（显式限制） | 文档已明确“在途请求遇 tombstone 返回 409 且不落历史”，取舍已显式化 |
| R-04 / R-05 | 已采纳 | 删除完成以当前 URL 判定；前台/首页两条回归覆盖 |
| R-06 | 已采纳 | 移除可传空 Repository 的构造器，收敛为单构造器 |
| R-07 | 未采纳 | 同模块应用协作、无运行期循环，超出 P3-02 范围，合理 |
| R-08 | 已采纳 | JPQL 批量删除 + 真实 PostgreSQL 验证消息清零 |
| R2-02 | 未采纳（本轮仍建议） | 见 S-02 |
| R2-03 | 未采纳（本轮仍建议） | 见 S-03 |
| R2-04 | 未采纳（本轮仍建议） | 见 S-04 |
| R2-05 | 待收口 | 见 S-05 |
| R2-06 | 部分采纳（延后 P3-03） | 见 S-06 |

## 五、主开发（Codex）评估回填区

| Issue ID | Codex 判定（采纳/部分采纳/不采纳） | 处理说明 | 关联文件/提交 |
|----------|-----------------------------------|----------|----------------|
| S-01 | 不采纳 | 该分支只处理 Agent 返回的 conversationId；客户端旧 ID 已由 `requireWritable` 先行拒绝，当前没有可利用的跨作用域输入路径。状态码口径可在后续公共错误整理时统一。 | `AgentActionService` |
| S-02 | 不采纳 | 服务级测试覆盖扣额和 Agent 调用前的拒绝；现有 HTTP 测试已覆盖异常映射与 SSE 建立路径，额外 mock 整个服务的 409 用例不能增加对真实分支的保障。 | `AgentChatServiceTest`、`AgentChatApiTest` |
| S-03 | 不采纳 | 与 S-01 同属内部防御分支，当前调用链在前置 `requireWritable` 后才到达；没有可利用路径或契约冲突。 | `ConversationHistoryService` |
| S-04 | 不采纳 | 缺少性能瓶颈证据，当前优先保留同会话写入与删除的清晰串行点。 | ADR-0025 |
| S-05 | 采纳 | 收口时将路线图与变更记录统一更新为 Implemented。 | 路线图、P3-02 变更记录 |
| S-06 | 部分采纳 | 当前手动确认与弹层语义已实现；键盘和焦点体验纳入紧接的 P3-03 聊天界面阶段。 | P3-03 路线图 |

## 六、审查边界声明

- 本轮为只读审查，未运行任何命令、未修改文件、未改变 Git 状态。
- 变更记录中给出的 Web（3 files/50 passed、build 退出码 0）与 Core（`clean verify` 退出码 0、24 suites/120 tests/0 failures/0 errors/8 skipped、真实 PostgreSQL 集成用例）结论仅作为启动器提供的证据引用，不代表 Pi 执行过这些测试。
- 审查范围严格限定在本次 Diff、涉及文件及显式必要接口（DELETE 端点、会话作用域与 tombstone 校验、Action 创建守卫、Web 历史删除交互）。未发现需要重新设计架构或扩展到全项目的证据，故未触发 NEEDS_FIX。
