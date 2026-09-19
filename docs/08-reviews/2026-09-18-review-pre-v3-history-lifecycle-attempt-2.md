# Pi 代码审查报告：pre-v3-history-lifecycle / Attempt 2

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
- 审查模式：Milestone Review（第 2 / 3 轮）
- 审查目标：`c04f3fc553030ac9b5218f0a7aa50d95220ef4bb .. INDEX@c04f3fc`
- 审查性质：完全只读；本轮未执行命令、未修改文件、未改变 Git 状态。
- 证据来源：本次 Git Diff、启动器提供的显式上下文、上一轮报告（`2026-09-18-review-pre-v3-history-lifecycle-attempt-1.md`）、ADR-0025/功能/API/数据架构文档，以及变更记录中的测试证据（仅作为证据引用，未改写为 Pi 执行结果）。

## 一、概述与总体结论

结论：**通过（PASS）**。本轮未发现具备明确证据的“必须修改”问题。

本轮是在上一轮 PASS 基础上的修复轮，核心变化与上轮建议的对应关系如下：

1. **R-02（跨作用域状态码一致性）已修复**：`ConversationHistoryService.requireWritable` 对不属于当前 project/user 的会话统一抛 `ResourceNotFoundException`（404），仅 `isDeleted()` 抛 `ConflictException`（409），与 `get`/`delete` 口径一致，消除了跨作用域存在性预言机。`ConversationHistoryServiceTest.chatDoesNotRevealAnotherUsersConversationExistence` 覆盖。
2. **R-01（流式守卫测试缺口）已补**：`AgentChatService.prepareStream` 加入 tombstone 前置校验，`AgentChatServiceTest.deletedConversationIsRejectedBeforeOpeningStreamOrChargingQuota` 断言配额未消费且 `client` 零交互。
3. **R-06（null 旁路）已修复（于 AgentActionService）**：删除可传空 Repository 的多余构造器，收敛为单一必需依赖构造器。
4. **R-08（派生删除）已修复**：消息删除改为 `@Modifying @Query` 批量删除，并在真实 PostgreSQL 集成测试中验证消息清零。
5. **R-04 / R-05（前端删除竞态与首页跳转）已修复**：删除完成时以“当前 URL”重新判定是否重置聊天，改用 `conversationIdRef` 处理首页/非 chat 路由的后台会话清理，新增两条 DOM 回归。

从 Milestone 视角看：本轮交付与路线图 P3-02 的目标（单会话删除、关联状态安全处置、旧 AI 回复保持可见、流失败不抹掉其他轮次）一致。并发边界（删除 vs 待审批 Action 创建 vs append 的会话主行悲观锁串行化）设计自洽，未越界到 V3 组件，Java 仍是会话与审批的权威写入边界。

以下 6 项均为“建议修改”或“无需修改”，不触发 NEEDS_FIX。

## 二、详细发现清单

### 必须修改

| ID | 严重级别 | 文件 | 行号/位置 | 核心问题 |
|----|----------|------|-----------|----------|
| — | — | — | — | 本轮未发现具备明确证据的必须修改项 |

### 建议修改

