# Pi 代码审查报告：v1-day-2 / Attempt 2

- 日期：2026-09-04
- 审查阶段：v1-day-2
- 审查对象：de08b54965a829bbc5bcb2625b5e630ecbc41c64（基线：8ad81498470bf265cc58516068bc095e2ebe5fa0）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- REVIEW_RESULT: NEEDS_FIX
- Pi 进程超时上限：600 秒

---

REVIEW_RESULT: NEEDS_FIX

# AgentForge v1-day-2 代码审查报告（第 2/3 轮）

- 日期：2026-09-04
- 审查阶段：v1-day-2
- 审查轮次：2 / 3
- 审查对象：`de08b54965a829bbc5bcb2625b5e630ecbc41c64`（基线：`8ad81498470bf265cc58516068bc095e2ebe5fa0`）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- Diff 状态：已截断；本次可核实首、中、尾采样与文件清单，`review-loop.ps1` / `bridge-monitor.ps1` / `run-review.ps1` 主体未完整展示。

---

## 概述与总体结论

- **结论**：需修复后交付（NEEDS_FIX）
- **总体判断**：上一轮 R1（Wiki/Task 删除版本号自相矛盾）的调用点已修正，R3～R5 依据 Codex 回填未发现反向证据。但 R1 要求补充的“过期版本必须拒绝”负向测试未落地，删除操作的乐观锁约束仍然只能靠生产代码单方面保证，未形成测试闭环。此外，新增核心编排脚本缺少自动化测试，且 Task 控制器契约覆盖仍不完整。
- **阻断性**：未发现已证实的生产运行阻断 Bug；当前问题集中在测试有效性与可验证性，因此不允许直接 PASS。

---

## 详细发现清单

| ID | 严重级别 | 文件 | 行号 | 核心问题 |
|----|---------|------|------|----------|
| N1 | Medium | `WikiPageServiceTest.java` / `TaskServiceTest.java` | 约 L101-103 / L117-119 | 删除调用点改为 `updated.version()`，但未补充“过期版本拒绝删除”的负向测试，乐观锁约束仍未被测试证明 |
| N2 | Medium | `ResourceApiTest.java` | 约 L110-124 | 仅补充 Wiki GET 的 404/403 映射，Task 资源同类契约仍无控制器层覆盖 |
| N3 | Low | `Show-ReviewStatus.ps1` | L7-13 | 直读运行时 JSON 与 PID 文件，无损坏/半写入文件保护，诊断脚本可能抛出未处理错误 |
| N4 | Medium | `scripts/agent-bridge/review-loop.ps1` | 文件级 | 新增 306 行状态机无任何自动化测试，互斥、三次上限、PASS/NEXT_STAGE_READY 等关键分支仅靠人工验证 |

---

## 逐项展开

### N1 — 删除操作乐观锁仍缺少过期版本拒绝测试

- **Severity**：Medium
- **File & Line**：
  - `services/core-api/src/test/java/com/agentforge/core/wiki/application/WikiPageServiceTest.java`
  - `services/core-api/src/test/java/com/agentforge/core/task/application/TaskServiceTest.java`
- **Evidence**：
```java
// WikiPageServiceTest
-        service.delete(projectId, pageId, actor, 0);
+        service.delete(projectId, pageId, actor, updated.version());

// TaskServiceTest
-        service.delete(projectId, taskId, actor, 0);
+        service.delete(projectId, taskId, actor, updated.version());
```
- **Description**：上一轮 R1 指出的版本号自相矛盾已通过使用 `updated.version()` 修复，但这只消除了“update 后再 delete 参数不匹配”的表面矛盾。如果生产代码 `WikiPageService.delete` / `TaskService.delete` 实际忽略预期版本直接删除，那么这两个测试仍会通过，原始并发问题会被掩盖。必须用显式的“版本过期必须拒绝”测试证明删除路径真正执行乐观锁校验。
- **Suggested Fix**：为 Wiki 与 Task 各自补充一个过期版本拒绝用例：
```java
@Test
void deleteRejectsStaleVersion() {
    when(wikiPages.findByProjectIdAndId(projectId, pageId)).thenReturn(Optional.of(page));

    assertThatThrownBy(() -> service.delete(projectId, pageId, actor, 1L))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("stale");

    verify(wikiPages, never()).delete(page);
}
```
Task 侧同理。若该测试失败，需在生产 `delete` 方法中补上版本比较逻辑，而不是只修测试。

---

### N2 — Task 资源 404/403 控制器契约未直接覆盖

- **Severity**：Medium
- **File & Line**：`services/core-api/src/test/java/com/agentforge/core/security/ResourceApiTest.java`（约 L110-124）
- **Evidence**：
```java
+    @Test
+    void wikiGetMapsNotFoundAndForbiddenContracts() throws Exception {
+        ...
+        when(wikiPageService.get(...))
+                .thenThrow(new ResourceNotFoundException("Wiki page was not found."));
+        mockMvc.perform(get("/api/v1/projects/{projectId}/wiki-pages/{pageId}", ...))
+                .andExpect(status().isNotFound());
+
+        when(wikiPageService.get(...))
+                .thenThrow(new ForbiddenException("access denied"));
+        mockMvc.perform(get("/api/v1/projects/{projectId}/wiki-pages/{pageId}", ...))
+                .andExpect(status().isForbidden());
+    }
```
- **Description**：上一轮 R2 要求 Task 与 Wiki 控制器都覆盖业务状态码。本次只补了 Wiki GET 一个测试；Task 同类 `ResourceNotFoundException` 与 `ForbiddenException` 映射仍没有 `@WebMvcTest` 级别的显式断言。虽然异常处理器可能是共享的，但 REST 契约验收应做到资源级别对称覆盖，防止某个 Controller 忘记启用异常映射。
- **Suggested Fix**：增加 `taskGetMapsNotFoundAndForbiddenContracts()`，同样断言 `GET /api/v1/projects/{projectId}/tasks/{taskId}` 返回 404 与 403；如时间允许，补齐 Task update 的 409、非法参数 400 用例。

