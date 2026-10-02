# Pi 代码审查报告：v3-09-integration-release / Attempt 1

- 日期：2026-10-02
- 审查阶段：v3-09-integration-release
- 审查对象：INDEX@aceb355（基线：aceb35527a9ac614624a535c2d49938e22963f5e）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: NEEDS_FIX
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: NEEDS_FIX

# V3-09 Integration / V3 Release Gate — Milestone Review（第 1 / 3 轮）

## 一、概述与总体结论

- 审查范围：Commit INDEX@aceb355 的 12 个改动文件（1 处生产代码、2 个新增 validation 脚本、9 份文档）。
- 生产代码改动只有一处：`Neo4jGraphStore` 将 `withMaxTransactionRetryTime` 由 `0` 放宽为 `2s`。该改动方向、范围、异常收敛（仍统一映射通用 503）、幂等前提（稳定 ID + 唯一约束 + 项目锁）与 ADR-0032 / graph-domain-model 的修订描述一致，未越过本节点「禁止新增功能」边界。
- 文档侧：README 只把 V3-09 标为 🚧，未提前宣称 `v3-stable`；V3-08 由 In Progress 修正为 Implemented，与 roadmap 一致。真实性检查整体通过。
- 判定：**需修复后交付（NEEDS_FIX）**。阻断项只有 1 个（runner 环境变量清理/阶段隔离），属于「文档已声明但代码未实现」的契约缺口；另有 6 项建议修改。无安全越权、无数据一致性破坏、无架构边界破坏。

| 结论 | 说明 |
| --- | --- |
| 通过 | ❌ |
| 需修复后交付 | ✅ 1 项必须修改 |
| 阻断性问题 | 无（不阻塞交付方向，但需修复后再 Close Gate） |

---

## 二、详细发现清单

### 必须修改

| ID | 严重级别 | 文件 | 行号（约） | 核心问题 |
| --- | --- | --- | --- | --- |
| M1 | High | `scripts/validation/v3-release-regression.ps1` | L146–L174 / L210–L216 | java-python-contracts 阶段注入 `NO_PROXY` 及一批 `POSTGRES_*` / `AGENTFORGE_*` 环境变量后从不恢复；与变更记录/testing-strategy 声明的「结束后恢复原值、精确清理」冲突，并使 `-Only repository-context-contract` 依赖上一阶段泄漏的 `NO_PROXY` |

### 建议修改

| ID | 严重级别 | 文件 | 行号（约） | 核心问题 |
| --- | --- | --- | --- | --- |
| S1 | High | `scripts/validation/v3-release-regression.ps1` | L224–L229 / L238–L245 / L281–L286 | 调用子 runner（`v2-release-regression.ps1`、`test-plan-change-gates.ps1`、`test-v2-release-regression.ps1`）后不校验 `$LASTEXITCODE`；`status = "PASS"` 仅由「未抛异常」推导，失败关闭保证依赖子脚本实现细节 |
| S2 | Medium | `scripts/validation/v3-release-regression.ps1` | L281–L289 | `-Only` 选择性运行同样输出 `status = "PASS"`，与完整 Release Gate 的 JSON 不可区分，消费方可能把部分验证误读为整体门禁通过 |
| S3 | Medium | `scripts/validation/v3-release-regression.ps1` | L138 / L150 | `$agentToken = "test-only-internal-token"` 为固定字面量并随脚本提交，与 V2-09 策略要求的「运行时随机测试凭据」不一致（同一函数中 `$coreToken`、DB 密码、JWT secret 均已随机） |
| S4 | Medium | `services/core-api/.../Neo4jGraphStore.java` | L34（配合 `lock()` 的 `SET p.revision=... +1`） | 启用重试后事务体会被重复执行；需确认 `GraphProjectLock.revision` 自增在重试下的语义（是否会漂移 CAS/产生假 409），并确认重试配置有自动化断言 |
| S5 | Low | `docs/03-features/README.md`、`docs/01-product/v2-v3-node-roadmap.md`、`docs/07-changes/README.md` | — | 「V3-01 至 V3-08 已实现并通过各自 Milestone Review」缺少 roadmap 中 V3-06 的显式状态；变更索引写 `In Progress` 而变更记录写 `In Review` |
| S6 | Low | `scripts/validation/v3-release-regression.ps1` | L159–L164 | PostgreSQL 就绪轮询依赖「非零退出码不抛异常」的 Windows PowerShell 默认行为；PowerShell 7.4+（`$PSNativeCommandUseErrorActionPreference = $true`）会让首轮 `pg_isready` 失败直接终止阶段 |

