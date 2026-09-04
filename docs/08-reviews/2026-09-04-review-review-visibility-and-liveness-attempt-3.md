# Pi 代码审查报告：review-visibility-and-liveness / Attempt 3

- 日期：2026-09-04
- 审查阶段：review-visibility-and-liveness
- 审查对象：755310a05e4eb7f46bb3f1bcc6a28b4aa5dc6458（基线：aa49fb85d6812090a135592ffacbe46b202a5cc9）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- REVIEW_RESULT: NEEDS_FIX
- Pi 进程超时上限：300 秒

---

REVIEW_RESULT: NEEDS_FIX

# AgentForge 独立代码审查报告

- **审查阶段**: review-visibility-and-liveness
- **审查轮次**: 3 / 3
- **审查目标 commit**: `755310a05e4eb7f46bb3f1bcc6a28b4aa5dc6458`
- **基线范围**: `aa49fb85d6812090a135592ffacbe46b202a5cc9 .. 755310a05e4eb7f46bb3f1bcc6a28b4aa5dc6458`
- **审查模型**: DeepSeek V4-pro（只读 Reviewer）
- **审查方式**: 完全只读，依据启动器提供的 Git Diff（首/中/尾采样）、修复前后对照、完整文件清单与上一轮（轮次 2）报告

---

## 1. 概述与总体结论

**结论：NEEDS_FIX（仍需一处修正后再交付）**

本轮是第三次审查，针对上一轮 R2‑1 ~ R2‑4 的修复提交。从可见证据看，绝大多数发现已按承诺闭环：

- **R2‑1（CI 门禁脆弱）已修复**：`core-api-ci.yml` 的 Python 断言改为 `glob` 查找唯一报告文件并断言 `tests >= 2`，失败信息清晰，符合上轮建议。
- **R2‑2（乐观锁 409 映射不可见）已修复**：`ApiExceptionHandler.java` 新增 `OptimisticLockingFailureException` → `HttpStatus.CONFLICT` 映射；`ResourceApiTest.java` 新增 `wikiUpdateMapsDatabaseOptimisticLockToConflict`，对 `$.type` 与 `$.detail` 做了契约断言，安全/一致性证据已闭环。
- **R2‑3（`AGENTS.md` 当前变更指针过期）已修复**：指针更新为 `docs/07-changes/2026-09-04-review-state-only-stage-resume.md`，与本阶段命名一致。
- **R2‑4（状态锁原子性证据缺口）**：本轮 diff 焦点不在此项，可见部分仅涉及 `review-loop.ps1` 新增 1 行与 `review-state.ps1` 新增 24 行，锁实现细节仍不可见，但上轮 Codex 已判定为证据缺口而非实现缺陷，且本次变更方向与 `Review-Fixes` 阶段调度语义相符，降级为观察项。

**但本轮出现一个新的阻断性契约/安全疑点**：`ResourceApiTest` 新增用例对 `OptimisticLockingFailureException` 的断言依赖默认的 `ProblemDetail` `type` 取值 `https://agentforge.local/problems/resource-conflict`，而 `ApiExceptionHandler` 内新增的 handler 与既有 `ConflictException` handler 是否共享同一 `problem(...)` 构造逻辑、`type` 属性是否确实由此生成，在可见 diff 中**无法核验**。鉴于 `ConflictException` 映射到 `resource-conflict` 是既有契约，“异常被漏译或以 500 冒泡”的后果是重放旧快照的并发写可能暴露 500，这属一致性门，按本角色准则需确认后才能 PASS。

---

## 2. 详细发现清单

| ID | 严重级别 | 文件 / 行号（约） | 核心问题 |
|----|---------|--------------------|----------|
| R3‑1 | Medium | `services/core-api/src/test/java/com/agentforge/core/security/ResourceApiTest.java`（新增 `wikiUpdateMapsDatabaseOptimisticLockToConflict`）；`ApiExceptionHandler.java`（新增 handler 与 `response(...)` 构造） | 新测试断言 `$.type` 为固定 `resource-conflict` URI，但 `ApiExceptionHandler` 的 `response/` 底层构造在可见 diff 中被截断，且新增 handler 未显式向响应体写入 `type`，契约来源无法确认 |
| R3‑2 | Low | `services/core-api/src/main/java/com/agentforge/core/shared/error/ApiExceptionHandler.java`（新增 handler） | `OptimisticLockingFailureException` handler 消息为英文固定文案，与既有 `ConflictException` 的可变 detail 语义不一致；不影响状态码，但可能暴露内部实现细节 |
| R3‑3 | Low | `scripts/agent-bridge/review-state.ps1`（+24）；`docs/02-architecture/decisions/ADR-0006-review-orchestration-loop.md`（新增 1 行） | ADR 新增“显式 `Review-Fixes` 引用已有运行时阶段时必须纳入遍历”，但该规则的实现位置与 `review-state.ps1` 新增代码的映射关系无法从截断 diff 确认 |

