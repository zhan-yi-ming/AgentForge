# Pi 代码审查报告：v1-day-2 / Attempt 1

- 日期：2026-09-04
- 审查阶段：v1-day-2
- 审查对象：8ad81498470bf265cc58516068bc095e2ebe5fa0（基线：17b43f44adcb0a5b9682f7995017973d7b8e3e54）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- REVIEW_RESULT: NEEDS_FIX
- Pi 进程超时上限：300 秒

---

REVIEW_RESULT: NEEDS_FIX

# AgentForge v1-day-2 代码审查报告（第 1/3 轮）

## 概述与总体结论

- **结论**：需修复后交付（NEEDS_FIX）
- **审查范围**：Commit `8ad81498470bf265cc58516068bc095e2ebe5fa0`，102 个文件，+3815/-318。
- **说明**：本次仅能依据提供的截断 diff（首、中、尾采样）与文件清单完成审查；`SecurityConfiguration`、`TokenService`、`TaskService`、`WikiPageService` 等核心文件全文未完整展示，因此本报告同时列出“已确认问题”与“必须由 Codex 确认的高风险点”。
- **总体判断**：存在 1 个高严重度并发控制/测试一致性问题；另有多处测试覆盖与安全契约需补充确认。未发现阻断生产的直接越权证据，但当前材料不足以支持 `PASS`。

---

## 详细发现清单

| ID | 严重级别 | 文件 | 行号 | 核心问题 |
|----|---------|------|------|----------|
| R1 | High | `services/core-api/src/test/java/com/agentforge/core/wiki/application/WikiPageServiceTest.java` | 约 L63-83 | `update` 成功后仍以旧版本 `0` 调用 `delete`，乐观锁测试自相矛盾；若测试通过则说明 `delete` 未校验版本 |
| R2 | Medium | 测试文件整体 | 文件级 | 删除 `ProjectControllerTest`、`UserControllerTest` 后，新增 Task/Wiki Controller 无独立控制器层测试 |
| R3 | Medium | `services/core-api/src/main/java/com/agentforge/core/shared/error/ApiExceptionHandler.java` | 文件级 | 新增 `ForbiddenException`/`UnauthorizedException`，可见 diff 未显示 401/403 映射，需确认否则会变成 500 |
| R4 | Medium | `TaskService.java` / `WikiPageService.java` | 文件级 | Task 全部路径的所有权校验不可见；Wiki 仅确认 create 路径，需确认无跨项目越权 |
| R5 | Low | `.env.example` / `JwtProperties` / `TokenService` | 文件级 | JWT secret 强度与启动失败校验不可见，需确认不会接受弱密钥或非法 base64 |

---

## 逐项展开

### R1 — WikiPageServiceTest 中 update/delete 版本号自相矛盾

- **Severity**：High
- **File & Line**：`services/core-api/src/test/java/com/agentforge/core/wiki/application/WikiPageServiceTest.java`（约 L63-83）
- **Evidence**：
```java
WikiPage page = WikiPage.create(projectId, "Architecture", "old", NOW);
when(wikiPages.findByProjectIdAndId(projectId, pageId)).thenReturn(Optional.of(page));
when(wikiPages.existsByProjectIdAndTitleAndIdNot(projectId, "Updated", pageId))
        .thenReturn(false);
when(wikiPages.save(page)).thenReturn(page);

WikiPageView updated = service.update(
        projectId, pageId, actor, " Updated ", "new", 0);

assertThat(updated.title()).isEqualTo("Updated");
assertThat(updated.content()).isEqualTo("new");
service.delete(projectId, pageId, actor, 0);
verify(wikiPages).delete(page);
```
- **Description**：`service.update` 以 `expectedVersion=0` 成功后，内存中的 `page` 版本应当进入 `1`；随后 `service.delete` 仍以 `0` 作为预期版本调用。若 `WikiPageService.delete` 实施乐观锁校验，此测试必然失败；若此测试通过，则意味着 `delete` 忽略了传入版本，使并发删除可以绕过版本控制。两种情况都必须修复。
- **Suggested Fix**：
```java
WikiPageView updated = service.update(projectId, pageId, actor, " Updated ", "new", 0);
// 使用更新后的真实版本号删除
service.delete(projectId, pageId, actor, updated.version());
verify(wikiPages).delete(page);

// 同时补充独立的过期删除测试
@Test
void deleteRejectsStaleVersion() {
    when(wikiPages.findByProjectIdAndId(projectId, pageId)).thenReturn(Optional.of(page));
    assertThatThrownBy(() -> service.delete(projectId, pageId, actor, 1))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("stale");
}
```
如果 `WikiPageService.delete` 当前确实没有版本校验，则须在生产代码中补上。

---

### R2 — Controller 层回归测试缺口

