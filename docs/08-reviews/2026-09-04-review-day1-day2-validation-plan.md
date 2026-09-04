我已完成只读检查。本轮未执行任何 PowerShell/shell 命令，也未运行测试、未创建或删除任何数据；全部操作仅为 `read`/`ls`/`grep`/`find` 只读文件检查。以下是五项设计与判断。

---

## 1. JWT secret 校验的最小 JUnit 测试

`SecurityConfiguration.jwtSecretKey` 已实现“标准 Base64 解码 + ≥32 字节”校验（`IllegalStateException` 信息分别为 `must be valid Base64` 与 `must decode to at least 32 bytes`），无需改生产代码，只缺证据测试。建议新增独立测试类（同包即可访问 package-private 的 `jwtSecretKey`，无需 Spring 上下文，毫秒级）：

```java
package com.agentforge.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;

class JwtSecretKeyValidationTest {

    private final SecurityConfiguration configuration = new SecurityConfiguration();

    private JwtProperties properties(String secret) {
        return new JwtProperties(secret, "https://agentforge.test/core-api", Duration.ofMinutes(30));
    }

    @Test
    void invalidBase64SecretFailsFast() {
        JwtProperties props = properties("!!!not-base64!!!");

        assertThatThrownBy(() -> configuration.jwtSecretKey(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("valid Base64");
    }

    @Test
    void secretShorterThan32BytesFailsFast() {
        String secret = Base64.getEncoder()
                .encodeToString("0123456789abcdef".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> configuration.jwtSecretKey(properties(secret)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    void valid32ByteSecretProducesHmacSha256Key() {
        String secret = Base64.getEncoder()
                .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

        SecretKey key = configuration.jwtSecretKey(properties(secret));

        assertThat(key.getAlgorithm()).isEqualTo("HmacSHA256");
        assertThat(key.getEncoded()).hasSize(32);
    }
}
```

说明：`"0123456789abcdef"` 为 16 字节、Base64 合法但解码后 <32 字节，命中第二分支；`"!!!not-base64!!!"` 含非法字符，命中第一分支；合法值沿用现有测试所用的 32 字节串。

---

## 2. review outcome 状态迁移的最小可测试接口 + PowerShell 冒烟断言

当前 `review-loop.ps1` 把 PASS/NEEDS_FIX 迁移逻辑内联在循环体里，且通过固定路径调用 `run-review.ps1`，无法脱离 Pi 测试。最小改动是抽出一个**纯函数**（无文件 IO、无 Git、无 Pi 调用、无时钟读取），由编排器调用后自行落盘。

### 建议接口（写入 `review-loop.ps1`）

```powershell
function ConvertTo-ReviewOutcome {
    [CmdletBinding()]
    [OutputType([pscustomobject])]
    param(
        [Parameter(Mandatory)][hashtable]$Stage,
        [Parameter(Mandatory)][ValidateSet('PASS', 'NEEDS_FIX')][string]$Result,
        [Parameter(Mandatory)][int]$Attempt,
        [Parameter(Mandatory)][int]$MaximumAttempts,
        [Parameter(Mandatory)][string]$TargetCommit,
        [Parameter(Mandatory)][string]$ReportPath
    )

    $status             = 'WAITING_FOR_CODEX_FIX'
    $nextStageReady     = $null
    $needsHumanRecord   = $false

    if ($Result -eq 'PASS') {
        $status           = 'RESOLVED'
        $nextStageReady   = [string]$Stage.id
    } elseif ($Attempt -ge $MaximumAttempts) {
        $status           = 'HUMAN_REQUIRED'
        $needsHumanRecord = $true
    }

    [pscustomobject]@{
        StageId            = [string]$Stage.id
        Status             = $status
        Attempts           = $Attempt
        LastReviewedCommit = $TargetCommit
        LastReportPath     = $ReportPath
        LastFailure        = $null
        NextStageReady     = $nextStageReady
        HumanIntervention  = $needsHumanRecord
        Blocked            = ($status -eq 'HUMAN_REQUIRED')
    }
}
```

迁移表：

| Result | Attempt vs Max | Status | NextStageReady | HumanIntervention | Blocked |
|---|---|---|---|---|---|
| PASS | 任意 | RESOLVED | Stage.id | false | false |
| NEEDS_FIX | ≥ Max | HUMAN_REQUIRED | null | true | true |
| NEEDS_FIX | < Max | WAITING_FOR_CODEX_FIX | null | false | false |