| ID | 严重级别 | 文件 | 行号/位置 | 核心问题 |
|----|----------|------|-----------|----------|
| R2-01 | 建议（中） | `services/core-api/src/main/java/com/agentforge/core/agent/application/AgentChatService.java` | `chat` / `prepareStream` 的 `conversationHistory != null` | 防御性 null 检查在依赖为 null 时会静默跳过 tombstone 守卫，与本轮已修复的 R-06 同类问题不一致 |
| R2-02 | 建议（中） | `ConversationHistoryApiTest.java` / 聊天 API 测试 | 缺失用例 | 已删除 conversationId 发送 Chat 的 409 目前只有 service 单测，缺 HTTP/流式 seam 的契约断言 |
| R2-03 | 建议（低） | `ConversationHistoryService.appendCompletedExchange` | `belongsTo` 分支 | 该公共方法仍抛 `ForbiddenException`，与 `requireWritable`/`get`/`delete` 的 404 语义不统一 |
| R2-04 | 建议（低） | `ConversationHistoryService.requireWritable` | `conversations.findByIdForUpdate` | 纯校验路径对每次已有会话的 Chat 取 `PESSIMISTIC_WRITE` 行写锁，锁范围可收敛 |
| R2-05 | 建议（低） | `docs/01-product/v2-v3-node-roadmap.md`、`docs/07-changes/2026-09-18-p3-02-conversation-lifecycle.md` | P3-02 条目 / 状态字段 | 路线图 P3-02 未标 `Implemented`，变更记录仍为 `In Progress`，与功能/API 文档已描述交付存在状态落差（上轮 R-09 的延续） |
| R2-06 | 建议（低） | `apps/web/src/App.tsx` | 删除确认对话框 | 复用 `onboarding-*` 样式，未提供 Esc 关闭与焦点管理，属无障碍体验建议 |

### 无需修改

| 项 | 说明 |
|----|------|
| tombstone 与不可复用 | 会话主行保留、`deleted_at` 非空；`findByScope`/`findAllByScope` 均带 `DeletedAtIsNull`；`delete` 对已删除返回 404，重复删除不可复用 |
| 删除与待审批 Action 串行化 | `delete` 与 `createPending` 都先对 `agent_conversation` 主行取悲观写锁；锁顺序一致，无环。删除先提交则 `createPending` 见 tombstone 返回 409；创建先提交则删除见 PENDING 返回 409 |
| 配额与转发顺序 | `requireWritable` 位于 `aiUsageQuota.consume` 与 `agentServiceClient.chat/prepareStream` 之前，符合“在消耗配额与调用 Agent 前拒绝”的文档语义 |
| 批量消息删除 | `@Modifying @Query` 在 `@Transactional` 内执行，调用链未在持久化上下文加载 `AgentMessage`，无脏实体风险；真实 PostgreSQL 集成测试验证消息清零 |
| 迁移与索引 | V9 新增可空 `deleted_at` 与部分索引 `(project_id, requested_by_user_id, conversation_id) WHERE status IN ('PENDING','APPROVED')`，谓词与 `existsByProjectIdAndRequestedByUserIdAndConversationIdAndStatusIn` 匹配 |
| 前端删除路由判定 | 删除完成后以 `parseRoute(window.location.pathname)` + `conversationIdRef` 判定，避免旧回调把正在查看的另一会话重置为空白 `/chat`；首页/非 chat 路由仅清内存不跳转 |
| 旧 AI 回复可见性 | `setExpandedChatIds((current) => new Set(current).add(historyId))` 保留既有轮次展开，测试已同步为“旧回答仍可见 + 手动收起生效” |
| API 契约 | DELETE 204、匿名 401、未决审批 409、不存在/越权/已删除 404；Web typed client `Promise<void>` 与无 body 响应匹配 |

## 三、逐个 Issue 展开

### R2-01 `conversationHistory != null` 会静默禁用 tombstone 守卫

- Severity：建议（中）
- File & Line：`services/core-api/src/main/java/com/agentforge/core/agent/application/AgentChatService.java`（`chat` 与 `prepareStream`）
- Evidence：

```java
projectAccess.requireAccess(projectId, actor);
if (conversationId != null && conversationHistory != null) {
    conversationHistory.requireWritable(projectId, conversationId, actor);
}
aiUsageQuota.consume(actor.userId());
```

- Description：`conversationHistory` 是构造器注入的必需 Bean，Spring 启动时缺失会直接失败，因此生产路径上该字段永不为 null，`!= null` 属死代码；但它带来的语义是“当该依赖为 null 时静默跳过跨作用域/ tombstone 校验”，与本轮已修复的 R-06（移除可传空 Repository 的构造器旁路）属同一类问题。安全相关守卫不应存在静默降级分支。
- Suggested Fix：移除 null 判断，直接调用；或在构造器中对依赖做 `Objects.requireNonNull(conversationHistory)`，让缺失在启动期暴露。