### 无需修改

| ID | 文件 | 结论 |
| --- | --- | --- |
| N1 | `README.md`、`docs/01-product/v2-v3-node-roadmap.md` | V3-09 仅标 🚧，`v3-stable` 未被提前宣称，符合公开描述真实性要求 |
| N2 | `ADR-0032`、`docs/03-features/graph-domain-model.md` | 对「仅重试 Neo4j 判定可重试的瞬时事务失败、最多 2 秒、单次事务 5 秒 timeout、最终 503」的描述与 `withMaxTransactionRetryTime(2, SECONDS)` 实际语义一致 |
| N3 | `Neo4jGraphStore.java` | 重试耗尽/非瞬时错误仍走 `Neo4jException → ServiceUnavailableException` 通用 503，不泄露连接串或驱动异常，安全边界未被削弱 |
| N4 | `test-v3-release-regression.ps1`、`Assert-SurefireResult` | 硬编码精确测试数（12 / 1）与 0 skip 是文档声明的刻意契约，非缺陷 |

---

## 三、逐个 Issue 展开

### M1（High）java-python-contracts 阶段环境变量未恢复，且破坏阶段隔离

- **File & Line**：`scripts/validation/v3-release-regression.ps1` ≈L146–L174（注入）、≈L210–L216（清理）
- **Evidence**：

```powershell
$env:POSTGRES_PORT = [string]$postgresPort
$env:POSTGRES_DB = "agentforge"
$env:POSTGRES_USER = "agentforge"
$env:POSTGRES_PASSWORD = $databasePassword
$env:AGENTFORGE_AGENT_INTERNAL_TOKEN = $agentToken
$env:AGENTFORGE_CORE_INTERNAL_TOKEN = $coreToken
$env:AGENTFORGE_JWT_SECRET = ...
...
$env:AGENTFORGE_AGENT_RAG_DB_DSN = "postgresql://..."
$env:AGENTFORGE_AGENT_CHECKPOINT_DB_DSN = $env:AGENTFORGE_AGENT_RAG_DB_DSN
$env:AGENTFORGE_AGENT_RAG_ENABLED = "false"
$env:AGENTFORGE_AGENT_LLM_PROVIDER = "disabled"
$env:NO_PROXY = "127.0.0.1,localhost,::1"          # ← 无条件覆盖，且无快照
$agentProcess = Start-Process ...
```

```powershell
finally {
    ...
    foreach ($name in @("AGENTFORGE_AGENT_CONTRACT_TEST", "AGENTFORGE_RESOLUTION_SMOKE_URL")) {
        Remove-Item "Env:$name" -ErrorAction SilentlyContinue
    }                                              # ← 仅清 2 个，NO_PROXY 及上述全部变量残留
}
```

- **Description**：
  1. 与文档声明直接冲突：`docs/07-changes/2026-10-02-v3-09-integration-release.md` 写明「runner 为本地 loopback 跨进程阶段临时补充 `NO_PROXY=127.0.0.1,localhost,::1`，**结束后恢复原值**；Agent 进程、Compose 容器/网络/卷和临时日志在 `finally` 中清理」；`docs/05-development/testing-strategy.md` 也要求「临时进程、仓库、Compose project、网络、volume、报告和 basetemp 在成功或失败后精确清理」。代码只恢复了零个 loopback 相关变量。
  2. 同文件 `v2-release-regression` 阶段做了严格的 `$previousNoProxy` 快照 + `finally` 还原（≈L231–L252），`java-python-contracts` 却直接覆盖，两处行为不一致，说明是遗漏而非设计。
  3. 阶段隔离被破坏：`repository-context-contract` 阶段同样是 Java→Python 的 loopback 跨进程调用，但自身没有设置 `NO_PROXY`；它能通过只是因为上一阶段把它留在进程环境里。用户执行 `-Only repository-context-contract`（runner 明确支持的能力）时会丢失该保护，可能因代理拦截 127.0.0.1 而失败，即验证结果依赖执行顺序，不可重放。
  4. 环境变量属于进程级，脚本以 `.\script.ps1` 方式执行时不会随子作用域回滚，会污染调用者 shell 会话（含随机 `AGENTFORGE_JWT_SECRET`、DB 密码、`AGENTFORGE_AGENT_LLM_PROVIDER=disabled` 等）。
- **Suggested Fix**（示例，保持最小改动）：