编排器接线（Codex 负责）：`$nextAttempt` 计算后调用该函数，把返回字段回填到 `$stage`；`$state.nextStageReady` 只在 `NextStageReady` 非空时赋值一次；`HumanIntervention` 为真时才 `Write-HumanInterventionRecord`；时间戳由调用方用 `(Get-Date)` 打点，保持纯函数确定性。BUSY 锁短路发生在调用前，天然保证“锁占用不消耗 attempt”。

### PowerShell 冒烟断言

```powershell
$stage = @{ id = 'v1-day-1' }

# 1) PASS -> RESOLVED + NEXT_STAGE_READY
$pass = ConvertTo-ReviewOutcome -Stage $stage -Result PASS -Attempt 3 -MaximumAttempts 3 -TargetCommit 'abc123' -ReportPath 'r.md'
if ($pass.Status -ne 'RESOLVED' -or $pass.NextStageReady -ne 'v1-day-1' -or $pass.Blocked) { throw "PASS 迁移失败: $($pass | ConvertTo-Json -Compress)" }

# 2) 第三次 NEEDS_FIX -> HUMAN_REQUIRED
$third = ConvertTo-ReviewOutcome -Stage $stage -Result NEEDS_FIX -Attempt 3 -MaximumAttempts 3 -TargetCommit 'abc123' -ReportPath 'r.md'
if ($third.Status -ne 'HUMAN_REQUIRED' -or -not $third.HumanIntervention -or -not $third.Blocked) { throw 'HUMAN_REQUIRED 迁移失败' }

# 3) 第一次 NEEDS_FIX -> WAITING_FOR_CODEX_FIX
$first = ConvertTo-ReviewOutcome -Stage $stage -Result NEEDS_FIX -Attempt 1 -MaximumAttempts 3 -TargetCommit 'abc123' -ReportPath 'r.md'
if ($first.Status -ne 'WAITING_FOR_CODEX_FIX' -or $first.HumanIntervention) { throw 'WAITING_FOR_CODEX_FIX 迁移失败' }

# 4) 纯函数确定性 / 重复应用不产生第二次推进
$again = ConvertTo-ReviewOutcome -Stage $stage -Result PASS -Attempt 3 -MaximumAttempts 3 -TargetCommit 'abc123' -ReportPath 'r.md'
if (($pass | ConvertTo-Json -Compress) -ne ($again | ConvertTo-Json -Compress)) { throw '迁移非确定性' }
```

“锁占用不消耗 attempt”是编排器级断言（预置状态文件 + 持锁，断言返回 BUSY 且 attempts 不变）：

```powershell
$stateFile = Join-Path $testRoot 'busy-seeded.json'
[System.IO.File]::WriteAllText($stateFile,
    '{"schemaVersion":1,"stages":{"v1-day-1":{"id":"v1-day-1","attempts":1,"status":"WAITING_FOR_CODEX_FIX","deliveryCommit":"0000"}},"nextStageReady":null}',
    [System.Text.UTF8Encoding]::new($false))
$heldLock = [System.IO.File]::Open("$stateFile.lock", [System.IO.FileMode]::OpenOrCreate, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
try {
    $busy = & $reviewLoop -OnCodexWake -StatePath $stateFile
    if ($busy.OverallStatus -ne 'BUSY') { throw '持锁时应返回 BUSY' }
} finally { $heldLock.Dispose() }
$after = Get-Content -Raw $stateFile | ConvertFrom-Json
if ([int]$after.stages.'v1-day-1'.attempts -ne 1) { throw '锁竞争消耗了 attempt' }
```

---

## 3. `Show-ReviewStatus` 损坏 JSON 与 STALLED 的可测试方式

**实现问题（仅报告）**：`Show-ReviewStatus.ps1` 把所有状态文件路径硬编码为 `Join-Path $PSScriptRoot ...`，且脚本体在顶层执行 `do/while`，无法在测试中用临时目录注入，也无法安全 dot-source（会读真实状态文件）。因此当前**不可测试**，需 Codex 先加一个运行目录注入参数。

### 需要的 seam（Codex 修改）

给 `Show-ReviewStatus.ps1` 增加参数并让 `Get-Snapshot` 内所有 `Join-Path $PSScriptRoot ...` 改用脚本级变量：

