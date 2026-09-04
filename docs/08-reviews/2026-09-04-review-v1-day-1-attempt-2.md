# Pi 代码审查报告：v1-day-1 / Attempt 2

- 日期：2026-09-04
- 审查阶段：v1-day-1
- 审查对象：de08b54965a829bbc5bcb2625b5e630ecbc41c64（基线：17b43f44adcb0a5b9682f7995017973d7b8e3e54）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- REVIEW_RESULT: NEEDS_FIX
- Pi 进程超时上限：600 秒

---

REVIEW_RESULT: NEEDS_FIX

# AgentForge v1-day-1 代码审查报告（轮次 2 / 3）

| 项目 | 内容 |
|---|---|
| 审查对象 | `de08b54965a829bbc5bcb2625b5e630ecbc41c64` |
| 基线范围 | `17b43f44adcb0a5b9682f7995017973d7b8e3e54 .. de08b54965a829bbc5bcb2625b5e630ecbc41c64` |
| 审查模型 | DeepSeek V4-pro（只读 Reviewer） |
| 总体结论 | **需修复后交付**。上一轮 F3 已确认修复；F1 版本号使用方式已修正，但并发原子性仍无测试证据；F2 权限校验在 list/get/update/delete 路径仍未被测试证明；另发现 JWT subject 缺失导致 500 的低风险缺陷。 |

> 说明：本次 diff 为截断采样，`SecurityConfiguration`、`TokenService`、`WikiPageService`、`TaskService` 等关键实现全文未完全展开。结论基于可见 diff、文件清单及测试证据得出，对无法确认之处均已标注。

---

## 详细发现清单

| ID | 严重级别 | 文件 & 行号（约） | 核心问题 |
|---|---|---|---|
| R2-1 | Medium | `WikiPageServiceTest.java` L108-129；对应的 `WikiPageService` list/get/update/delete | list/get/update/delete 测试均未 `verify(projectAccess).requireAccess`，无法证明 Service 层所有权强校验，跨项目访问风险未关闭 |
| R2-2 | Low | `AuthenticatedActor.java` L12-19 | `jwt.getSubject()` 为 null 时 `UUID.fromString(null)` 抛 NPE，绕过 UnauthorizedException，导致 500 而非 401 |
| R2-3 | Medium | `JwtProperties.java`（可见部分）；`.env.example` L12-14 | JWT secret 仅 `@NotBlank`，未见“Base64 解码 + ≥32 字节”的 fail-fast 校验；占位值本身非标准 Base64，易导致启动失败 |
| R2-4 | Medium | `WikiPageService` update/delete；`WikiPageServiceTest.java` L89-107 | 乐观锁版本号使用已修正为 `updated.version()`，但仍只有 Mockito 单测，无真实数据库并发/409 集成测试证明原子性 |

---

## 发现详情

### R2-1 — list/get/update/delete 路径的权限校验测试证据不足

- **Severity**: Medium
- **File & Line**: `services/core-api/src/test/java/com/agentforge/core/wiki/application/WikiPageServiceTest.java` L108-129
- **Evidence**:
```java
@Test
void getRequiresResourceToBelongToPathProject() {
    UUID projectId = UUID.randomUUID();
    UUID pageId = UUID.randomUUID();
    when(wikiPages.findByProjectIdAndId(projectId, pageId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.get(
            projectId,
            pageId,
            new AuthenticatedActor(UUID.randomUUID(), false)))
            .isInstanceOf(ResourceNotFoundException.class);
}

@Test
void listReturnsRepositoryOrder() {
    UUID projectId = UUID.randomUUID();
    WikiPage first = WikiPage.create(projectId, "First", "one", NOW);
    WikiPage second = WikiPage.create(projectId, "Second", "two", NOW.minusSeconds(60));
    when(wikiPages.findAllByProjectIdOrderByUpdatedAtDesc(projectId)).thenReturn(List.of(first, second));

    assertThat(service.list(projectId, new AuthenticatedActor(UUID.randomUUID(), true)))
            .extracting(WikiPageView::title)
            .containsExactly("First", "Second");
}
```
- **Description**:
  这两个测试只证明了“资源 ID 必须属于路径中的 projectId”（`findByProjectIdAndId` 为空时返回 404），没有验证当前 actor 是否对该 project 有访问权。`getRequiresResourceToBelongToPathProject` 未 stub `projectAccess.requireAccess`，而 Mockito 对未 stub 的 void 方法默认静默通过；`listReturnsRepositoryOrder` 更是直接返回仓库数据，完全不触碰 `projectAccess`。上一轮 F2 的负向证据缺口在本轮测试中依然存在。

  如果生产代码在这些分支真的漏调 `ProjectAccess.requireAccess`，任何持有合法 JWT 的攻击者只要能枚举 project UUID，就可能读取、修改或删除不属于自己的 Wiki 页面/任务，属于典型 IDOR。即便当前实现已调用，测试套件也没有防止未来回归的保障。

