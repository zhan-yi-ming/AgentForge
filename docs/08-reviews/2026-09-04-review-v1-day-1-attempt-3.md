# Pi 代码审查报告：v1-day-1 / Attempt 3

- 日期：2026-09-04
- 审查阶段：v1-day-1
- 审查对象：468f280d1d44d8bfb00e21c92831fee02d8479e4（基线：de08b54965a829bbc5bcb2625b5e630ecbc41c64）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- REVIEW_RESULT: NEEDS_FIX
- Pi 进程超时上限：300 秒

---

REVIEW_RESULT: NEEDS_FIX

# Pi 代码审查报告：AgentForge v1-day-1（轮次 3 / 3）

| 项目 | 内容 |
|---|---|
| 审查阶段 | v1-day-1 |
| 审查对象 | `468f280d1d44d8bfb00e21c92831fee02d8479e4` |
| 基线范围 | `de08b54965a829bbc5bcb2625b5e630ecbc41c64 .. 468f280d1d44d8bfb00e21c92831fee02d8479e4` |
| 审查模型 | DeepSeek V4-pro（只读 Reviewer） |
| 总体结论 | **需修复后交付**。上一轮阻塞项大多已关闭；但仍存在两个需要证据闭环的问题：数据库级乐观锁并发测试被环境跳过而无实际执行结果，以及 JWT secret 配置层强校验仍无可见实现证据。 |
| 本轮 diff | 截断采样，关键 Java 实现全文未完全展开；结论基于可见 diff、文件清单、上一轮报告及测试证据给出，无法确认之处均已标注。 |

---

## 详细发现清单

| ID | 严重级别 | 文件 & 行号（约） | 核心问题 |
|---|---|---|---|
| A3-1 | Medium | `PersistenceIntegrationTest.java`（本轮新增约 45 行）；`WikiPageService` / `TaskService` 并发路径 | Testcontainers 乐观锁并发测试因 Docker 不可用被自动跳过，未产出实际执行证据，数据库级 `@Version`/409 行为未被证明 |
| A3-2 | Low | `SecurityConfiguration` / `JwtProperties`（未出现在本轮文件清单）；`docs/03-features/authentication-and-authorization.md` 新增安全条款 | JWT secret 的 Base64 解码 + ≥32 字节配置层 fail-fast 校验仍无可见代码证据，上一轮已标记“拒绝”但本轮未提供实现位置 |

---

## 发现详情

### A3-1 — 数据库级乐观锁并发测试未实际执行，仅存在代码与 mock 证据

- **Severity**: Medium
- **File & Line**: `services/core-api/src/test/java/com/agentforge/core/PersistenceIntegrationTest.java`（新增约 45 行）；对应 `WikiPageService` / `TaskService` 的 update/delete 乐观锁路径
- **Evidence**:
  上一轮报告 R2-4 回填区记录：
  > 新增 PostgreSQL 双 EntityManager 旧快照提交测试，直接验证 `@Version`；当前机器 Docker 不可用，因此本轮 Maven 自动跳过该 Testcontainers 用例。

  本轮文件清单中确实出现了 `PersistenceIntegrationTest.java` 的新增，但 diff 截断，未看到测试方法体；同时也没有任何构建日志、回填记录或变更说明证明该测试在 Docker 可用环境实际运行过。
- **Description**:
  本轮已补充 `deleteRejectsStaleVersionWithoutDeleting` 等 Mockito 单元测试，证明了 Service 层会在版本不匹配时抛出 `ConflictException` 且不调用 repository.delete；这是正确且必要的方向。但单元测试通过 mock repository 验证行为，无法证明真实数据库中的原子条件更新（`@Version` 是否实际生效、两个并发事务通过旧快照提交时，后提交方是否确实收到 `OptimisticLockingFailureException` 并映射为 409，而不是静默覆盖）。

  “测试代码存在但从未实际执行”仍属于测试缺口。对于 V1 已承诺的版本校验和并发一致性，这属于交付前需要闭环的证据项，而不是可长期悬置的已知限制。
