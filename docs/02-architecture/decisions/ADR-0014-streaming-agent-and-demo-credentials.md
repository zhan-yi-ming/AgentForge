# ADR-0014：SSE 流式 Agent 与服务器专有 Demo 凭据

- 状态：Accepted
- 日期：2026-09-06
- 后续决策：固定 Demo 凭据的公开策略已由 `ADR-0015-public-demo-credential-and-centered-chat.md` 取代；本文的流式协议与服务信任边界继续有效

## 背景

公网面试 Demo 需要稳定、可重复分享的普通用户凭据，也需要在较慢的模型调用中持续展示回答。前端不能持有模型 key；Python 不能承担用户授权、费用配额或业务写入；固定 Demo 密码不能进入公开仓库。

## 决策

1. 生产 `.env` 可配置固定 Demo email/password，且 seed 脚本继续生成独立随机备用账号。固定密码只由宿主机脚本读取，不在 Compose service environment 中声明；两个账号均通过临时开启的公共注册创建为 USER。
2. 保留现有 REST/JSON Chat，新增 POST SSE 公共入口。Java 在返回流前完成 JWT、项目授权和 PostgreSQL 日配额扣减，避免未授权或超额请求启动模型流。
3. Python 新增内部 NDJSON 流入口。LangGraph 继续负责 prepare、retrieve 和 deterministic tool plan；OpenAI-compatible adapter 使用模型 SDK 的 streaming iterator 产生真实 delta。Python 最终发送来源和 tool proposal，但不保存 action。
4. Java 边读内部 NDJSON 边发浏览器 SSE；只把 delta、公共 metadata 和安全错误向外发送。完整 tool proposal 到达后，Java复用既有 AgentActionService 转成 pending action，再发送 complete 事件。
5. Nginx 对流式路由关闭 proxy buffering 并保留现有 IP 限速；React 使用 `fetch` POST 和 `ReadableStream`，不使用只支持 GET 的 EventSource。

## 取舍

NDJSON 便于 Java 按行解析内部流，SSE 便于浏览器按事件消费；两种格式增加一个小型适配层，但避免把内部结构直接暴露公网。保留 JSON 接口会短期存在两条 Chat 表达路径，却降低回归风险并允许文本整理继续使用原子响应。固定账号提高分享便利性，也更容易被多人共用，因此必须继续受日配额、IP 限速和 workspace 隔离保护。

## 后果与限制

- 流开始后的上游失败不能再改 HTTP 状态，客户端通过 `error` 事件获得通用错误和 request ID。
- 首个 delta 前仍需完成授权、RAG 和模型首 Token 等待。
- 不持久化聊天记录；刷新页面后回答消失。
- 固定密码轮换需要维护者修改服务器 `.env` 并通过受控账号流程处理，不能通过 Git 发布密码。
