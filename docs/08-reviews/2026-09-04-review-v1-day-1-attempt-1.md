# Pi 代码审查报告：v1-day-1 / Attempt 1

- 日期：2026-09-04
- 审查阶段：v1-day-1
- 审查对象：17b43f44adcb0a5b9682f7995017973d7b8e3e54（基线：&lt;root&gt;）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- REVIEW_RESULT: NEEDS_FIX
- Pi 进程超时上限：300 秒

---

REVIEW_RESULT: NEEDS_FIX

# AgentForge v1-day-1 代码审查报告（轮次 1/3）

| 项目 | 内容 |
|---|---|
| 审查对象 | `17b43f44adcb0a5b9682f7995017973d7b8e3e54` |
| 审查范围 | 102 个改动文件，按 diff 首/中/尾采样 + 文件清单审查 |
| 审查模型 | DeepSeek V4-pro（只读 Reviewer） |
| 总体结论 | **需修复后交付**。发现并发乐观锁原子性、Service 层越权校验证据不足两个高风险问题，以及审查循环工具脚本缺失等中低风险问题。 |

> 说明：本次 diff 为截断采样，未能逐文件核验完整实现。涉及 `SecurityConfiguration`、`TaskService`、`WikiPageService` 等关键文件的全文未完全展开，下文行号对可确认范围给出，对截断区域以测试证据推断，并已在发现中标注。

---

## 详细发现清单

| ID | 严重级别 | 文件 & 行号（约） | 核心问题 |
|---|---|---|---|
| F1 | High | `services/core-api/src/main/java/com/agentforge/core/wiki/application/WikiPageService.java` update/delete；`services/core-api/src/test/java/com/agentforge/core/wiki/application/WikiPageServiceTest.java` L73-105 | 版本校验为 check-then-act，非原子乐观锁；并发写会丢更新；且 update 后 delete 仍传 `expectedVersion=0`，版本语义自相矛盾 |
| F2 | High | `WikiPageService.java` 与 `TaskService.java` 的 list/get/update/delete；`WikiPageServiceTest.java` L108-125 | Service 层项目访问权（`ProjectAccess.requireAccess`）在 list/get/update/delete 路径未证明强制校验，存在跨项目资源访问风险 |
| F3 | Medium | `AGENTS.md` “审查循环触发规则”段落 | 引用不存在的 `scripts/agent-bridge/review-loop.ps1`，强制唤醒审查循环无法执行 |
| F4 | Medium | `.env.example` L12-14；`README.md` 快速开始；`docs/05-development/local-development.md` | 本地 JWT secret 生成命令与格式校验缺失，易导致启动失败或使用弱密钥 |
| F5 | Low | `SecurityConfiguration.java` / `AuthenticationController` 相关（diff 截断未展示） | 401/403 契约、JWT `issuer/audience` 校验与配置缺失行为未在可见证据中确认，需补充验证用例 |

---

## 发现详情

### F1 — 并发乐观锁非原子，且测试暴露版本语义不一致

- **Severity**: High
- **File & Line**: `WikiPageService.java` update/delete；`WikiPageServiceTest.java` L73-105（可见 diff 尾部）
- **Evidence**:
```java
// WikiPageServiceTest.updateRejectsStaleVersion
when(wikiPages.findByProjectIdAndId(projectId, pageId)).thenReturn(Optional.of(page));
assertThatThrownBy(() -> service.update(
        projectId, pageId, new AuthenticatedActor(...), "Architecture", "new", 1))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("stale");

// WikiPageServiceTest.updateAndDeleteUseCurrentVersion
WikiPageView updated = service.update(projectId, pageId, actor, " Updated ", "new", 0);
...
service.delete(projectId, pageId, actor, 0);
verify(wikiPages).delete(page);
```
- **Description**:
  当前实现先读取实体、比较 `version == expectedVersion`，再保存 `version + 1`。这是典型的“检查后执行”，没有数据库原子条件更新或 `@Version` 锁。
  并发场景下，两个请求同时以 `expectedVersion=0` 读取到 `version=0`，都能通过校验，后写的请求静默覆盖前写结果，相当于丢失更新。
  另外，测试 `updateAndDeleteUseCurrentVersion` 中先用 `expectedVersion=0` 更新，随后仍以 `expectedVersion=0` 删除；若 update 确实将版本递增为 `1`，则 delete 应抛 `409 Conflict`。该测试能够通过，说明要么 update 未持久化递增后的版本，要么 delete 根本没有执行版本校验。两种情况都破坏了 REST 乐观锁契约。