```powershell
param(
    [string]$StateDirectory,
    ... # 保留现有 Tail/Json/Watch/RefreshSeconds/StallThresholdSeconds
)
# 脚本顶部：
$script:RunDir = if ([string]::IsNullOrWhiteSpace($StateDirectory)) { $PSScriptRoot } else { $StateDirectory }
```

`Get-Snapshot` 中的 pid/state/lock/status/log 全部改为 `Join-Path $script:RunDir ...`。

### 损坏 JSON 冒烟（写入 `Test-ReviewBridge.ps1`）

```powershell
$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("agentforge-status-test-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tmp | Out-Null
try {
    # 损坏的 Pi 状态文件 -> INVALID
    [System.IO.File]::WriteAllText((Join-Path $tmp '.pi-review-status.json'), '{broken', [System.Text.UTF8Encoding]::new($false))
    $snap = & $showStatus -StateDirectory $tmp -Json | ConvertFrom-Json
    if ($snap.pi.status.status -ne 'INVALID') { throw '损坏的 Pi 状态未报告 INVALID' }

    # 损坏的循环状态文件 -> 不崩溃，stages 为空，仍产出合法 JSON
    [System.IO.File]::WriteAllText((Join-Path $tmp '.review-loop-state.json'), '{broken', [System.Text.UTF8Encoding]::new($false))
    $snap2 = & $showStatus -StateDirectory $tmp -Json | ConvertFrom-Json
    if ($snap2.stages.Count -ne 0) { throw '损坏的状态文件应产生空阶段列表' }
} finally {
    Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
}
```

### STALLED 冒烟

先写 pid（指向正在运行的自身 `$PID`），**先回拨锁文件 mtime、再持独占锁**（持锁后 `LastWriteTime` setter 会因 `FileShare.None` 打开失败），最后以最小阈值 30 秒调用：

```powershell
$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("agentforge-stalled-test-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tmp | Out-Null
try {
    [System.IO.File]::WriteAllText((Join-Path $tmp '.bridge-monitor.pid'), "$PID", [System.Text.UTF8Encoding]::new($false))

    $lockPath = Join-Path $tmp '.review-loop-state.json.lock'
    [System.IO.File]::WriteAllText($lockPath, '', [System.Text.UTF8Encoding]::new($false))
    (Get-Item $lockPath).LastWriteTime = (Get-Date).AddSeconds(-60)   # 先回拨，再持锁

    $heldLock = [System.IO.File]::Open($lockPath, [System.IO.FileMode]::Open, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
    try {
        $snap = & $showStatus -StateDirectory $tmp -Json -StallThresholdSeconds 30 | ConvertFrom-Json
        if ($snap.overallStatus -ne 'STALLED') { throw "期望 STALLED，实际 $($snap.overallStatus)" }
        if (-not $snap.lock.held) { throw 'lock.held 应为 true' }
    } finally { $heldLock.Dispose() }
} finally {
    Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
}
```

触发链：monitor PID 存活 → `monitor.running=true`；Pi 未运行 → `RUNNING` 不成立；锁被独占且 `ageSeconds(60) ≥ 30` → `STALLED`。注意 `StallThresholdSeconds` 参数下限为 30，因此必须回拨 mtime 而不能等待。

---

## 4. PostgreSQL Testcontainers 在 GitHub Actions 上是否可直接运行

**结论：可以直接运行，无需为“启动容器”改 workflow。**

依据：
- `.github/workflows/core-api-ci.yml` 使用 `runs-on: ubuntu-latest`。GitHub 托管 Ubuntu 镜像自带 Docker 且 daemon 已运行，Testcontainers 可通过 `/var/run/docker.sock` 直接拉取并启动 `postgres:17-alpine`。
- `PersistenceIntegrationTest` 用 `@Testcontainers(disabledWithoutDocker = true)` + `@Container @ServiceConnection PostgreSQLContainer`，Spring Boot 3.5.16 已含 `spring-boot-testcontainers`、`testcontainers:junit-jupiter` 与 `postgresql` 依赖，`mvn verify` 的 Surefire 会默认执行 `*Test` 类（含 2 个用例）。
- 无自定义 Docker 网络、无 `withReuse`、无特权模式等特殊要求。

**关键风险（证据闭环）**：`disabledWithoutDocker = true` 意味着 Docker 缺失时该测试会被**跳过而非失败**，因此“CI 绿了”不等于“A3-1 实际跑过”。为闭合 A3-1，建议在 CI 增加一步断言该测试真实执行（`tests=2, skipped=0`）。建议片段（仅建议，不落盘）：