```powershell
function Invoke-JavaPythonContracts {
    ...
    $envSnapshot = @{}
    $envNames = @(
        "POSTGRES_PORT","POSTGRES_DB","POSTGRES_USER","POSTGRES_PASSWORD",
        "AGENTFORGE_AGENT_INTERNAL_TOKEN","AGENTFORGE_CORE_INTERNAL_TOKEN","AGENTFORGE_JWT_SECRET",
        "AGENTFORGE_AGENT_RAG_DB_DSN","AGENTFORGE_AGENT_CHECKPOINT_DB_DSN",
        "AGENTFORGE_AGENT_RAG_ENABLED","AGENTFORGE_AGENT_LLM_PROVIDER",
        "AGENTFORGE_AGENT_CONTRACT_TEST","AGENTFORGE_AGENT_SERVICE_URL",
        "AGENTFORGE_RESOLUTION_SMOKE_URL","NO_PROXY"
    )
    foreach ($n in $envNames) { $envSnapshot[$n] = [Environment]::GetEnvironmentVariable($n, "Process") }
    try { ... }
    finally {
        ...
        foreach ($n in $envNames) {
            if ($null -eq $envSnapshot[$n]) {
                Remove-Item "Env:$n" -ErrorAction SilentlyContinue
            } else {
                Set-Item "Env:$n" $envSnapshot[$n]
            }
        }
    }
}
```

若担心 `repository-context-contract` 需要 loopback 直连，应在该阶段自身按 v2 阶段的写法设置并在 `finally` 还原 `NO_PROXY`，而不是依赖前一阶段残留。

---

### S1（High）子 runner 退出码未校验：Release Gate 的失败关闭保证不完整

- **File & Line**：`scripts/validation/v3-release-regression.ps1` ≈L224–L229（runner-contract）、≈L238–L245（v2-release-regression）、≈L281–L286（summary）
- **Evidence**：

```powershell
"v2-release-regression" {
    ...
    if ($Json) {
        $v2Output = @(& $v2Runner -Json 6>&1)
        Write-StageOutput $v2Output
    } else {
        & $v2Runner            # ← 无 $LASTEXITCODE 检查
    }
}
```

```powershell
$summary = [ordered]@{
    release = "V3"
    status = "PASS"            # ← 仅由「未抛异常」推导
    completedStages = @($completedStages)
    ...
}
```

- **Description**：同文件为原生命令专门写了 `Invoke-NativeChecked`（读取 `$LASTEXITCODE` 并在非零时抛错），`test-v3-release-regression.ps1` 也会检查被调用 runner 的 `$LASTEXITCODE`——说明作者清楚 PowerShell 子脚本既可能 `throw` 也可能用 `exit <n>` 结束。V3 runner 对三个子 runner 只依赖异常传播：以 `&` 调用脚本时，子脚本 `exit 1` 只结束子脚本本身并返回调用方，不会终止 V3 runner，于是 `status` 仍会打印 `PASS` 且退出码为 0。这正好击中本节点最核心的「失败关闭」契约。
- **Suggested Fix**：新增一个统一封装并替换三处调用：

```powershell
function Invoke-ChildChecked {
    param([scriptblock]$Action, [string]$Description)
    $output = @(& $Action 2>&1)
    $exitCode = $LASTEXITCODE
    Write-StageOutput $output
    if ($exitCode -ne 0) { throw "$Description failed with exit code $exitCode." }
}
```

---

### S2（Medium）`-Only` 部分运行与完整门禁的 summary 无法区分

- **File & Line**：`scripts/validation/v3-release-regression.ps1` ≈L281–L289
- **Evidence**：`$summary` 只包含 `release/status/completedStages/startedAt/finishedAt`；`-Only` 单阶段运行同样得到 `status = "PASS"`。
- **Description**：`-Only` 的定位是定向复验（变更记录也明确「最终一次性 runner 必须全量」），但 JSON 摘要没有 `requestedStages` 或 `partial: true`，CI/评审脚本若只看 `status` 会把部分验证误判为 Release Gate 通过，与 testing-strategy 中「历史 Node 报告和旧缓存不能替代」「任何未执行项都必须记为未完成」的精神相悖。
- **Suggested Fix**：在 `$summary` 增加 `requestedStages = @($selectedStages.id)` 与 `partial = ($selectedStages.Count -ne $stages.Count)`；`-Only` 时 `status` 输出 `"PASS_PARTIAL"`（或额外字段），完整运行才输出 `"PASS"`。注意同步 `test-v3-release-regression.ps1` 中对 `status -ne "PASS"` 的断言。

