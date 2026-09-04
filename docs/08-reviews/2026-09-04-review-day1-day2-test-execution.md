# AgentForge 独立验证报告（Day 1 / Day 2 测试执行）

## 执行命令与结果总表

| # | 命令 | 退出码 | 结果 | 测试数 | 跳过 | 清理 |
|---|------|--------|------|--------|------|------|
| 1 | `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\agent-bridge\Test-ReviewBridge.ps1` | **1** | **失败**（DryRun 阶段异常） | 12 项声明检查未跑完（约前 8 项通过后中止） | — | 本次运行自清理 |
| 2 | `pwsh.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\agent-bridge\Test-ReviewBridge.ps1` | **1** | **失败**（同上，PS5/PS7 行为一致） | 同上 | — | 本次运行自清理 |
| 3 | `services/core-api` 下 `.\mvnw.cmd verify` | **0** | **成功** | 45 | **2**（PostgreSQL 集成测试） | `target` 保留（按约定保留 Surefire 证据） |
| 4 | 诊断复现：`pwsh.exe -Command "& .\scripts\agent-bridge\review-loop.ps1 -DryRun -StatePath %TEMP%\af-verify-dryrun.json"` | **1** | 复现同一异常，确认根因 | — | — | 无残留 |
| 5 | `docker info --format '{{.ServerVersion}}'` | **1** | Docker 守护进程未运行（npipe 不存在） | — | — | — |
| 6 | 清理 3 个遗留 `%TEMP%\agentforge-review-test-*` 目录 | **0** | 删除 3 个，剩余 0 | — | — | ✅ |

> 说明：命令 4/5/6 为验证与清理所需；未执行任何 Git 写操作、未使用 edit/write、未写任何源码/文档、未输出任何密钥。

---

## 核验结论

### 1. bridge 输出 —— ❌ 未达到 `Passed=True` / `Checks=12`

`Test-ReviewBridge.ps1` 在到达最终 `[PSCustomObject]@{ Passed = $true; ...; Checks = 12 }` 之前异常退出，**未输出任何 `Passed`/`Checks` 结果**。

- **异常来源**：`Test-ReviewBridge.ps1:55` 调用 `& $reviewLoop -DryRun -StatePath $dryState` 时抛出。
- **根因位置**：`scripts/agent-bridge/review-loop.ps1:235`（`if (-not $knownStage) { throw "Review-Fixes 引用了未知阶段：$fixStageId" }`）。
- **触发数据**：当前 HEAD `a8a5571ddfd1f94ad7746f1647b9b72dba2a4243` 的提交信息含三条 `Review-Fixes:`，其中 `review-visibility-and-liveness` 不在 `review-stages.json` 的 `historicalStages`（仅 `v1-day-1`、`v1-day-2`），而 DryRun 使用全新空状态目录，故 `$state.stages` 也不含该阶段 → 判定为“未知阶段”抛出。

关键证据链：
- 当前 HEAD 提交信息（只读 `git log -1`）：
  ```
  Review-Fixes: v1-day-1
  Review-Fixes: v1-day-2
  Review-Fixes: review-visibility-and-liveness
  ```
- `review-stages.json` 仅注册 `v1-day-1`、`v1-day-2`。
- 工作树已有 `.review-loop-state.json` 中确实含 `review-visibility-and-liveness`（此前真实循环运行过），所以**非 DryRun 的当前工作树运行不会失败**；但 `Test-ReviewBridge.ps1` 用临时空状态目录，等价于“全新 clone / 首次运行”，从而复现失败。这说明该缺陷不仅影响测试，也会在全新环境首次运行时命中。

### 2. JWT secret 三个新测试 —— ✅ 真实执行

`JwtSecretKeyValidationTest`：
```
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
```
三个测试（`invalidBase64SecretFailsFast`、`secretShorterThan32BytesFailsFast`、`valid32ByteSecretProducesHmacSha256Key`）均真实执行并通过。

### 3. PostgreSQL 集成测试 —— ⏭️ 跳过（未执行）

`PersistenceIntegrationTest`：
```
Tests run: 2, Failures: 0, Errors: 0, Skipped: 2
```
Surefire XML 中两条跳过记录的原因为：
```
<skipped message="disabledWithoutDocker is true and Docker is not available"/>
```
`docker info` 确认 Docker 守护进程未运行（`npipe:////./pipe/dockerDesktopLinuxEngine` 不存在），故 `@Testcontainers(disabledWithoutDocker = true)` 触发跳过。**无 Testcontainers 容器被创建，无需容器清理。**

---

## 最小修复建议（供 Codex 修改，我未改动）

**推荐方案 A（代码修复，同时修复全新环境首次运行）**
文件 `scripts/agent-bridge/review-loop.ps1`，在第 232 行附近（`$reviewFixStageIds = ...` 之后、`foreach ($fixStageId ...)` 之前）补充“变更记录派生阶段 ID”集合，并放宽已知阶段判断：

```powershell
$changeRecordStageIds = @(Get-ChildItem -LiteralPath (Join-Path $ProjectRoot 'docs/07-changes') -Filter '*.md' -File -ErrorAction SilentlyContinue |
    ForEach-Object { [System.IO.Path]::GetFileNameWithoutExtension($_.Name) -replace '^\d{4}-\d{2}-\d{2}-', '' })
foreach ($fixStageId in $reviewFixStageIds) {
    $knownStage = @($definitions | Where-Object { [string]$_.id -eq $fixStageId }).Count -gt 0 -or
                  $state.stages.ContainsKey($fixStageId) -or
                  ($changeRecordStageIds -contains $fixStageId)
    if (-not $knownStage) { throw "Review-Fixes 引用了未知阶段：$fixStageId" }
}
```

原理：`review-visibility-and-liveness` 实际对应已存在的 `docs/07-changes/2026-09-04-review-visibility-and-liveness.md`（按 `Find-AutomaticStageDefinition` 的同一命名规则派生），只是尚未被持久化进状态文件。把“变更记录派生 ID”纳入已知集合，既能通过 DryRun 回归测试，也能修复全新 clone 首次运行时的同源崩溃，同时保留对真正拼写错误的 `Review-Fixes:` 的拦截。

**备选方案 B（数据修复，快速但脆弱）**
在 `review-stages.json` 的 `historicalStages` 增加：
```json
{
  "id": "review-visibility-and-liveness",
  "displayName": "review-visibility-and-liveness",
  "baseRef": "49b893898e5a699073b41f17539d6401adc91117",
  "deliveryCommit": "de08b54965a829bbc5bcb2625b5e630ecbc41c64"
}
```
缺点：只解决当前这一条 trailer；未来任何新的自动发现阶段在“全新状态 + 含其 `Review-Fixes:` trailer”的场景仍会复发，且 DryRun 测试依旧非密封（依赖真实 HEAD）。因此建议采用方案 A。

---

## 清理结果

- 删除 3 个测试脚本遗留的 `%TEMP%\agentforge-review-test-*` 目录（内容均为 `invalid-state.json`/`busy-state.json.lock` 等测试产物；时间戳为本次会话之前的旧运行遗留）。删除后剩余匹配目录数 = **0**。
- 本次两条 bridge 命令自身已在 `finally` 中自清理其临时目录；DryRun 复现命令未写状态/锁文件。
- Maven `target` **未删除**（按约定保留 Surefire 证据）。
- 无 Testcontainers 容器（Docker 不可用，未创建）。
- 未执行任何 Git 写操作。