- **Suggested Fix**:
  1. 在 `WikiPage`/`TaskItem` 数据库表上建立 `version` 列，并使用 `@Version` 或等价原子条件更新：
     ```sql
     UPDATE wiki_page
        SET version = version + 1, title = ?, content = ?
      WHERE id = ? AND project_id = ? AND version = ?;
     ```
     更新返回 0 行时抛 `ConflictException("stale version")`。
  2. 修正测试：更新后使用 `updated.version()` 作为 delete 的 `expectedVersion`，并补充两个并发请求的集成测试，证明后写请求会收到 409 而不是静默覆盖。
  3. 若 V1 明确放弃并发防护，必须在变更记录中把“非原子版本校验”列为已知限制，不能作为已完成的防御能力。

---

### F2 — Wiki/Task 的 list/get/update/delete 访问权校验证据不足

- **Severity**: High
- **File & Line**: `WikiPageService.java`、`TaskService.java` 的 list/get/update/delete；`WikiPageServiceTest.java` L108-125
- **Evidence**:
```java
// WikiPageServiceTest.getRequiresResourceToBelongToPathProject
when(wikiPages.findByProjectIdAndId(projectId, pageId)).thenReturn(Optional.empty());
assertThatThrownBy(() -> service.get(projectId, pageId, actor))
        .isInstanceOf(ResourceNotFoundException.class);

// WikiPageServiceTest.listReturnsRepositoryOrder
when(wikiPages.findAllByProjectIdOrderByUpdatedAtDesc(projectId))
        .thenReturn(List.of(first, second));
assertThat(service.list(projectId, actor))
        .extracting(WikiPageView::title)
        .containsExactly("First", "Second");
```
- **Description**:
  可见测试只证明了“资源 ID 必须属于路径中的 projectId”（`findByProjectIdAndId`），但没有验证当前 actor 是否对该 project 有访问权。`create` 测试显式 `verify(projectAccess).requireAccess(...)`，而 `get/list/update/delete` 测试均未校验这一调用；`list` 测试甚至直接按 projectId 返回仓库顺序结果。
  若实际代码在这些路径漏调 `ProjectAccess.requireAccess`，任何持有合法 JWT 的用户只要能枚举/猜测 project UUID，就可能读取、修改或删除不属于自己的任务/Wiki 页面，属于典型 IDOR。
- **Suggested Fix**:
  1. 所有服务方法在触及任何仓库查询前，必须执行：
     ```java
     projectAccess.requireAccess(projectId, actor);
     ```
     对读接口建议资源不存在时统一返回 404，避免泄露资源存在性。
  2. 在每个正向测试中增加：
     ```java
     verify(projectAccess).requireAccess(projectId, actor);
     ```
     并补充负向测试：
     ```java
     doThrow(new ForbiddenException("access denied"))
         .when(projectAccess).requireAccess(projectId, actor);
     assertThatThrownBy(() -> service.list(projectId, actor))
         .isInstanceOf(ForbiddenException.class);
     ```
  3. 同时检查 `TaskService` 是否复用了相同的安全模式；当前 `TaskServiceTest` 的可见样本不足。

---

### F3 — AGENTS.md 引用不存在的审查循环脚本

- **Severity**: Medium
- **File & Line**: `AGENTS.md` “审查循环触发规则”段落
- **Evidence**:
```markdown
- Codex 每次收到用户消息、从额度恢复或开始新的执行回合时，必须先运行
  `scripts/agent-bridge/review-loop.ps1 -OnCodexWake`，再决定是否进行业务开发。
```
  但本 commit 的改动文件清单中仅包含：
  - `scripts/agent-bridge/bridge-monitor.ps1`
  - `scripts/agent-bridge/run-review.ps1`
  - `scripts/agent-bridge/prompts/stage-review-system.md`
  未包含 `review-loop.ps1`。
- **Description**:
  该强制规则指向一个不存在的脚本。Codex 按文档执行时会直接失败，导致审查循环无法启动；如果该脚本已在其他提交中存在，必须在文档中标注来源或依赖版本，否则交付不可复现。
- **Suggested Fix**:
  1. 补充 `scripts/agent-bridge/review-loop.ps1` 到本 commit。
  2. 或改为引用现有入口 `bridge-monitor.ps1` / `run-review.ps1`，并明确参数与行为：
     ```markdown
     - Codex 先运行 `scripts/agent-bridge/run-review.ps1 -OnCodexWake`
     ```
  3. 在 `scripts/agent-bridge/README.md` 列出所有脚本的入口关系，避免再次出现悬空引用。

