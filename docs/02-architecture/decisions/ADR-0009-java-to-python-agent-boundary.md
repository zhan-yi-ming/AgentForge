# ADR-0009：Java 作为 Agent Service 的调用与信任边界

- 状态：Accepted
- 日期：2026-09-04

## 决策

V1 由 Web 调用 Java Core API，Java 完成 JWT 与项目权限校验后同步调用 Python Agent Service。Python 暴露受内部 token 保护的接口，只负责概率性 graph 计算，不访问业务数据库。

## 原因与后果

该方向确保所有用户身份、项目访问和未来 tool 写入都经过同一 Java 边界；Web 不持有内部凭据，Python 不复制权限规则。同步 HTTP JSON 是 Day 3 最小可观察契约，失败统一映射为 503。Day 3 graph 无持久化，conversationId 仅作关联；V2 才评估 checkpoint、重试、熔断和完整 trace。