```java
if (conversationId != null) {
    conversationHistory.requireWritable(projectId, conversationId, actor);
}
```

### R2-02 已删除 conversationId 发送 Chat 的 409 缺 HTTP/流式契约测试

- Severity：建议（中）
- File & Line：`services/core-api/src/test/java/com/agentforge/core/conversation/api/ConversationHistoryApiTest.java`；聊天/流式 Controller 测试
- Evidence：本轮新增 `AgentChatServiceTest.deletedConversationIsRejectedBeforeOpeningStreamOrChargingQuota`，覆盖 `prepareStream` 在 service 层抛 `ConflictException` 且不消费配额；但未见 HTTP 层断言“POST 同步 Chat / 建立 SSE 流时携带已删除 conversationId 返回 409”。
- Description：Web 实际主路径是 SSE 流式接口。虽然 `ConflictException` 的 409 映射已由其它端点用例间接证明，但“发送端携带已删除 ID”这一新增安全分支的端到端契约（状态码 + 未触达 Agent）仍建议在 API seam 固化，避免未来异常映射或过滤链调整后无声回归。
- Suggested Fix：在聊天 API 测试中新增一条：mock `ConversationHistoryService.requireWritable` 抛 `ConflictException`，断言 409 且未调用下游 Agent 客户端。

### R2-03 `appendCompletedExchange` 的作用域错误语义不统一

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

对比同类的 `requireWritable`/`get`/`delete` 对作用域不匹配统一返回 `ResourceNotFoundException`。

- Description：在 Chat 主路径上，`appendCompletedExchange` 由其上游 `requireWritable` 先行拦截，跨作用域场景不可达，因此不构成本轮可运行性问题。但该方法是公开的 `@Transactional` 服务方法，语义与同模块其它入口不一致，后续被其它调用方复用时可能造成口径混乱。
- Suggested Fix：将作用域不匹配也改为 `ResourceNotFoundException`，或明确注释该方法仅接受已通过 `requireWritable` 的输入。

### R2-04 `requireWritable` 使用写锁但只做校验

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

- Description：该方法不被 `AgentChatService` 的同一事务包裹（`AgentChatService` 非事务方法），因此 `requireWritable` 自身事务提交后锁即释放，守卫对后续 append 的保护有限，这与 R-03 的既有取舍一致。纯校验却对每次已有会话的 Chat 取悲观写锁，会增加同会话并发请求的串行化与锁竞争；实际有效的一致性是依靠 append/createPending 阶段的锁与 tombstone 守卫。属性能层面建议。
- Suggested Fix：若仅需校验，改用非锁定 scope 查询（`findByScope`）读取并判断 `isDeleted`；保留 append/createPending 处的写锁做真正串行化。

### R2-05 文档状态标记未同步

- Severity：建议（低）
- File & Line：`docs/01-product/v2-v3-node-roadmap.md` 的 P3-02 条目；`docs/07-changes/2026-09-18-p3-02-conversation-lifecycle.md` 首部 `- 状态：In Progress`
- Evidence：路线图 P3-01 标注 `Implemented`，P3-02 条目未标注；变更记录仍为 `In Progress`，而 `docs/03-features/conversation-history.md`、`docs/04-api/core-api.md`、`docs/02-architecture/data-architecture.md` 已按交付语义描述删除行为。
- Description：按项目流程 `In Progress → Implemented` 通常在阶段提交/推送后回填，本轮属审核前，暂不阻塞；但应在收口时统一，避免路线图、变更记录与功能/API 文档状态不一致。
- Suggested Fix：阶段被接受并提交后，把变更记录置为 `Implemented`，并在路线图 P3-02 后补 `Implemented` 标记。

### R2-06 删除确认对话框无障碍细节

- Severity：建议（低）
- File & Line：`apps/web/src/App.tsx` 删除确认弹层
- Evidence：

