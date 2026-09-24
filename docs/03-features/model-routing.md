# Model Gateway 与 Provider 抽象

- 状态：Implemented（V3-02；Milestone Review PASS）
- 阶段：V3-02
- 相关决策：ADR-0030（取代 ADR-0012 的模型客户端选择）
- 相关接口：`../04-api/agent-service.md`（HTTP 契约不变）

## 用户场景与边界

项目管理员通过部署配置选择 DeepSeek、智谱、千问或 GPT 模型。普通用户沿用 Chat 和 Tool 意图入口；切换 provider 不修改 LangGraph、RAG、Tool 或 Java 业务代码。Agent 仍只产生文本与 Action Intent，Java 独占授权、风险、审批与写入。

## 调用流程

`Agent responder → ModelGateway → LiteLLM Python SDK → configured provider`。Python 服务持有模型凭据；浏览器和 Java 不接触凭据。Gateway 提供同步回答、流式回答及 JSON 意图调用，统一 timeout、模型标识、usage、可用时的估算美元成本和脱敏失败。Embedding、ASR 不进入本 Gateway。

主 provider 必须显式配置 key。可选配置一个静态 fallback provider 与独立 key；只有主调用在尚未向客户端输出任何 token 时发生暂时性网络、超时、限流或 5xx 错误，才尝试一次 fallback。认证、参数、上下文长度、内容策略与无效输出不 fallback。fallback 不递归；流式输出一旦开始，失败即结束为错误，防止重复或拼接两家输出。工具意图解析失败返回无意图，绝不产生未经校验的写入。

## 配置与数据

保持 `AGENTFORGE_AGENT_LLM_PROVIDER`、`LLM_API_KEY`、`LLM_BASE_URL`、`LLM_MODEL`、`LLM_MAX_TOKENS`；增加对应 `LLM_FALLBACK_*`。`disabled` 仍是无密钥确定性模式。Provider 选择属于部署配置，不由用户输入、RAG 内容或另一个 LLM 决定。Gateway 只向观测系统记录 provider/model、token 数和可用时的成本；价格表未知或 SDK 返回零估算时成本保持 unknown，不写 0 伪装免费，也不记录 prompt、response 或 key。

## 失败、权限与测试

现有 Chat 同步入口的依赖失败仍是通用 503，流式入口仍发送通用 error 事件。上游异常正文、URL 查询和凭据不能进入公共响应或 Trace。测试从 `build_responder`/Responder 公共 seam、Agent HTTP/NDJSON seam 验证两家切换、JSON 意图、流式失败、fallback 次数、usage/cost 与脱敏；provider HTTP 用可控替身，不依赖真实付费 key。

## 限制与下一节点

V3-02 仅支持部署时选定的主模型及一个静态故障候选，不实现按 FORMAT/REWRITE/PLAN/REVIEW 任务路由；后者属于 V3-03。成本为可观测估算，不是账单或配额执行依据；兼容端点的非 OpenAI 模型若不在 LiteLLM 价格表中，成本保持 unknown。V3-02 关闭同一 provider 的自动重试，默认未配置 fallback 时暂时性故障会立即返回通用依赖错误，以避免隐性重复费用。LiteLLM SDK 作为进程内依赖，不部署 Proxy。
