# R12 实体消歧角色并发互斥

- 日期：2026-10-04
- 状态：完成
- 风险：L3（人工决策状态机与 PostgreSQL 并发一致性）
- 范围：Core API 实体消歧确认、真实 PostgreSQL/Neo4j 集成测试

## 问题核实

报告问题成立。`GraphResolutionDecisionService.confirm` 在事务内先分别查询成员是否已是规范锚点、目标是否已是成员，随后才写入两个独立表；V13 的表内主键和外键不能表达“同一项目实体不能同时出现在规范锚点角色和已确认成员角色”。两个相反确认可在没有共同数据库锁的事务中同时通过先查校验。

## 目标契约与公共 seam

公共 seam 保持 `PUT /api/v1/projects/{projectId}/graph/resolution/decisions/{entityId}`。一次确认必须在同一 PostgreSQL 事务中、按确定顺序取得成员与锚点的项目实体角色锁，然后重新执行既有角色、来源版本和 CAS 校验并写入。相反映射、共享中间实体或同一成员竞争不同目标时只能有一个互斥决策成功；另一方稳定返回 409，不能留下半条成员/规范/审计记录，也不能死锁。

锁只保护 PostgreSQL 中的人工规范映射角色；它不替代 Neo4j 项目写锁，也不宣称跨库原子事务。已有 API 请求/响应和稳定实体 ID 不变。

## TDD 与验证计划

1. 在真实 PostgreSQL/Neo4j HTTP 集成测试中，先从独立连接占用同一角色锁，证明两条相反确认都会等待；释放后断言恰好一个 200、一个 409。
2. 查询两端决策和审计表，证明只有一个已确认成员及一条确认审计，且没有实体同时承担两个角色。
3. 先取得旧实现红灯，再加入最小事务级 advisory lock 并取得绿灯。
4. 运行变更门禁、Core API clean verify、diff check、暂存差异 Gitleaks 和本批 Pi Milestone Review；最终回填证据。

## 验证回填

- TDD 红灯：新增相反确认并发测试先由独立连接占用预期角色锁；旧实现没有取得该锁，等待中的 advisory lock 数为 0，断言失败。
- 实现：确认事务按实体 UUID 字符串排序，对成员与规范目标取得项目级 `pg_advisory_xact_lock`，之后才执行既有角色、来源版本和 CAS 校验。不同请求对共享实体采用相同锁顺序，避免相反映射同时通过或形成死锁环。
- 绿灯：定向并发测试通过；最终 `GraphResolutionIntegrationTest` 15 tests 全通过，并由 Core `clean verify` 覆盖。
- 状态/审计断言：两条相反确认结果恰为 200/409；数据库只有 1 个 CONFIRMED 成员、1 条 CONFIRM audit，规范实体与已确认成员角色交集为 0。
- Pi：本批 Attempt 1 未对 R12 提出阻断项；Attempt 2 结论 PASS。锁前 canonical 读取顺序和测试锁观测范围仅为低风险建议，经逐条判断不改变正确性或隔离边界，本批不扩展修改。
