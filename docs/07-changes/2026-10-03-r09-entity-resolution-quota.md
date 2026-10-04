# R09 实体消歧模型建议纳入 AI 日配额

- 日期：2026-10-03
- 状态：Completed
- 风险：L3（费用保护与公共 API 错误语义）
- 影响域：Core API、Entity Resolution、Docs

## 问题确认

深层审查 R09 成立。当前 `POST /graph/resolution/suggestions` 在找到候选后直接调用 Python advisor，没有经过 `AiUsageQuota`；已耗尽 Chat/ASR 日额度的认证用户仍可重复触发模型建议。

## 目标与边界

- 继续先完成项目授权、实体读取与候选发现；没有候选时返回空建议，不消耗额度，也不调用 advisor。
- 只要存在将送往 advisor 的候选，每个公开建议请求在调用 Python 前原子消费一次现有用户 AI 日额度；不是按候选数扣费。
- 额度耗尽返回既有 429，且不能被 advisor 的 fail-open 降级吞掉；未认证请求在进入服务前返回 401，不扣额度。
- Python 的建议失败仍降级为人工审阅；本次不改变人工确认、Graph 数据或每日额度表结构。

## 约定测试 seam

公共 seam 为 Bearer 保护的 `POST /api/v1/projects/{projectId}/graph/resolution/suggestions`。测试覆盖候选存在时单次扣额、额度耗尽时不调用 advisor、无候选不扣额、未认证不扣额。

## 验证计划

先在 Graph HTTP 集成测试加入上述断言并取得红灯，再做最小实现；随后运行定向测试、Core 相称回归、门禁规划、diff/敏感扫描与本批 Pi Milestone Review。

## 验证回填

- TDD 红灯：`services/core-api/.\\mvnw.cmd -Dtest=GraphResolutionIntegrationTest test`，14 项中 3 项按预期失败：额度耗尽仍返回 200、空候选仍调用 advisor、候选请求未消费额度。
- 最小实现后同一命令通过：14 tests，0 failures/errors/skipped。
- Core 全量：Pi 处置后的 `services/core-api/.\\mvnw.cmd clean verify` 通过，238 tests，0 failures/errors，12 skipped；保留 Mockito 动态 agent 与测试容器关闭阶段的既有警告。
- Java↔Python 跨进程门禁：`v3-release-regression.ps1 -Only java-python-contracts` 通过，13/13，覆盖真实 Chat、Resume、实体消歧与 GraphRAG 契约。
- `plan-change-gates.ps1 -BaseRef HEAD -TargetRef WORKTREE -Json` 判定本批为 L3 / Milestone Review；`git diff --check` 通过。
- Pi Attempt 1：R09 单项 PASS；确认候选、空候选、额度耗尽与未认证四个分支正确，无需修改。
- Pi Attempt 2：本批 Milestone Review `PASS`，无必须修改项；R09 保持通过。

## 风险与回滚

风险是建议请求比历史多消耗一次额度，或错误地对空候选扣费。回滚仅撤销该调用与对应文档/测试，不修改已有额度计数或实体映射。