---

### N3 — Show-ReviewStatus 对运行时状态文件缺少容错

- **Severity**：Low
- **File & Line**：`scripts/agent-bridge/Show-ReviewStatus.ps1`（L7-13）
- **Evidence**：
```powershell
$monitorPidText = if(Test-Path $pidFile){(Get-Content -Raw $pidFile).Trim()}else{$null}
...
piStatus=$(if(Test-Path $statusFile){Get-Content -Raw $statusFile|ConvertFrom-Json}else{$null})
```
- **Description**：`.pi-review-status.json` 可能正被 monitor 半写入、为空或损坏。当前直接 `ConvertFrom-Json`，PowerShell 会输出错误或导致诊断工具本身失败；自动化排障时，该脚本的非零/异常输出可能被误判为审查失败。PID 文件同理，若内容为空，`Get-Process -Id ""` 虽有 `-ErrorAction SilentlyContinue`，但整体没有结构化错误兜底。
- **Suggested Fix**：用 `try/catch` 包裹 JSON 解析并退化为显式状态对象：
```powershell
$piStatus = if(Test-Path $statusFile){
    try { (Get-Content -Raw $statusFile | ConvertFrom-Json) }
    catch { [PSCustomObject]@{ error = "status file unreadable: $_" } }
}
```
PID 读取增加 `String.IsNullOrWhiteSpace` 判断后再调用 `Get-Process`。

---

### N4 — review-loop 状态机无自动化测试

- **Severity**：Medium
- **File & Line**：`scripts/agent-bridge/review-loop.ps1`（+306 行，文件级）
- **Evidence**：本次文件清单中没有任何针对 `review-loop.ps1` / `bridge-monitor.ps1` 的测试脚本；`review-loop.ps1` 是唯一状态机，承担“最多三次 NEEDS_FIX”、“PASS 后写 NEXT_STAGE_READY 且只消费一次”、“用户唤醒与 monitor 并发不得消耗轮次”等关键行为。
- **Description**：这些分支直接关系人工接管边界与并发安全，仅靠 Codex 回填“已核验”不足以长期保障。当前测试策略文档与 Definition of Done 要求“与改动相称的自动化测试”，但新编排逻辑完全没有可重复执行的测试。
- **Suggested Fix**：为 `review-loop.ps1` 增加纯函数化的状态迁移层并配套 Pester 冒烟测试，至少覆盖：
  - `NEEDS_FIX` 三次后进入 `HUMAN_REQUIRED` 且不触发第四次审查；
  - `PASS` 只写入一次 `NEXT_STAGE_READY`，重复执行不重复推进；
  - 锁被占用时不消耗 attempt 次数。
  若不符合 V1 复杂度，至少提供一条 `-DryRun` 场景可被脚本化验证的命令，并写入变更记录。

---

## 主开发（Codex）评估回填区

| 发现 ID | 是否采纳 | 处理方式 | 验证结果 |
|--------|---------|---------|---------|
| N1 | 接受 | Wiki/Task 各新增过期版本删除拒绝测试，并验证 repository delete 从未执行。 | 已实现，测试通过 |
| N2 | 接受 | 新增 Task GET 的 404/403 对称控制器契约。 | 已实现，测试通过 |
| N3 | 接受 | 状态读取统一容错，损坏 JSON 转为显式 `INVALID`，PID 仅在纯数字时解析。 | 已实现，bridge 回归通过 |
| N4 | 接受（最小实现） | 新增无 Pi 冒烟脚本，覆盖 AST、BUSY、异常释放锁、DryRun 不落盘与 JSON 状态输出；未引入 Pester 依赖。 | 5 项检查通过 |

---

## 上一轮问题关闭情况核验

| 上一轮 ID | 判断 | 说明 |
|----------|------|------|
| R1 | 部分关闭 | 调用点改用 `updated.version()`；但过期版本拒绝测试缺失，已立为 N1 |
| R2 | 部分关闭 | 仅补 Wiki 404/403；Task 对称覆盖缺失，已立为 N2 |
| R3 | 认可关闭 | 未发现反向证据；`ResourceApiTest` 中 `ForbiddenException` 已实际参与映射断言 |
| R4 | 认可关闭 | `TaskServiceTest` 可见 `projectAuthorizationRunsBeforeTaskLookup` 验证先鉴权后查询 |
| R5 | 认可关闭 | 依据 Codex 回填及 R1/R5 前文，未发现本次 diff 削弱该约束 |

> 说明：本轮为只读审查，未修改任何文件。Codex 恢复后请根据上表逐项确认，不要机械照改；N4 若经评估认为超出 V1 复杂度，需在回填区明确豁免理由。
