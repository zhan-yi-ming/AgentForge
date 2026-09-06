# ADR-0016：Python 集中式 Langfuse fail-open 观测

- 状态：Accepted
- 日期：2026-09-06
- 决策者：项目维护者

## 背景

V2-01 要求把一次 Agent 请求的 Agent、Retrieval、LLM 与 Tool 调用组织成可关联 Trace，同时记录耗时、Token、结果与错误。现有 Java `request_id` 日志只能串联服务，不能表达 Python Agent 内部父子节点；若在 Java、Python 和各业务类分别直连 SDK，会扩大依赖方向、产生重复 Trace，并提高敏感字段泄漏与观测故障影响业务的风险。

## 备选方案

1. Java 与 Python 都直接接入 Langfuse：入口覆盖完整，但会形成两个 SDK 生命周期和跨服务 parent context 传播协议，V2-01 的复杂度与维护面过大。
2. 只使用 LangGraph/LangChain 自动 callback：接入快，但自定义 retrieval、deterministic tool planner、disabled responder 与异常闭合不完整，字段脱敏也难以集中约束。
3. Python 建立集中 adapter，并手工标注稳定业务节点：可以复用已有 `request_id`/`conversation_id`/`project_id`，明确字段白名单，且保持 Java 业务边界不变。

## 决策

选择方案 3。Python Agent Service 为每个通过内部认证的 Chat 创建根请求观测，并在其下创建 `agent`、`prepare`、`retriever`、`tool` 和 `generation` 类型观测。SDK 的当前上下文负责父子继承；`request_id`、`thread_id` 和 `project_id` 作为白名单 metadata/session 关联字段，不把 Langfuse trace id 变成业务主键或公共 API 契约。

adapter 只接受预定义的结构化摘要：模型 provider/name 与 Token 计数、检索来源数量、Tool 类型/是否产生 proposal/结果状态、异常类别。禁止发送用户 message、回答正文、检索正文、Tool 参数、认证信息、配置 secret 或原始异常文本。

Langfuse 默认关闭。启用需要 public key、secret key 和 host；配置 secret 使用 `SecretStr`。初始化、记录、flush 与网络发送均为非关键路径并 fail-open：允许写安全日志，但不得改变 Chat 返回、重试业务操作或泄漏异常正文。进程关闭时尽力 flush，短生命周期测试可显式 flush；不在 V2-01 部署 Langfuse Server。

## 结果

收益是基础链路的父子关系、延迟、Token 和错误可在一个平台查询，观测字段有集中安全边界，Java 确定性业务职责不变。代价是 Python 增加 Langfuse SDK 依赖与少量手工节点标注；fail-open 意味着观测平台故障时可能丢 Trace，因此业务日志中的 `request_id` 仍是兜底信号。跨服务 W3C Trace Context、持久化上下文、Eval 和 Dashboard 留给后续明确 Node，不在本决策中提前实现。

## 取代关系

无。补充 ADR-0009、ADR-0010、ADR-0011、ADR-0012 与 ADR-0014，不改变它们的 Java/Python、安全与流式边界。
