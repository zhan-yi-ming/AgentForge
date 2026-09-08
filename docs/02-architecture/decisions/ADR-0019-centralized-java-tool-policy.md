# ADR-0019：Java 集中管理 Tool Policy 与风险授权

- 状态：Accepted
- 日期：2026-09-08
- 决策者：项目维护者

## 背景

现有 ProjectAccess 能阻止基础跨项目访问，Day 5 Action Service 也会重新校验 Task，但风险与角色要求散落在调用路径中。若由 Python proposal 或客户端携带 risk/role/approval，Prompt Injection 或字段篡改可能降低 Java 的执行保护。

## 备选方案

- 每个 Tool 内写权限 if/else：局部直观，但容易漂移和漏掉直接 API。
- Python 计算风险并传给 Java：便于 Agent 展示，但概率性边界不能成为授权事实。
- Java 以服务端 Tool 名称查询集中策略：需要统一动作词汇，但可让所有执行入口复用确定性规则。

## 决策

选择 Java 集中策略。不可变 Metadata 注册 `required_role`、`risk_level`、`need_approval`；Risk Engine 根据认证 actor、可信 Tool 名称和 ProjectAccess 判定。Agent proposal 只携带业务 Intent，客户端提供的策略字段不可信。直接 Java API 在对应应用服务入口调用同一策略。

## 结果

权限规则可统一审查和负向测试，Java 保持最终写权限。代价是新增策略注册表和动作映射；任何新增 Tool 必须先注册 Metadata。V2-06 在此决策上增加通用 Approval/Audit/Idempotency。

## 取代关系

扩展 ADR-0011，不取代其 Day 5 Action Intent 与确认写入边界。