---

### S3（Medium）固定内部 token 与「运行时随机测试凭据」策略不一致

- **File & Line**：`scripts/validation/v3-release-regression.ps1` L138（定义）、L150（注入）
- **Evidence**：

```powershell
$agentToken = "test-only-internal-token"
$coreToken = "v309-$([guid]::NewGuid().ToString('N'))"
```

- **Description**：`docs/05-development/testing-strategy.md` V2-09 门槛明确要求 runner「使用运行时随机测试凭据」。同一函数内 DB 密码、`AGENTFORGE_JWT_SECRET`、`AGENTFORGE_CORE_INTERNAL_TOKEN` 均已随机，唯独 Agent 内部 token 为随脚本提交的固定字面量；若该字面量同时硬编码在 Java 契约测试源码中，会形成「测试夹具常量」，也让敏感信息扫描规则失去统一性（虽然仅绑定 127.0.0.1、非生产凭据，风险有限）。
- **Suggested Fix**：与 `$coreToken` 一致改为运行时随机值，并由 Java 契约测试通过 `AGENTFORGE_AGENT_INTERNAL_TOKEN` 环境变量读取；若确因测试源码必须硬编码而保留，请在测试策略或变更记录中显式说明该例外，避免规则与实现相互矛盾。

---

### S4（Medium）Neo4j 有界重试会重复执行事务体，需确认锁计数器语义与测试覆盖

- **File & Line**：`services/core-api/src/main/java/com/agentforge/core/graph/infrastructure/Neo4jGraphStore.java` L34

```java
Config.builder().withConnectionTimeout(2,TimeUnit.SECONDS)
    .withConnectionAcquisitionTimeout(3,TimeUnit.SECONDS).withMaxTransactionRetryTime(2,TimeUnit.SECONDS).build());
```

```java
private void lock(TransactionContext tx, UUID project) {
    tx.run("MERGE (p:GraphProjectLock {id:$project}) SET p.revision=coalesce(p.revision,0)+1", ...);
}
```

- **Description**：驱动级重试会重新执行整个 `TransactionWork`。`MERGE ... SET p.revision = revision + 1` 是非幂等自增：若上一次尝试在「已提交但确认丢失」的模糊结果下重试，锁 revision 会被多计一次。若该 revision 被读取端用作快照/版本一致性或 CAS 依据，可能出现假 409/版本漂移。文档（ADR-0032 修订段、graph-domain-model）只承诺「稳定 ID 幂等」和「不承诺跨库原子快照」，未说明锁计数的重试语义。另需确认新增的 `2s` 配置本身有断言（例如通过 `GraphApiIntegrationTest` 之外的并发用例或配置测试固定），而不是仅由一次并发用例间接覆盖。
- **Suggested Fix**：确认 `GraphProjectLock.revision` 只用于互斥/清理代际而非 CAS 期望值；若被用作版本比较，改为基于稳定 ID 的 `MERGE ... ON CREATE SET`/显式代际比较而非无条件自增，或在驱动重试路径下把锁更新移出可重试事务体。同时在功能文档中补一句「重试不会改变锁计数的可观察语义」或明确其局限。

---

### S5（Low）V3-06 状态缺少 roadmap 单一事实来源，变更索引状态用词不一致

- **File & Line**：`docs/03-features/README.md`（「V3-01 至 V3-08 已实现并通过各自 Milestone Review」）、`docs/01-product/v2-v3-node-roadmap.md`（V3-06 段落无 `**状态**` 条目）、`docs/07-changes/README.md`（`V3-09 …（In Progress）`）对 `docs/07-changes/2026-10-02-v3-09-integration-release.md`（`状态：In Review`）
- **Description**：roadmap 自称是节点状态唯一来源，但 V3-01/02/03/04/05/07/08 都有显式状态行，V3-06 没有；本次又新增了「V3-06 已通过 Milestone Review」的外围表述，缺少 roadmap 侧对应记录。另外变更索引与变更记录的状态用词（In Progress vs In Review）不一致。
- **Suggested Fix**：为 V3-06 在 roadmap 补 `- **状态**：Implemented（…；Milestone Review PASS）`；统一 V3-09 状态用词为 `In Progress`（节点未关闭）或统一为 `In Review`，并在 `docs/07-changes/README.md` 与之对齐。

---

### S6（Low）就绪轮询依赖 Windows PowerShell 的退出码默认行为

- **File & Line**：`scripts/validation/v3-release-regression.ps1` L159–L164

