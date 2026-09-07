# Context Management

- 状态：Implemented
- 所属阶段：V2-02
- 相关架构：`../02-architecture/system-overview.md`

## 工程目标

Context Manager 把一次 Agent 请求中“模型应该看到什么”从 LangGraph 的流程状态中分离出来。它不是 Prompt 美化器，也不负责业务授权；其目标是让 Working、Conversation、Project、Retrieved 与 Tool Context 有稳定结构、明确来源和单一组合入口。

## ContextBundle

| 部分 | 当前内容 | 生产者 | 消费者 |
| --- | --- | --- | --- |
| Working Context | 规范化后的当前用户消息 | prepare / Context Manager | Retrieval、Tool Planner、Responder |
| Conversation Context | 可空 summary 槽位 | 本节点固定为空 | V2-03 使用；当前 Responder 不拼入空值 |
| Project Context | project、user、admin、request 标识 | 已通过 Java 授权的内部 Chat 请求 | Retrieval、Trace 关联 |
| Retrieved Context | 项目内检索文本与结构化 sources | Retrieval Node | Responder、API response |
| Tool Context | 本次确定性 Tool proposal | Tool Planner | API complete/response；不进入模型 messages |

`ContextBundle` 仅存在于 Python Agent Service 内部，不进入公共或内部 HTTP schema。Context Manager 创建初始 bundle，并以整体替换方式更新 Retrieved/Tool 部分，避免 Node 直接维护一组同级散落字段。

## State 与 Context 边界

LangGraph State 保存请求入口字段、当前 `ContextBundle` 和流程输出 `answer`。入口原始字段只供 Context Manager 建立 bundle；prepare 后的 Retrieval、Planner 和 Responder 从 bundle 读取数据。Context 是模型/Agent 决策所需资料，State 是图执行期间传递这些资料与结果的载体，两者不能混为同一概念。

Project Context 显式携带每次已授权请求的 project/user 边界。Retriever 仍必须把该 project ID 传给 Core 来源 API 和 RAG Store；Context Manager 不能使用全局当前项目、上一次请求或浏览器状态。Tool proposal 保存在 Tool Context，但不自动追加到 Working/Conversation messages，也不允许循环进入后续 Prompt。

## 同步与流式路径

同步 `/internal/v1/chat` 与流式 `/internal/v1/chat/stream` 使用同一个 prepare/retrieve/plan Context 过程。同步路径随后执行 responder；流式路径把已构建的 bundle 交给模型原生 stream。二者返回既有 conversation、request、sources、answer/proposal 契约，不新增 ContextBundle 字段。

## 安全与失败

- Java 仍在调用 Python 前完成认证、项目权限和配额；Context Manager 不替代授权。
- Retrieved Context 只接受当前 project 的检索结果；跨项目负向测试继续由 RAG seam 保证。
- Tool Context 只保存白名单 planner 的结构化意图，Python 不执行写入。
- Context 正文不进入 Langfuse metadata；Trace 继续只记录白名单状态与数量。
- Context 构建或检索失败沿用现有 422/503 与脱敏错误行为。

## 测试边界

通过 LangGraph/FastAPI 公共入口断言 prepare 后各 Node 消费同一个 bundle、同步与流式输出一致、project 标识显式传给 Retriever、Retrieved/Tool Context 不重复或串用。通过 Responder 的模型 boundary fake 断言 messages 只组合 Working 与 Retrieved Context，空 Summary 和 Tool proposal 不进入 Prompt。

## 已知限制

V2-02 不生成 Conversation Summary，不计算 Token Budget，也不持久化 ContextBundle。Conversation Context 的 summary 槽位仅用于稳定后续结构；V2-03 才定义摘要生命周期与预算策略，V2-04 才定义跨 tenant/workspace/project/user/thread 的 Memory Namespace。