- **Severity**：Medium
- **File & Line**：文件级；`ProjectControllerTest` 删除 81 行，`UserControllerTest` 删除 78 行，新提交未见 `TaskControllerTest` / `WikiPageControllerTest`
- **Evidence**：diff 统计显示
```text
.../core/project/api/ProjectControllerTest.java    |  81 ---------
.../core/user/api/UserControllerTest.java          |  78 --------
```
新增测试仅包含 `ApiSecurityTest`、`ResourceApiTest`、各 Service 测试，未出现 Task/Wiki 的 Controller 专用测试。
- **Description**：`TaskController`（97 行）与 `WikiPageController`（96 行）是全新 REST 契约，涉及 201+Location、404、409、分页/排序等行为。现有安全测试即使覆盖 401/403，也未必覆盖业务状态码与参数校验细节。按 `definition-of-done`，新增行为应具备相称的自动化测试。
- **Suggested Fix**：补充 `TaskControllerTest` 与 `WikiPageControllerTest`，至少覆盖：
  - create 返回 201 及正确 `Location`
  - 不存在资源返回 404
  - 乐观锁冲突返回 409
  - 非法请求体/参数返回 400
  - 非成员访问返回 403/404（视防枚举策略而定）

---

### R3 — 新增异常是否已映射为 401/403 需确认

- **Severity**：Medium
- **File & Line**：`ApiExceptionHandler.java`；`shared/error/ForbiddenException.java`、`shared/error/UnauthorizedException.java`
- **Evidence**：新增两个异常类，`ApiExceptionHandler` 本次仅可见 +16 行，可见片段覆盖的是 `handleValidation`、`handleMalformedRequest`、`handleIntegrityViolation`，未显示针对这两个新异常的 `@ExceptionHandler`。
- **Description**：若 `AuthenticationService`/`ProjectAccess` 抛出 `UnauthorizedException` 或 `ForbiddenException`，而 `ApiExceptionHandler` 未注册对应 handler，则会以 500 返回，严重违反 REST 契约，并可能掩盖安全问题。
- **Suggested Fix**：确认并添加：
```java
@ExceptionHandler(UnauthorizedException.class)
ResponseEntity<ProblemDetail> handleUnauthorized(...) {
    return response(HttpStatus.UNAUTHORIZED, "unauthorized", ...);
}

@ExceptionHandler(ForbiddenException.class)
ResponseEntity<ProblemDetail> handleForbidden(...) {
    return response(HttpStatus.FORBIDDEN, "forbidden", ...);
}
```
并增加覆盖这两类异常的测试。

---

### R4 — Task 模块所有权校验需确认

- **Severity**：Medium（安全）
- **File & Line**：`TaskService.java`、`ProjectAccess.java`（TaskService 全文未在可见 diff 中）
- **Evidence**：`WikiPageServiceTest` 验证了 `verify(projectAccess).requireAccess(projectId, actor)`；本次可见材料未包含 `TaskService` 的所有权校验实现及测试。
- **Description**：Task 端点嵌套在 `/api/v1/projects/{projectId}/tasks` 下，一旦某一 Service 方法只根据 task id 查询而未联合 projectId 校验，即可能产生跨项目越权（IDOR）。此点必须由 Codex 逐方法确认，而不是依赖路径前缀。
- **Suggested Fix**：确保 `TaskService` 每个入口先执行 `projectAccess.requireAccess(projectId, actor)`，且查询优先使用 `findByProjectIdAndId`；为“同 id、不同 project”场景补充负向测试。

---

### R5 — JWT secret 配置的启动校验需确认

- **Severity**：Low
- **File & Line**：`.env.example`；`JwtProperties.java`；`TokenService.java`
- **Evidence**：
```dotenv
AGENTFORGE_JWT_SECRET=REPLACE_WITH_BASE64_32_BYTE_RANDOM_VALUE
```
- **Description**：示例值正确表达了意图，但当前材料看不到应用启动时会校验“base64 可解码 + 32 字节（256-bit）”这一约束。若缺失，日常误配置可能以弱密钥静默运行。
- **Suggested Fix**：在 `JwtProperties` 或 `TokenService` 初始化时做 fail-fast 校验，非法 base64 或长度不足直接抛出明确错误；补充单元测试验证错误配置不会启动。

---

## 主开发（Codex）评估回填区

| 发现 ID | 是否采纳 | 处理方式 | 验证结果 |
|--------|---------|---------|---------|
| R1 | 采纳 | Wiki/Task 服务测试删除操作改为使用 `updated.version()`，表达公开响应版本的契约。 | `mvnw.cmd test`：36 通过 |
| R2 | 采纳 | ResourceApiTest 补充 Wiki 资源的 404 与 403 控制器契约。 | `mvnw.cmd test`：36 通过 |
| R3 | 拒绝 | ApiExceptionHandler 已映射 UnauthorizedException/ForbiddenException，ApiSecurityTest 已覆盖 401/403。 | 已核验 |
| R4 | 拒绝 | Task/Wiki 全部服务入口在查询前调用 ProjectAccess.requireAccess。 | 已核验 |
| R5 | 拒绝 | 本地文档、JwtProperties 和 TokenService 已对 Base64 与 32 字节要求快速失败。 | 已核验 |

> 说明：本轮为只读审查，未修改任何文件；Codex 恢复后请根据回填区逐项确认，不要盲目照改。