```powershell
for ($attempt = 0; $attempt -lt 60; $attempt++) {
    & docker compose ... exec -T postgres pg_isready -U agentforge -d agentforge *> $null
    if ($LASTEXITCODE -eq 0) { $postgresReady = $true; break }
    Start-Sleep -Milliseconds 500
}
```

- **Description**：轮询的语义是「首轮预期非零退出码，随后继续等待」。在 PowerShell 7.4+ 且 `$PSNativeCommandUseErrorActionPreference` 默认为 `$true` 时，本脚本的 `$ErrorActionPreference = "Stop"` 会让首轮非零退出的 `pg_isready` 直接抛出终止错误，阶段立即失败（虽然仍是失败关闭，但属于环境相关的伪失败，与变更记录中记录的 4/4 PASS 环境不可复现）。
- **Suggested Fix**：在脚本入口固定该偏好（`$PSNativeCommandUseErrorActionPreference = $false`，并对不存在的旧版本用 `Get-Variable` 守卫），或在轮询中显式 `try { ... } catch { }` 包裹 docker 调用。

---

## 四、主开发（Codex）评估回填区

| ID | 是否成立 | 事实判断与原因 | 处理决定 | 修复范围 / 补测 | 备注 |
| --- | --- | --- | --- | --- | --- |
| M1 | 成立 | Java/Python 阶段确实覆盖并泄漏了 `NO_PROXY`、PostgreSQL 与 Agent 变量；Repository 阶段也隐式依赖该泄漏。 | 修复 | 对全部注入变量做 Process snapshot/finally restore；Repository 阶段独立追加并恢复 loopback proxy。相同 sentinel 公共 seam 修复前 exit 1、修复后 PASS；Repository 单阶段 sentinel PASS。 | 阻塞项已消除。 |
| S1 | 成立 | 子 PowerShell runner 的非零 exit 不保证抛异常，原实现可能继续生成 PASS。 | 修复 | 新增 `Invoke-ChildChecked`，在调用前归零、调用后检查退出码并恢复调用者状态；三个子 runner 均经封装。runner contract 与完整 runner PASS。 | 与 M1 同属失败关闭缺口。 |
| S2 | 成立 | 单阶段结果与完整 PASS 不可区分。 | 修复 | summary 增加 `partial`、`requestedStages`；单阶段为 `PASS_PARTIAL`，仅 4/4 完整运行为 `PASS`。契约测试覆盖。 | — |
| S3 | 成立 | live Entity Resolution seam 固定了测试 token。 | 修复 | runner 改用运行时 GUID token，Java live 测试从 `AGENTFORGE_AGENT_INTERNAL_TOKEN` 读取；真实 12-test 跨进程阶段 PASS。 | 不改生产鉴权。 |
| S4 | 不成立 | 全仓检索确认 `GraphProjectLock.revision` 仅在 `lock()` 自增，没有任何读取、CAS、响应或 409 使用；它只用于写锁副作用。 | 不修复 | 既有并发红灯与完整 201-test clean verify 已覆盖；重试后公共结果稳定为 200/404。 | 内部计数不可观察，不构成数据/契约问题。 |
| S5 | 成立 | V3-06 在 roadmap 缺少显式状态，V3-09 索引与记录状态用词不一致。 | 修复 | roadmap 补 V3-06 Implemented/PASS；变更索引统一为 In Review。 | — |
| S6 | 成立 | 原轮询在启用 native-command ErrorAction 偏好时可能把预期未就绪变为终止错误。 | 修复 | runner 在脚本局部检测并关闭 `PSNativeCommandUseErrorActionPreference`，仍由显式 `$LASTEXITCODE` 失败关闭。完整 runner PASS。 | 不污染调用者偏好。 |

---

## 五、边界与证据声明

- 本轮为完全只读审查：未执行任何命令、未修改文件、未变更 Git 状态；全部结论来自提供的 diff、文件清单、变更记录与路线图。
- V3-09 未新增服务、数据表、API 或依赖方向，仅新增验证脚本并修订一处驱动重试配置，符合「禁止新增功能」的节点边界；未发现越界实现下一节点能力。
- 修复 M1（及视情况采纳 S1/S2）后建议重跑一次完整 `v3-release-regression.ps1` 全量（而非 `-Only`），并在变更记录中更新对应证据，再进入 Close Gate 判定；在全部门禁与文档一致性通过前不得标记 V3-09 Implemented，也不得创建 `v3-stable`。
