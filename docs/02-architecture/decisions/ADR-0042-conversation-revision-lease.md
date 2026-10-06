# ADR-0042：会话 revision lease 与短期 exchange claim

- 状态：Accepted
- 日期：2026-10-06
- 决策范围：Python Agent Service 的进程内 Conversation Memory 并发提交与 Action checkpoint 收口
- 细化：ADR-0017、ADR-0018

## 背景

ADR-0017/0018 的内部 lease 只绑定完整 Memory Namespace 与 session generation。两个请求可以从同一个 generation 同时读取相同历史，分别完成模型生成，再按完成先后都提交 exchange；第二个回答并未看到第一个已提交的对话，却仍成为后续历史。若仅在最终 commit 增加冲突校验，而 Action WAITING checkpoint 已先创建，失败请求还会遗留无法由成功响应关联的待决轮次。

不能用覆盖模型或网络调用的全局互斥锁解决：一次慢会话会阻塞所有用户和不同会话，且取消时更难安全释放。当前 Memory 仍是进程内有界状态，本决定不把它扩展为跨实例分布式锁或持久历史。

## 决定

- 每个 session 除 generation 外维护单调 revision；每次 load 返回 Namespace、generation、读取时 revision 与唯一 lease ID。
- 模型和检索继续基于只读快照并行执行，不持有 Conversation Memory 全局锁。
- 完整回答形成后，请求在短临界区认领其 lease 对应的当前 revision。只有 generation、revision 都仍匹配且没有其他 lease 认领时才成功。
- Action proposal 必须在认领成功后才创建 WAITING checkpoint；随后提交 exchange 并原子推进 revision、清除认领。输入合法性在认领前完成，避免认领后因空消息等确定性错误留下 checkpoint。
- 请求失败、取消或 checkpoint 异常时，只能用相同 lease ID 释放自己的认领；不得释放其他请求的认领。流式响应若已输出 delta 后才发现冲突，以安全 error 结束且不发送 complete。
- 被认领的 session 在提交或释放前不能被 LRU 淘汰。容量已满且全部 session 都在认领时，新 Namespace 失败关闭，不替换任何活跃 session。
- 不同 Namespace 不共享 claim；实现只在 load、claim、commit、release 与 LRU 元数据变更的短临界区使用现有锁，不把模型、Retriever、checkpoint 数据库或网络调用置于锁内。

## 理由

revision 让“基于哪个历史生成”成为可验证的提交前置条件；唯一 lease ID 又使取消清理只能影响原请求。先 claim 后 checkpoint 把并发失败边界提前到持久副作用之前，同时保留不同会话的并行度。相比为每个 Namespace 长时间持锁，该方案不会让同一进程的一次慢模型请求阻塞其他用户。

## 影响与限制

- 同一会话的并发请求仍可同时消耗模型资源；只有完成提交被串行化。冲突的流式请求可能已向调用方发送 delta，但不会发送 complete、提交 Memory 或创建 WAITING。
- 当前机制仅在单个 Agent Service 进程内有效。多实例各自有 Memory 与 revision，重启会丢失历史；跨实例一致性仍需未来持久化设计。
- 公共和内部 HTTP 字段不变；Java 继续负责权限、Approval 与业务写入。
- 现有 generation 仍防止淘汰/重建后的旧 lease 写入，revision 只补充同 generation 的并发完成顺序。

## 验证

在 ConversationMemory 公共 seam 取得真实红灯/绿灯：同 Namespace 两个并发 lease 只能提交一个；不同 Namespace 可同时 claim；取消释放后后继 lease 可提交；旧实例 lease 不能用于新实例；已 claim session 不被 LRU 淘汰。同步/流式 FastAPI seam 验证并发冲突使用既有安全错误，Action proposal 冲突不会创建 WAITING checkpoint，普通成功、摘要与 Namespace 隔离保持不变。