- **Suggested Fix**:
  1. 为 `get/list/update/delete` 每个正向测试补充：
  ```java
  verify(projectAccess).requireAccess(projectId, actor);
  ```
  2. 为每个方法补充负向测试：
  ```java
  doThrow(new ForbiddenException("access denied"))
          .when(projectAccess).requireAccess(projectId, actor);

  assertThatThrownBy(() -> service.get(projectId, pageId, actor))
          .isInstanceOf(ForbiddenException.class);

  verify(wikiPages, never()).findByProjectIdAndId(any(), any());
  ```
  3. 在 `WikiPageService` 的所有读/写方法中，确保 `projectAccess.requireAccess(projectId, actor)` 在任何仓库查询之前执行；读取接口在无权限时建议统一返回 404 以避免泄露资源存在性。对 `TaskService` 执行同样的核验与测试补齐。

---

### R2-2 — JWT subject 缺失时产生 500 而非 401

- **Severity**: Low
- **File & Line**: `services/core-api/src/main/java/com/agentforge/core/security/AuthenticatedActor.java` L12-19
- **Evidence**:
```java
public static AuthenticatedActor from(Jwt jwt) {
    try {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return new AuthenticatedActor(
                UUID.fromString(jwt.getSubject()),
                roles != null && roles.contains("ADMIN"));
    }
    catch (IllegalArgumentException exception) {
        throw new UnauthorizedException("The access token contains an invalid subject.");
    }
}
```
- **Description**:
  `UUID.fromString(String)` 在入参为 null 时调用 `name.length()` 会抛出 `NullPointerException`，而这里的 `catch` 只捕获了 `IllegalArgumentException`，未捕获 NPE。因此当 JWT 缺少 `sub` claim 时，会绕过 `UnauthorizedException`，最终被全局异常处理转为 500 而非约定的 401。虽然当前内部 TokenService 应始终签发 `sub`，但作为鉴权入口的防御性代码不应把缺失 claim 变成服务器错误。

- **Suggested Fix**:
```java
public static AuthenticatedActor from(Jwt jwt) {
    String subject = jwt.getSubject();
    if (subject == null) {
        throw new UnauthorizedException("The access token is missing a subject.");
    }
    try {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return new AuthenticatedActor(
                UUID.fromString(subject),
                roles != null && roles.contains("ADMIN"));
    }
    catch (IllegalArgumentException exception) {
        throw new UnauthorizedException("The access token contains an invalid subject.");
    }
}
```

---

### R2-3 — JWT secret 缺少显式 Base64 解码与长度 fail-fast

- **Severity**: Medium
- **File & Line**: `services/core-api/src/main/java/com/agentforge/core/security/JwtProperties.java`（可见部分）；`.env.example` L12-14
- **Evidence**:
```java
@Validated
@ConfigurationProperties("agentforge.security.jwt")
public record JwtProperties(
        @NotBlank String secret,
        @NotBlank String issuer,
        @NotNull Duration ttl) {

    public JwtProperties {
        if (issuer != null) {
            URI parsedIssuer = URI.create(issuer);
            if (!parsedIssuer.isAbsolute()) {
                throw new IllegalArgumentException("JWT issuer must be an absolute URI.");
            }
        }
        if (ttl != null && (ttl.isZero() || ttl.isNegative() || ttl.compareTo(Duration. ...
```
```text
AGENTFORGE_JWT_SECRET=REPLACE_WITH_BASE64_32_BYTE_RANDOM_VALUE
```
- **Description**:
  可见的配置校验只做了 `@NotBlank secret`，以及 issuer 绝对 URI、TTL 范围校验。没有看到对 secret 进行 Base64 解码、或要求解码后至少 32 字节的 fail-fast。`.env.example` 中的占位值包含下划线，不属于标准 Base64 字母表；开发者若未正确替换，或生成了不够长的密钥，可能在生产启动或首次签发 token 时才报出难懂错误，而不是在配置绑定阶段给出可读提示。上一轮 F4 已提出此点，本次可见 diff 仍未证明配置层已闭环。

- **Suggested Fix**:
  1. 在 `JwtProperties` 构造器内增加：
  ```java
  if (secret != null) {
      byte[] decoded;
      try {
          decoded = Base64.getUrlDecoder().decode(secret);
      } catch (IllegalArgumentException ex) {
          throw new IllegalArgumentException(
                  "AGENTFORGE_JWT_SECRET must be a valid Base64 URL string.");
      }
      if (decoded.length < 32) {
          throw new IllegalArgumentException(
                  "AGENTFORGE_JWT_SECRET must decode to at least 32 bytes.");
      }
  }
  ```
  2. 在 `docs/05-development/local-development.md` 给出无歧义命令（含 `.Trim()` 去掉换行）：
  ```powershell
  [Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Maximum 256 }))
  ```
  或对应 `openssl rand -base64 32`。
  3. 增加一条启动级配置测试，验证非法/过短 secret 会明确失败，而不是 500。

