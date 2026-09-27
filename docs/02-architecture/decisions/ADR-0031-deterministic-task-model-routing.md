# ADR-0031：确定性任务模型路由

- 状态：Accepted
- 日期：2026-09-27

## Context
V3-02 统一调用，任务仍共用模型；V3-03 要求任务感知与可解释取舍。

## Decision
在 Responder 上方用固定规则分类和部署候选排序。复用 primary/fallback 凭据槽，声明任务、JSON/流式能力与相对成本/延迟/能力。每任务独立 Gateway，最多两个不同目标；输出前临时失败回退一次。无覆盖或无兼容能力失败关闭。LLM 不选择模型，HTTP 与 Java 权限边界不变。

## Alternatives
LLM 分类增加成本和不确定性；动态价格探测增加外部依赖；逐任务手写映射缺少统一取舍表达。

## Trade-offs
前缀识别覆盖有限；等级依赖运维校准，不保证实际省钱或提速。最多两个 provider 凭据槽，可支持同 provider 多模型。

## Consequences
不增加业务表。PLAN 使用 JSON object 与严格解析；原生 Tool Calling 未使用。图领域节点独立实施。