---

### F4 — 本地 JWT secret 生成命令与格式校验缺失

- **Severity**: Medium
- **File & Line**: `.env.example` L12-14；`README.md` “本地快速开始”；`docs/05-development/local-development.md`
- **Evidence**:
```text
AGENTFORGE_JWT_SECRET=REPLACE_WITH_BASE64_32_BYTE_RANDOM_VALUE
AGENTFORGE_JWT_ISSUER=https://agentforge.local/core-api
AGENTFORGE_JWT_TTL=PT30M
```
  README 仅说明“生成独立的本地 JWT secret”，未给出具体命令。
- **Description**:
  `AGENTFORGE_JWT_SECRET` 的占位值要求“Base64 32 字节随机值”。开发者若直接填入 32 个 ASCII 字符或用非 Base64 字符串，应用启动时可能因密钥解码失败或长度不足而崩溃，且错误信息未知。
- **Suggested Fix**:
  1. 在 `local-development.md` 与 README 给出明确命令：
     ```bash
     openssl rand -base64 32
     ```
  2. 在 `JwtProperties` 或配置绑定处增加 fail-fast 校验：
     - 必须能够 Base64 解码；
     - 解码后长度 ≥ 32 字节；
     - 为空时给出“请先执行生成命令”的可读错误，而不是 NPE。
  3. 增加一个启动级别的配置测试，验证非法 secret 会导致明确失败。

---

### F5 — 401/403 与 JWT 配置校验证据不足

- **Severity**: Low
- **File & Line**: `SecurityConfiguration.java`、`ApiExceptionHandler` 相关（diff 截断未展示全文）
- **Evidence**:
  diff 中仅可见 `ForbiddenException`、`UnauthorizedException` 为新建简单异常类，`ApiExceptionHandler` 增加了 `MissingServletRequestParameterException` 处理；`ApiSecurityTest`、`ResourceApiTest` 已新增但正文未在采样中展开。
- **Description**:
  作为安全关键配置，无法从当前证据确认：
  1. 未配置 JWT `issuer/audience` 校验时是否 fail-fast；
  2. 认证失败统一返回 401、授权失败统一返回 403，且 body 不含敏感堆栈；
  3. `/api/v1/auth/**` 的 permitAll 范围是否过宽。
- **Suggested Fix**:
  补充或确认以下测试：
  1. 无 token / 坏 token / 过期 token 分别返回 401；
  2. 普通 user 访问需要 admin 的接口返回 403；
  3. 非法 JWT secret 启动失败测试（与 F4 合并验证）。
  不要求修改产品行为，但需要在变更记录中回填这些验证结果。

---

## 主开发 (Codex) 评估回填区

| ID | Codex 结论（接受/部分接受/拒绝） | 修复计划或反驳理由 | 当前状态 |
|---|---|---|---|
| F1 | 拒绝 | Day 2 已为 WikiPage/TaskItem 声明 `@Version`，并由基础设施适配器 `saveAndFlush`，应用服务捕获 `OptimisticLockingFailureException` 转为 409；历史 Mockito 测试不模拟 JPA 的版本递增，因此不能反证生产行为。 | 已核验 |
| F2 | 拒绝 | 当前 WikiPageService 与 TaskService 的 create/get/list/update/delete 均在仓库查询前调用 `ProjectAccess.requireAccess`；TaskService 的负向测试还验证了权限校验先于仓库查询。 | 已核验 |
| F3 | 接受 | 历史 Day 1 提交确无该脚本；本次变更已新增 `review-loop.ps1`、状态机和脚本入口说明。 | 已修复，待本次交付复审 |
| F4 | 拒绝 | `local-development.md` 已提供 PowerShell 生成命令，并明确说明服务会对缺失、非法 Base64 或少于 32 字节的 secret 快速失败。 | 已核验 |
| F5 | 部分接受 | `ApiSecurityTest` 已验证无 Bearer 为 401、业务所有权失败为 403，资源接口也覆盖 409；JWT 配置验证仍会在 Day 2 的独立审查中再次检查。 | Day 2 待审 |

> 回填要求：Codex 恢复后逐项评估，不得盲目照改；接受则记录修复方案并回到文档先行流程，拒绝则提供证据。

---

**审查结论**：`NEEDS_FIX`。F1、F2 属于高风险缺陷，F3 会阻塞审查循环，均应在当前阶段交付前处理或至少由 Codex 明确给出可接受的已知限制与后续修复计划。