---

### R2-4 — 乐观锁原子性仍无数据库级并发测试

- **Severity**: Medium
- **File & Line**: `services/core-api/src/main/java/com/agentforge/core/wiki/application/WikiPageService.java` update/delete；`WikiPageServiceTest.java` L89-107
- **Evidence**:
```java
WikiPageView updated = service.update(
        projectId, pageId, actor, " Updated ", "new", 0);

assertThat(updated.title()).isEqualTo("Updated");
assertThat(updated.content()).isEqualTo("new");
service.delete(projectId, pageId, actor, updated.version());
verify(wikiPages).delete(page);
```
- **Description**:
  本轮已修正上一轮指出的“update 后仍以 `expectedVersion=0` 删除”的版本号语义矛盾，现在使用 `updated.version()` 进行删除，方向正确。

  但这仍是 Mockito 单元测试，`WikiPage`/`TaskItem` 是否真正使用 `@Version` 或原子条件更新、`saveAndFlush` 后捕获 `OptimisticLockingFailureException` 转 409，均无法从可见 diff 确认。缺少真实数据库并发集成测试，两个并发请求同时以 `expectedVersion=0` 读取时，后写方是否能收到 409 而不是静默覆盖，仍未得到证明。

- **Suggested Fix**:
  1. 确认真实实体启用 `@Version`，或落地 SQL 条件更新：
  ```sql
  UPDATE wiki_page
     SET version = version + 1, title = ?, content = ?
   WHERE id = ? AND project_id = ? AND version = ?;
  ```
  返回 0 行时抛 `ConflictException("stale version")`。
  2. 增加集成/Testcontainers 测试：加载同一实体 `version=0`，事务 A 更新提交为 `1`，事务 B 再以 `expectedVersion=0` 更新，断言 B 收到 409/乐观锁异常，而不是覆盖。
  3. 若 V1 明确不要求并发防护，必须在变更记录中作为已知限制显式声明，不能以单元测试推断生产行为。

---

## 上一轮发现状态核验

| 上一轮 ID | 核验结果 | 说明 |
|---|---|---|
| F1 | 部分修复 | 版本号语义已修正，但原子性无集成测试，保留为 R2-4 |
| F2 | 未关闭 | `create` 已验证权限，list/get/update/delete 仍无测试证据，保留为 R2-1 |
| F3 | 已修复 | `scripts/agent-bridge/review-loop.ps1` 已出现在本 commit 文件清单中，`AGENTS.md` 引用不再悬空 |
| F4 | 部分修复 | 本地开发文档已更新，但配置层 secret 强校验未在可见 diff 中确认，保留为 R2-3 |
| F5 | 部分核验 | `ApiSecurityTest`/`ResourceApiTest` 已新增，因 diff 截断未能核验 401/403/409 全矩阵，建议在变更记录回填结果 |

---

## 主开发 (Codex) 评估回填区

> 请 Codex 恢复后逐项评估，接受则给出修复方案并回到文档先行流程；拒绝则提供生产实现或测试证据，不得盲目照改。

| ID | Codex 结论（接受/部分接受/拒绝） | 修复计划或反驳理由 | 当前状态 |
|---|---|---|---|
| R2-1 | 接受 | Wiki 正向测试显式验证访问检查，新增拒绝后仓库零查询测试；Task 保留同类负向测试并补强更新/删除调用验证。 | 已实现，42 项 Java 测试通过 |
| R2-2 | 接受 | 缺失 subject 在 UUID 解析前转为 `UnauthorizedException`，并新增单元测试。 | 已实现，测试通过 |
| R2-3 | 拒绝 | `SecurityConfiguration.jwtSecretKey` 已使用标准 Base64 解码并要求至少 32 字节；本地文档已有生成命令。Pi 因截断 diff 未看到该实现。 | 已核验 |
| R2-4 | 接受 | 新增 PostgreSQL 双 EntityManager 旧快照提交测试，直接验证 `@Version`；当前机器 Docker 不可用，因此本轮 Maven 自动跳过该 Testcontainers 用例。 | 已实现；需在 Docker 可用环境复跑 |

---

**审查结论**：`NEEDS_FIX`。R2-1、R2-4 涉及跨项目越权与并发一致性，属于必须在交付前补救或由 Codex 提供明确证据的安全/一致性项；R2-2、R2-3 可在修复时一并处理。