```tsx
{conversationToDelete && <div className="onboarding-backdrop">
  <section className="onboarding-dialog" role="dialog" aria-modal="true" aria-labelledby="delete-conversation-title"> ... </section>
</div>}
```

- Description：`role="dialog"`/`aria-modal`/`aria-labelledby` 已具备，核心可访问性达标；但复用了 onboarding 样式，未提供 Esc 关闭或初始焦点管理。属体验建议，不影响功能与安全。
- Suggested Fix：为删除弹层补充 Esc 关闭与打开时聚焦“取消”/“确认删除”的处理，或抽出通用 `ConfirmDialog` 组件复用。

## 四、上一轮建议的落地验证

| 上轮 ID | 判定 | 本轮核验 |
|---------|------|----------|
| R-01 | 已采纳 | `prepareStream` 新增加守卫；新增 service 级测试断言不消费配额、不与 client 交互。残留 HTTP/流式契约测试建议见 R2-02 |
| R-02 | 已采纳 | `requireWritable` 作用域不匹配改为 404；新增 `chatDoesNotRevealAnotherUsersConversationExistence` |
| R-03 | 未采纳（保留限制） | 新增文档明确“在途请求遇 tombstone 返回 409 且不落历史”，取舍已显式化 |
| R-04 | 已采纳 | 删除完成以当前 URL 判定；新增“删除 A 未决时切 B 保持 B”回归 |
| R-05 | 已采纳 | 首页/非 chat 路由改为仅清内存；新增首页删除保持 `/` 回归 |
| R-06 | 已采纳 | 移除可传空 Repository 的构造器，收敛为单构造器 |
| R-07 | 未采纳 | 同模块应用协作、无运行期循环，超出 P3-02 范围，合理 |
| R-08 | 已采纳 | 改为 JPQL 批量删除，真实 PostgreSQL 测试验证消息清零 |
| R-09 | 待收口 | 见 R2-05 |

## 五、主开发（Codex）评估回填区

| Issue ID | Codex 判定（采纳/部分采纳/不采纳） | 处理说明 | 关联文件/提交 |
|----------|-----------------------------------|----------|----------------|
| R2-01 | 采纳 | 以实际红灯证明空 `ConversationHistoryService` 可构造，再移除四参构造器并强制非空依赖；聚焦测试与 Core clean verify 通过。 | `AgentChatService`、`AgentChatServiceTest`；本阶段提交 |
| R2-02 | 不采纳 | `prepareStream` 和同步 `chat` 的服务测试已验证旧 ID 在扣额度和调用 Agent 前被拒；现有 Controller 异常映射和 SSE 建立路径已有测试。此处再 mock 整个服务断言 409 仅重复异常映射，缺少新增行为证据。 | `AgentChatServiceTest`、`AgentChatApiTest` |
| R2-03 | 不采纳 | 主路径先执行 `requireWritable`；持久化阶段的跨作用域异常属于防御分支，当前没有可利用路径。若独立公开该应用服务入口，再统一语义。 | `ConversationHistoryService` |
| R2-04 | 不采纳 | 锁竞争属于性能优化；目前同会话并发由写锁与 tombstone 检查约束，缺少实际瓶颈证据。 | ADR-0025 |
| R2-05 | 采纳 | 阶段收口时同步路线图和变更记录状态。 | 路线图、P3-02 变更记录 |
| R2-06 | 部分采纳 | 当前弹层具备语义角色与手动确认；Esc 和初始焦点随 P3-03 聊天界面阶段统一处理。 | P3-03 路线图 |

## 六、审查边界声明

- 本轮为只读审查，未运行任何命令、未修改文件、未改变 Git 状态。
- 变更记录中给出的 Web/Core 测试结论仅作为启动器提供的证据引用，不代表 Pi 执行过这些测试。
- 审查范围严格限定在本次 Diff、涉及文件及显式必要接口（DELETE 端点、会话作用域与 tombstone 校验、Action 创建守卫、Web 历史删除交互）。未发现需要重新设计架构或扩展到全项目的证据，故未触发 NEEDS_FIX。