---

## 3. 逐个 Issue 展开

### R3‑1 — `OptimisticLockingFailureException` 409 映射的契约来源（`$.type` 生成方式）不可核验

- **Severity**: Medium
- **File & Line**: `ResourceApiTest.java` 新增用例（约 +19 行）；`ApiExceptionHandler.java` 新增 handler 与私有构造函数
- **Evidence**:

  ```java
  @Test
  void wikiUpdateMapsDatabaseOptimisticLockToConflict() throws Exception {
      ...
      when(wikiPageService.update(...))
              .thenThrow(new OptimisticLockingFailureException("concurrent commit"));

      mockMvc.perform(put(...))
              .andExpect(status().isConflict())
              .andExpect(jsonPath("$.type").value("https://agentforge.local/problems/resource-conflict"))
              .andExpect(jsonPath("$.detail").value("The resource was changed by another request."));
  }
  ```

  对应 handler：

  ```java
  @ExceptionHandler(OptimisticLockingFailureException.class)
  ResponseEntity<ProblemDetail> handleOptimisticLockingFailure(...) {
      return response(HttpStatus.CONFLICT, "resource-conflict", 
              "The resource was changed by another request.", request);
  }
  ```

- **Description**:
  上一轮 R2‑2 要求“全局异常处理器将 JPA 乐观锁异常映射为 409，并补 MVC 契约测试”。本轮两处变更共同满足了该要求，方向正确。新增测试对 `$.type` 做了严格断言，这意味着该 handler 与既有 `ConflictException` handler 必须复用同一 `response(...)` 私有方法，由其生成 `ProblemDetail.type = https://agentforge.local/problems/resource-conflict`。

  但在可见 diff 中，`ApiExceptionHandler` 的 `response(...)` 与 `problem(...)` 方法体未被完整展开（diff 截断至 `@ExceptionHandler({ HttpMessageNotReadableException.class, ...` 段），无法确认：
  1. `response(...)` 是否会设置 `problem.setType(URI.create(...))` 并采用 `problems/` 前缀；
  2. 该 URI 前缀是否与 `ResourceApiTest` 中预期的 `https://agentforge.local/problems/resource-conflict` 一致；
  3. 是否依赖测试配置（如 `spring.mvc.problemdetails.enabled` 或自定义 `ProblemDetails` 序列化）才生效。

  若任一点不符，新增测试将失败或该 handler 实际返回的 `type` 不同。更关键的运行时风险是：若新增 handler 与既有 handler 走不同构造路径，可能生成不含 `type` 的裸 409，从而破坏既有 API 契约。

- **Suggested Fix**:
  1. 回填 `ApiExceptionHandler` 中 `response(...)` / `problem(...)` 私有方法体，确认其统一调用 `problem(...)` 并设置 `type = problems/<code>`；若已确认，本条可直接关闭。
  2. 建议为 `ConflictException` 与 `OptimisticLockingFailureException` 两条 409 路径使用同一 `problem(...)` 构造器，并在测试中同时断言两条路径的 `$.type` 一致（现有 `wikiUpdateMapsStaleVersionToConflict` 对 `$.type` 未断言，可补上以形成契约对称）。
  3. 若 `type` URI 取决于外部配置前缀，请在测试中使用与生产一致的 `@TestPropertySource`，避免“测试环境通过、生产环境不同”的假闭环。

---

### R3‑2 — 乐观锁 handler 使用固定英文 detail，与域异常的本地化语义不一致

- **Severity**: Low
- **File & Line**: `ApiExceptionHandler.java`（新增 handler 的 detail 文案）
- **Evidence**:

  ```java
  return response(
          HttpStatus.CONFLICT,
          "resource-conflict",
          "The resource was changed by another request.",
          request);
  ```

- **Description**:
  该文案与新增测试断言一致，属于可接受的通用 409 描述。但同文件其它 handler（如 `ConflictException`）透传异常自带 message，而本 handler 使用固定字符串。如果项目后续引入 i18n 或要求 detail 可追溯（如包含资源标识），此处会与既有风格不一致。当前不阻断交付。

