# ADR-0017：V2-03 使用进程内有界会话上下文

- 状态：Accepted
- 日期：2026-09-07
- 决策范围：Python Agent Service 的多轮会话历史与摘要生命周期

## 背景

V2-03 必须让相同 conversationId 的长对话拥有 Recent Messages、Conversation Summary 和真实生效的 Token Budget。当前 HTTP 契约只传当前消息与 conversationId，数据库也没有 conversation memory schema。可选方案包括立即增加持久化、由浏览器回传全部历史，或由 Agent Service 暂存有界会话状态。

## 决定

V2-03 在 Agent Service 内使用线程安全、容量受限的进程内 store：

- conversationId 首次使用时绑定 projectId 与 userId，后续不允许改变绑定。
- load 快照携带仅供进程内部使用的 session generation；完成提交必须同时匹配 conversationId、scope 和 generation。若请求执行期间 session 被 LRU 淘汰或重绑，拒绝提交，不能把回答写入另一作用域。
- 仅在回答完整成功后提交 user/assistant exchange；失败、Tool proposal、Tool result 和 Retrieval Context 不进入历史。
- 最近消息以结构化 role/content 保存，每条历史副本最多占用总 Context Budget 的保守计数；被挤出的原始消息形成受预算约束的确定性摘要，不对已有摘要做递归模型总结。当前请求与回答仍按既有 API 完整处理，截断只影响下一轮使用的内存副本。
- store 的 session 数量和每个 session 的 Recent/Summary 大小均有上限，最久未使用的 session 可被淘汰。
- 同步和流式请求使用同一 store；流式仅在 complete 前的所有 delta 成功产生后提交完整 assistant 文本。

## 理由

由客户端回传历史会扩大公共契约并使服务端难以证明历史属于当前授权作用域；本节点直接建表又会提前占用后续持久化和 V2-04 namespace 的设计空间。进程内有界 store 能在保持 HTTP 兼容的同时验证 Context Composition、摘要和预算策略，并明确限制内存增长。

确定性摘要只处理原始对话，牺牲一部分语义压缩能力，但避免额外模型调用、摘要自我漂移、上游故障扩大和 Tool Result 被误总结。未来若替换为模型摘要，必须保持相同 public seam、原始消息边界和预算后置校验。

## 影响

- 进程重启、滚动部署或 session 淘汰后，对话上下文会丢失。
- 多实例之间不共享历史；生产持久化和恢复必须由后续节点重新决策。
- 该 store 不是 V2-04 Memory Namespace，不能作为 tenant/workspace 隔离已经完成的证据。
- HTTP schema、Java 权限边界和业务写入职责不变。

## 验证

通过 Context/store 公共接口、LLM model boundary、同步/流式 FastAPI 入口验证：作用域拒绝、成功后提交、失败不提交、近期消息与摘要分离、Tool 内容排除、最终 Prompt 不超过预算和两种请求路径一致。