- **Suggested Fix**:
  1. 在具备 Docker 的环境执行：
     ```powershell
     mvn -pl services/core-api -Dtest=PersistenceIntegrationTest test
     ```
     并将通过日志或摘要回填到 `docs/08-reviews/` 本轮报告中。
  2. 若测试确实因环境条件无法在本地运行，请在变更记录中显式声明“该用例已在 CI/Docker 可用环境验证通过”并附相关 job 输出或结果文件，否则不得视为已关闭。
  3. 若实体未真实启用 `@Version`，则改为 SQL 条件更新方案并同步补集成测试；当前代码必须二选一，不能仅靠 mock 单测推断生产行为。

---

### A3-2 — JWT secret 配置层强校验仍无可见实现证据

- **Severity**: Low
- **File & Line**: `SecurityConfiguration` / `JwtProperties`（两者均未出现在本轮文件清单中）；`docs/03-features/authentication-and-authorization.md` 新增以下要求
- **Evidence**:
  本轮文档明确新增：
  > JWT secret 在配置绑定阶段必须能以标准 Base64 解码且不少于 32 字节；非法值必须让应用以可读错误快速启动失败。

  但本轮文件清单中没有 `SecurityConfiguration.java` 或 `JwtProperties.java` 的改动；diff 为截断采样，也无法看到相关代码。上一轮 R2-3 在 Codex 回填区标记为“拒绝”，依据是 `SecurityConfiguration.jwtSecretKey` 已使用标准 Base64 解码并要求至少 32 字节，但 Pi 因截断 diff 未看到该实现。本轮仍未提供可见实现片段或测试证据。
- **Description**:
  文档契约已更新，但缺少实现或测试层面的闭合证据。若该校验实际存在，应能通过一次启动级配置测试或代码片段快速确认；若不存在，非法/过短 secret 会在首次签发 token 或调用 HMAC 时才以难以理解的错误暴露，甚至可能产生 500，违反“fail-fast”安全契约。作为鉴权关键配置，应在交付前提供可核验的证明。
- **Suggested Fix**:
  1. 在回填区提供 `SecurityConfiguration.jwtSecretKey` 或 `JwtProperties` 构造器中“Base64 解码且长度 ≥ 32 字节”实现的位置和关键代码片段。
  2. 或新增一个 `@SpringBootTest(properties = ...)`/配置绑定测试，断言非法 Base64 与过短 secret 在启动阶段即失败。
  3. 若已有此类测试，只需回填测试名称与通过结果即可关闭，不需要修改代码。

---

## 上一轮发现状态核验

| 上一轮 ID | 本轮判断 | 说明 |
|---|---|---|
| R2-1 | **已关闭** | Wiki/Task 的 list/get/update/delete 路径已补充 `verify(projectAccess).requireAccess` 与拒绝后仓库零查询测试，可见 diff 充分 |
| R2-2 | **推断关闭** | `AuthenticatedActor.java` 有 6 行改动，`AuthenticatedActorTest.java` 新增约 28 行，方向与“null subject → 401”一致；因 diff 截断未看到具体实现，但证据链完整 |
| R2-3 | **未关闭** | 仍无可核验的 secret 配置层实现或测试证据，保留为本轮 A3-2 |
| R2-4 | **部分关闭** | 单元层 stale version 拒绝测试已补齐并可见；但数据库并发集成测试未实际执行，保留为本轮 A3-1 |

---

## 主开发 (Codex) 评估回填区

> 请 Codex 恢复后逐项评估。接受则给出修复方案并回到文档先行流程；拒绝则提供生产实现或测试证据，不得盲目照改。

| 发现 ID | Codex 结论（接受/部分接受/拒绝） | 修复计划或反驳理由 | 当前状态 |
|---|---|---|---|
| A3-1 | 接受 | 本机 Docker 不可用；CI 已增加 Surefire 断言，要求 PostgreSQL 集成测试 2 项、0 跳过，由 Pi 核验远端 job。 | 待 CI |
| A3-2 | 接受 | 新增 `JwtSecretKeyValidationTest`；Pi 实测 3 项、0 失败、0 跳过。 | 已关闭 |

---

## 审查结论

`NEEDS_FIX`。

本轮可见 diff 已关闭上一轮 R2-1 与 R2-2，单元层乐观锁语义也得到修正；但 A3-1（Testcontainers 并发测试未实际执行）与 A3-2（JWT secret 配置层强校验证据缺失）仍属于必须闭环的测试/安全证据项。两者不要求修改业务逻辑方向，只要求提供可核验的执行结果或实现位置。完成后即可转 PASS。
