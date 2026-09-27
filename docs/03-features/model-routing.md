# Model Gateway 与确定性任务路由

- 状态：Implemented（V3-02 / V3-03；Milestone Review PASS）
- 阶段：V3-02 / V3-03
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

## V3-03 任务路由（Implemented）

`AGENTFORGE_AGENT_LLM_ROUTES` 为候选 JSON 数组。字段：name、endpoint（primary/fallback 凭据槽）、model、tasks（FORMAT/REWRITE/PLAN/REVIEW/ANSWER）、json_output、streaming、cost_rank、latency_rank、capability_rank。等级为部署者校准的 1–100 相对值；成本/延迟越低越好，能力越高越好，不是实际账单或在线测量。凭据留在已有 LLM_API_KEY / LLM_FALLBACK_API_KEY，不进入路由 JSON。

当前用户消息去除首尾空白后，FORMAT: 或“请将以下内容整理为 Markdown”开头进入 FORMAT；REWRITE: /“请改写”/“请润色”进入 REWRITE；REVIEW: /“请评审”/“请审查”进入 REVIEW；PLAN: /“请制定计划”进入 PLAN；其余 ANSWER。Tool 意图入口固定 PLAN。检索与历史不参与分类，分类不提供权限。

排序键：FORMAT=(成本,延迟,-能力,名称)，REWRITE=(延迟,成本,-能力,名称)，PLAN/REVIEW/ANSWER=(-能力,成本,延迟,名称)。按 provider/model 去重，取两个不同目标作主备，至多输出前临时失败回退一次；认证错误和无效输出不回退。所有任务须有候选，所有回答候选须支持流式，PLAN 须声明 JSON object；所有声明候选的凭据与目标均预校验；无覆盖或能力不兼容首次请求失败关闭。自然语言计划回答仍是文本；仅 PLAN 的 Tool 意图调用使用 JSON object 加严格 Intent 校验，不使用原生 Tool Calling。

未配置路由保留 V3-02；disabled 不允许启用路由。能力声明需部署者用真实厂商验证。rank 不承诺实际省钱或提速。usage/cost 记录实际成功模型，未知价格保持 unknown；失败调用可能产生费用但不返回 usage，不可将 Trace 当作总账。单 provider timeout 不变，主备最多两次 timeout，外层还须覆盖 Planning 和回答。

Trace generation 沿用既有计时，新增 task_type、route 和 rank；实际 provider/model/usage/cost 沿用 Gateway 观测。Tool 意图仍遵守原有脱敏与严格解析行为。

### 示例：同 provider 两个模型

```json
[
  {"name":"economy","endpoint":"primary","model":"economy-model","tasks":["FORMAT","REWRITE","PLAN","REVIEW","ANSWER"],"json_output":true,"streaming":true,"cost_rank":1,"latency_rank":1,"capability_rank":30},
  {"name":"reasoning","endpoint":"primary","model":"reasoning-model","tasks":["FORMAT","REWRITE","PLAN","REVIEW","ANSWER"],"json_output":true,"streaming":true,"cost_rank":5,"latency_rank":5,"capability_rank":90}
]
```

模型名是示意值，替换成实际部署可用且支持 JSON/streaming 的模型；候选支持两个 provider 凭据槽中的任意多个模型。新增 provider 不改变业务代码。

### 契约与观测边界

Web `apps/web/src/App.tsx` 的 FORMAT_PROMPT_PREFIX 是整理入口的显式路由契约：`请将以下内容整理为 Markdown，保留事实，使用一个明确的一级标题，不执行写入：\n\n`。修改该前缀或 Python 分类规则时必须同步调用方、功能文档和对应 Responder 测试；当前无需 HTTP 字段变更。

`task_type/route/rank` metadata 只覆盖同步/流式回答 generation；既有 `plan_tool(bundle)` 不接收 observation，Tool 意图仍走 PLAN 路由，但未提供单独的路由/token/cost Trace。`route` 表示初始路由决策，实际成功调用由 provider/model/usage/cost 表示，回退不改写初始决策名。不能将这些数据视作所有模型调用的完整账单；V3-09 集成验收再评估规划观测覆盖。

紧急切回确定性模式须同时设置 `LLM_PROVIDER=disabled`、`LLM_ROUTES=[]` 并清空 `LLM_FALLBACK_PROVIDER`（环境变量均带 AGENTFORGE_AGENT_ 前缀）；否则配置失败关闭，返回通用依赖错误。
