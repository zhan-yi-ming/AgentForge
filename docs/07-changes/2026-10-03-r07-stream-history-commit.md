# R07 流式完成与历史提交一致性

- 状态：Verified（待 Pi 审核与提交）
- 日期：2026-10-03
- 风险：L3（流式成功契约、历史数据一致性、数据库迁移）
- 影响域：Core API、PostgreSQL、Docs

## 问题确认

深层审查 R07 成立。Core 当前先把 SSE `complete` 交给浏览器，再追加历史；历史失败只记录 warning，仍向客户端报告成功。若发送 `complete` 时连接断开，sink 异常还会跳过历史写入。因此客户端可观察到成功但立即读不到历史，Java 展示历史也可能与已提交的 Python exchange 脱节。

## 目标与边界

- Java 只在完整 exchange 与 pending Action（如有）均已完成确定性处理、且展示历史事务提交成功后发送公共 `complete`。
- 历史保存失败不得发送 `complete`，由既有流式错误边界返回通用 `error`；不得静默成功。
- `complete` 发送阶段断线不得撤销或跳过已提交历史。
- 展示历史按 conversationId + requestId 去重；同一完成 exchange 的响应丢失重试不得重复追加 USER/ASSISTANT。
- 不在模型调用期间持有数据库事务，不改变 Python memory/checkpoint、配额或审批权限边界。

## 验证计划

- `AgentChatService.stream` 公共 seam：证明当前 `complete` 先于历史写入，且历史失败仍泄露成功；修复后验证持久化先于 `complete`、失败不发 `complete`、complete sink 断线时历史已提交。
- `ConversationHistoryService` 公共 seam：证明相同 requestId 会重复追加；修复后验证同一 exchange 幂等、不同 requestId 正常追加。
- 真实 PostgreSQL：验证 V15 迁移、JPA validate、唯一索引和重复调用只保存两条消息。
- 最终运行门禁规划、相关测试、Core clean verify、diff check、敏感扫描与 Pi Milestone Review。

## 验证回填

### TDD 与实现

- 红灯 1：`historyWriteFailurePreventsTheCompletedStreamEvent` 在旧实现下明确失败为“预期抛出异常但未抛出”，日志同时证明历史异常被 warning 吞掉且 `complete` 已交付。
- 绿灯 1：Core 收到内部 `complete` 后先完成 Action finalization 与短事务历史提交，再调用公共 sink；历史失败向上进入既有通用 `error` 边界，发送 `complete` 时断线也不会跳过已提交历史。
- 红灯 2：幂等测试因历史仓储没有 requestId 查询 seam、append 没有 requestId 参数而 testCompile 失败。
- 绿灯 2：V15 为 `agent_message` 新增兼容遗留数据的可空 request_id，以及 `(conversation_id, request_id, role)` 部分唯一索引；新交换的 USER/ASSISTANT 共用非空 requestId。
- 历史服务在 conversation 行锁内读取同 requestId 的完整交换：载荷和来源完全相同则幂等返回；同 key 不同交换返回 409 冲突，不能把客户端新答案与旧历史静默分叉。
- 未在 LLM/Agent 调用期间开启数据库事务；Python memory/checkpoint 与 Java 权限、配额、审批职责均未改变。

### 当前机器证据

- `AgentChatServiceTest#historyWriteFailurePreventsTheCompletedStreamEvent`：修复前 1 failure，修复后 1 passed。
- `ConversationHistoryServiceTest#completedExchangeRetryWithTheSameRequestIdDoesNotAppendDuplicateMessages`：修复前 testCompile 因缺少 requestId seam 失败，修复后通过。
- `AgentChatServiceTest,ConversationHistoryServiceTest`：28 tests，0 failure/error/skip，退出码 0；覆盖历史失败不发 complete、complete sink 断线后已保存、精确幂等与载荷冲突。
- `PersistenceIntegrationTest#conversationHistoryRetriesAreIdempotentByRequestId`：真实 PostgreSQL，15 个 Flyway migration 校验并应用，1 test 通过；相同 requestId 重试保持 2 条消息，不同 requestId 正常增长到 4 条，message_count 一致。
- `mvnw clean verify`：230 tests，0 failure/error，12 skipped，BUILD SUCCESS，退出码 0，耗时 2:46。日志保留既有 Graph 测试上下文切换后调度线程访问已关闭 PostgreSQL 的 warning/error；未造成测试失败，且不在本次历史提交路径。
- `plan-change-gates.ps1 -BaseRef HEAD -TargetRef WORKTREE -Json`：L3，CoreApi + Docs，要求 database-integration、java-clean-verify 与 Milestone Review；实现回填前 fingerprint `69c0ac6e7d7abc85d7c700efe3b13180313e119c170b796a012acb89b7e5b97f`。
- `git diff --check`：通过。

### 效率记录

- 上下文限定在流式服务/控制器、展示历史 service/domain/repository、V6/V9 迁移与直接测试；未修改 Web 或 Python。
- 根因假设 ① 回调顺序错误、② sink 异常中断后续保存、③ 缺少 requestId 去重均被测试证实；控制器现有异常边界可继续复用，无需新增公共字段。
- Pi Milestone Attempt 1：PASS，无必须修改项。
- S1 记录：历史提交失败前可能已形成 PENDING Action 属真实跨事务窗口，但 R06 已提供按当前用户恢复 PENDING/APPROVED 的入口；把 Action、历史和 Python Abort 合并为一个事务会扩大审批补偿边界，本修复不冒险改造。客户端不会收到虚假的 complete，刷新后可从恢复队列继续处理。
- S2 采纳：尽管公共 `RequestIdFilter` 已限制安全 requestId 为 1–100 字符，历史 service 也显式拒绝空白或超过 100 字符；红灯曾落到 mock NPE，修复后 `ConversationHistoryServiceTest` 10/10 通过。
- S3 保持 fail-closed：同 requestId 不同 question/answer/sources 明确冲突，避免一个幂等身份对应两个事实。Web 常规重发生成新 requestId；响应丢失后的首次结果可从历史读取。
- S4 部分采纳：补齐长度边界；主路径已有真实 PostgreSQL、会话行锁与唯一索引证据。并发同会话属于后续 R18 专项，不在 R07 重复扩大范围。
- PASS 后建议调整不触发第二轮 Pi；相关生产输入变化仅为 requestId 前置校验，已重跑受影响历史 service 测试类。