```yaml
      - name: Assert Testcontainers integration test actually ran
        working-directory: services/core-api
        run: |
          python3 - <<'PY'
          import xml.etree.ElementTree as ET
          r = ET.parse('target/surefire-reports/TEST-com.agentforge.core.PersistenceIntegrationTest.xml').getroot()
          assert int(r.attrib['tests']) == 2, r.attrib
          assert int(r.attrib['skipped']) == 0, r.attrib
          assert int(r.attrib['errors']) == 0 and int(r.attrib['failures']) == 0, r.attrib
          print('PersistenceIntegrationTest ran:', r.attrib)
          PY
```

补充观察（非阻断）：workflow 使用系统 `mvn` 而非仓库固定的 `./mvnw`（Maven 3.9.11），存在版本漂移；建议改用 `./mvnw`，否则 CI 实际运行的是 runner 自带 Maven 版本，与本地固定版本不一致。另外需留意 Docker Hub 匿名拉取限流，必要时配置镜像源或登录凭证。

---

## 5. 需要 Codex 修改的精确文件清单

1. `services/core-api/src/test/java/com/agentforge/core/security/JwtSecretKeyValidationTest.java`（**新增**）—— 落地第 1 节三条 JUnit 用例，闭合 A3-2。
2. `scripts/agent-bridge/review-loop.ps1` —— 新增纯函数 `ConvertTo-ReviewOutcome`，并把 PASS/NEEDS_FIX 分支改为调用它；接线 `nextStageReady` 单次写入与 `HumanIntervention` 触发写记录（闭合 F1）。
3. `scripts/agent-bridge/Test-ReviewBridge.ps1` —— 增加第 2 节三条迁移断言 + “锁占用不消耗 attempt”断言（闭合 F1）。
4. `scripts/agent-bridge/Show-ReviewStatus.ps1` —— 增加 `-StateDirectory` 注入参数并路由所有状态文件路径（闭合 F2 的 seam）。
5. `scripts/agent-bridge/Test-ReviewBridge.ps1` —— 增加第 3 节损坏 JSON 与 STALLED 冒烟断言（闭合 F2）。
6. `.github/workflows/core-api-ci.yml`（**建议**）—— 增加“Testcontainers 实际执行（0 跳过）”断言，并可选把 `mvn` 改为 `./mvnw`，用 CI 证据闭合 A3-1。
7. （可选，文档回填）`docs/08-reviews/2026-09-04-review-v1-day-1-attempt-3.md` 与 `2026-09-04-review-v1-day-2-attempt-3.md` 的 Codex 回填区，在验证通过后回填测试名称与 CI 结果，把 `A3-1/A3-2/F1/F2` 标记为已关闭。

无需修改：`SecurityConfiguration.java` 与 `JwtProperties.java`（校验逻辑已正确存在，仅缺测试证据）。

---

## 执行记录（本轮）

| 项目 | 结果 |
|---|---|
| Shell/PowerShell 命令执行数 | 0（仅只读 `read`/`ls`/`grep`/`find` 文件检查） |
| 退出码 | 无（无 shell 命令） |
| 测试数量 | 0（按要求本轮只设计，不执行） |
| 跳过项 | 0 |
| 清理结果 | 无需清理（未创建临时/构建/Testcontainers 数据） |

只读检查过的文件：`scripts/agent-bridge/{review-loop.ps1, Show-ReviewStatus.ps1, Test-ReviewBridge.ps1, run-review.ps1, bridge-monitor.ps1, Start-BridgeMonitor.ps1, review-stages.json, README.md, .pi-review-status.json, .review-loop-state.json, .bridge-monitor.pid}`、`services/core-api/src/main/java/com/agentforge/core/security/{SecurityConfiguration.java, JwtProperties.java}`、`services/core-api/src/test/java/com/agentforge/core/security/*`、`services/core-api/src/test/java/com/agentforge/core/PersistenceIntegrationTest.java`、`services/core-api/pom.xml`、`.github/workflows/core-api-ci.yml`、`docs/08-reviews/2026-09-04-review-v1-day-{1,2}-attempt-3.md`、`docs/03-features/review-orchestration.md`、`docs/07-changes/2026-09-03-repository-bootstrap-and-day-1.md`。