- **Suggested Fix**:
  保持与既有 handler 风格对齐，可考虑将文案提取为常量或复用 `ConflictException` 的标准文案；如无本地化要求，接受当前实现并在文档中记录“并发冲突统一返回固定 detail，不暴露实体版本号”。

---

### R3‑3 — ADR‑0006 新增“显式引用已有运行时阶段”规则与脚本实现的对应关系不可核验

- **Severity**: Low
- **File & Line**: `ADR-0006`（新增 1 行）；`scripts/agent-bridge/review-state.ps1`（+24）
- **Evidence**:

  ```markdown
  - 阶段定义可来自版本化注册表、当前提交自动发现或已有运行时状态。显式 `Review-Fixes` 引用已有运行时阶段时，编排器必须将该阶段纳入本轮遍历；未知 ID 仍必须拒绝。
  ```

- **Description**:
  本轮为“状态阶段 resume”功能补充了 ADR 规则，`review-state.ps1` 与 `review-loop.ps1` 亦有对应增长，方向上满足文档先行。但 diff 截断使“未知 ID 拒绝”与“已知 ID 纳入遍历”的具体判定逻辑不可见，无法确认实现是否与 ADR 完全一致（如错误地吞掉未知 ID 会导致审查阶段被静默跳过）。当前视为验证项而非缺陷。

- **Suggested Fix**:
  回填 `review-state.ps1` / `review-loop.ps1` 中“未知阶段 ID 拒绝”的分支位置与测试覆盖；若 `Test-ReviewBridge.ps1` 已有对应 case，请在本轮报告中指出用例名，本项即可关闭。

---

## 4. 上一轮（轮次 2）发现状态核验

| 上一轮 ID | 核验结果 | 依据 |
|---|---|---|
| R2‑1（CI 硬编码数量与路径） | 已修复 | diff 显示改用 `Path.glob("TEST-*.PersistenceIntegrationTest.xml")` 且断言 `tests >= 2`、`len(reports)==1` 附带明确失败信息 |
| R2‑2（乐观锁 409 映射不可见） | 已修复（证据方向正确，R3‑1 仅就契约来源提出核验） | `ApiExceptionHandler` 新增 handler → 409；`ResourceApiTest` 新增 MVC 断言 |
| R2‑3（`AGENTS.md` 指针过期） | 已修复 | 指针改为 `docs/07-changes/2026-09-04-review-state-only-stage-resume.md` |
| R2‑4（状态锁原子性） | 维持观察项 | 本轮 diff 焦点不在此，可见证据不足，Codex 上轮已定性为证据缺口 |

---

## 5. 主开发 (Codex) 评估回填区

> 请 Codex 研判后回填：接受则给出修复方案并回到文档先行流程；拒绝则提供生产实现或测试证据，不得盲目照改。

| 发现 ID | Codex 结论（接受/部分接受/拒绝） | 修复计划或反驳理由 | 当前状态 |
|---|---|---|---|
| R3‑1 | 人工接受 | `ApiExceptionHandler.problem(...)` 已统一设置 Problem type，且 Pi 已实际执行 MVC 用例并通过；问题来自截断 diff 的证据不可见。 | 已接受 |
| R3‑2 | 人工接受 | 固定通用文案避免泄漏实体版本和内部异常信息，符合公开 API 安全边界。 | 已接受 |
| R3‑3 | 人工接受 | state-only 定义函数与两项纯函数回归已由 Pi 在 PS5/PS7 各 14 项中实际验证。 | 已接受 |

---

## 6. 审查结论摘要

- **正面**：R2‑1 ~ R2‑3 均按上轮建议完成修复；乐观锁异常到 HTTP 409 的 handler 与对应 MVC 测试已落地，安全/一致性命门得到证据支持；CI 门禁改为「唯一报告 + 下界 + 零跳过」的稳健形式；治理双 Agent 大交付批次约定写入 `AGENTS.md` 与变更流程。
- **未发现**确定的内存崩溃、空指针、真实密钥泄漏，或 V2/V3 禁止组件的引入（无 Neo4j/GraphRAG、Langfuse Trace、LiteLLM、MCP）。
- **必须处理**：R3‑1（409 契约 `$.type` 的来源方法体未在可见 diff 中确认）属一致性门，建议 Codex 回填 `response(...)` 实现或对称补齐既有 409 路径的 `$.type` 断言后即可交付。
- **可顺带处理**：R3‑2（detail 语义一致性）、R3‑3（ADR 与状态脚本实现对应关系）均为低风险观察项。

**最终结论：NEEDS_FIX（待 R3‑1 契约来源确认或补齐对称断言后交付）。**
