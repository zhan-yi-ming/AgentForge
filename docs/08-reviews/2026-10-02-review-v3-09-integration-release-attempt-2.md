# Pi 代码审查报告：v3-09-integration-release / Attempt 2

- 日期：2026-10-02
- 审查阶段：v3-09-integration-release
- 审查对象：INDEX@aceb355（基线：aceb35527a9ac614624a535c2d49938e22963f5e）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: PASS

# V3-09 Integration / V3 Release Gate — Milestone Review（第 2 / 3 轮）

## 一、概述与总体结论

- 审查范围：Commit INDEX@aceb355 的 14 个改动文件（1 处生产代码、3 个 validation 脚本/契约、10 份文档）。核心新增为 `v3-release-regression.ps1`、`test-v3-release-regression.ps1`，以及 `Neo4jGraphStore` 的驱动级有界重试。
- 生产代码改动仅一处：`withMaxTransactionRetryTime(0s → 2s)`。方向、范围与 ADR-0032 的「发布收口修订」段、`graph-domain-model.md` 的「仅重试 Neo4j 判定的瞬时事务失败、最多 2 秒、单次事务 5 秒 timeout、最终通用 503」描述一致；未引入跨库原子一致性承诺，未越过 V3-09「禁止新增功能」边界。
- 本次 diff 同时包含上一轮报告文档（`docs/08-reviews/...attempt-1.md`）与变更记录中记录的修复：环境变量 snapshot/restore（M1）、子 runner 退出码校验（S1）、`PASS_PARTIAL` 区分（S2）、运行时随机 Agent token（S3）、V3-06 状态补齐与状态用词（S5）、native command 偏好守卫（S6）。本报告独立复核这些修复在当前代码中的实际落地情况。
- 判定：**通过（PASS）**。无 Must-Fix。剩余 4 项均为不阻塞的建议项（文档用词一致性、`-Plan` 与 `-Only` 的组合语义、`-Only` 去重、环境清单维护）。

| 结论 | 说明 |
| --- | --- |
| 通过 | ✅ |
| 需修复后交付 | ❌ 无必须修改项 |
| 阻断性问题 | 无 |

---

## 二、详细发现清单

### 必须修改

| ID | 严重级别 | 文件 | 行号（约） | 核心问题 |
| --- | --- | --- | --- | --- |
| （无） | — | — | — | 未发现具备明确证据的可运行性、正确性、安全、权限、并发、数据一致性或契约问题 |

### 建议修改

| ID | 严重级别 | 文件 | 行号（约） | 核心问题 |
| --- | --- | --- | --- | --- |
| S1 | Low | `docs/01-product/v2-v3-node-roadmap.md`、`docs/07-changes/README.md`、`docs/07-changes/2026-10-02-v3-09-integration-release.md` | L5 / 首条 / L3 | 节点状态用词仍不统一：roadmap 写 `In Progress`，变更记录与索引写 `In Review` |
| S2 | Low | `scripts/validation/v3-release-regression.ps1` | L30–L60 | `-Plan` 在 `-Only` 校验之前短路返回，`-Plan -Only <unknown>` 以 exit 0 通过，未失败关闭 |
| S3 | Low | `scripts/validation/v3-release-regression.ps1` | L62–L78 | `-Only` 未去重：重复阶段 id 会重复执行并产生重复 `completedStages`，且 `partial` 计算仍按去重前计数 |
| S4 | Low | `scripts/validation/v3-release-regression.ps1` | L150–L176 | 环境变量 snapshot 名单为手工硬编码，契约测试只断言静态布尔 `stageEnvironmentIsolation`，未来新增注入变量会静默泄漏 |

### 无需修改

| ID | 文件 | 结论 |
| --- | --- | --- |
| N1 | `Neo4jGraphStore.java` | 有界重试与 ADR-0032、`graph-domain-model.md` 描述一致；重试耗尽/非瞬时错误仍走 `Neo4jException → ServiceUnavailableException` 通用 503，不泄露连接串或驱动异常；稳定 ID + 唯一约束 + 项目锁保证重试的幂等前提 |
| N2 | `GraphResolutionAdvisorContractTest.java` | live 测试改为从 `AGENTFORGE_AGENT_INTERNAL_TOKEN` 读取 token，并用 `assumeTrue` 守卫；默认 `mvn test` 仍条件跳过，runner 注入随机值后真实执行，仓库中不再保留固定测试凭据 |
| N3 | `v3-release-regression.ps1`（java-python-contracts / repository-context-contract） | M1 的契约缺口已实际修复：注入变量经 Process 级 snapshot 在 `finally` 还原，`Add-LoopbackNoProxy` 为追加式；Repository 阶段自行设置并还原 `NO_PROXY`，不再依赖上一阶段残留，单阶段可重放 |
| N4 | `v3-release-regression.ps1`（summary） | `partial` / `requestedStages` / `PASS_PARTIAL` 已实现，并由 `test-v3-release-regression.ps1` 对单阶段执行结果断言 |
| N5 | `README.md`、`v2-v3-node-roadmap.md` | V3-09 仅标 🚧 / In Progress，未提前宣称 `v3-stable`；V3-08 修正为 Implemented 与 roadmap 一致，公开描述真实性通过 |
| N6 | `Assert-SurefireResult` | 硬编码精确测试数（12 / 1）与 0 skip 失败关闭是文档声明的刻意契约，非缺陷 |

---

## 三、逐个 Issue 展开

### S1（Low）节点状态用词在 roadmap / 变更记录 / 索引之间仍不一致

- **File & Line**：`docs/01-product/v2-v3-node-roadmap.md`（V3-09 状态行 ≈L5–L7）、`docs/07-changes/2026-10-02-v3-09-integration-release.md`（`- 状态：In Review`）、`docs/07-changes/README.md`（索引条目 `（In Review）`）
- **Evidence**：

```text
roadmap:  - Current Node：V3-09 Integration / V3 Release Gate（In Progress；Start Gate 已确认）
roadmap:  - **状态**：In Progress（2026-10-02；Start Gate 已确认）。
change:   - 状态：In Review
index:    ...（In Review）。
README:   - 🚧 V3-09：正在执行完整 Integration / V3 Release Gate；...
```

- **Description**：roadmap 自我声明为节点状态唯一来源，语义上仍是 `In Progress`；上一轮 S5 要求统一用词，本次把变更记录与索引统一到 `In Review`，但 roadmap 未同步，形成“同名状态、两种措辞”。这不影响门禁与运行结果，属于文档一致性问题，不阻塞。
- **Suggested Fix**：二选一并全量对齐：要么 roadmap 改为 `In Review`（并说明该状态表示“正在被独立审查”），要么把变更记录/索引改回节点生命周期词 `In Progress`，在记录内用单独字段表达“已被 Review”。建议采用后者，保持“节点状态只存在于 roadmap”的单一事实来源。

---

### S2（Low）`-Plan` 在 `-Only` 校验之前短路

- **File & Line**：`scripts/validation/v3-release-regression.ps1` ≈L30–L60（`if ($Plan) { ... exit 0 }`）与 ≈L62–L78（阶段校验）
- **Evidence**：

```powershell
if ($Plan) {
    $result = [ordered]@{ ... }
    if ($Json) { $result | ConvertTo-Json -Depth 6 } else { ... }
    exit 0
}

$stageById = @{}
foreach ($stage in $stages) { $stageById[$stage.id] = $stage }
$selectedStages = if ($Only.Count -eq 0) { @($stages) } else {
    foreach ($stageId in $Only) {
        if (-not $stageById.ContainsKey($stageId)) {
            throw "Unknown release stage '$stageId'. Use -Plan to list valid stages."
        }
```

- **Description**：testing-strategy 要求“未知阶段失败关闭”。当前 `-Plan -Only not-a-stage` 会返回计划并 exit 0，未知阶段不会被拒绝。`test-v3-release-regression.ps1` 只覆盖了不带 `-Plan` 的非法阶段路径，因此该组合未被锁定。
- **Suggested Fix**：把阶段解析/校验移到 `$Plan` 之前（或在 `$Plan` 分支内先对 `$Only` 做同样的 `ContainsKey` 校验），确保任何入口对未知阶段都失败关闭。

---

### S3（Low）`-Only` 未去重，重复阶段被重复执行

- **File & Line**：`scripts/validation/v3-release-regression.ps1` ≈L62–L78
- **Evidence**：

```powershell
$selectedStages = if ($Only.Count -eq 0) {
    @($stages)
} else {
    foreach ($stageId in $Only) {
        if (-not $stageById.ContainsKey($stageId)) { throw ... }
        $stageById[$stageId]          # ← 按 $Only 原样输出，未去重
    }
}
...
$completedStages.Add([string]$stage.id)
```

- **Description**：`-Only "v2-release-regression","v2-release-regression"` 会把同一阶段执行两次（跨进程/容器阶段代价高），并使 `completedStages` 出现重复项；`partial` 仍按 `@($selectedStages).Count -ne @($stages).Count` 计算，语义上不受影响但输出噪声。属于健壮性建议。
- **Suggested Fix**：使用 `Select-Object -Unique` 或在收集阶段用 `HashSet` 去重，并保持 `requestedStages` 与 `completedStages` 一致。

---

### S4（Low）环境变量 snapshot 名单为手工维护，未被契约测试锁定

- **File & Line**：`scripts/validation/v3-release-regression.ps1` ≈L150–L176
- **Evidence**：

```powershell
$environmentNames = @(
    "POSTGRES_PORT","POSTGRES_DB","POSTGRES_USER","POSTGRES_PASSWORD",
    "AGENTFORGE_AGENT_INTERNAL_TOKEN","AGENTFORGE_CORE_INTERNAL_TOKEN","AGENTFORGE_JWT_SECRET",
    "AGENTFORGE_AGENT_RAG_DB_DSN","AGENTFORGE_AGENT_CHECKPOINT_DB_DSN",
    "AGENTFORGE_AGENT_RAG_ENABLED","AGENTFORGE_AGENT_LLM_PROVIDER",
    "AGENTFORGE_AGENT_CONTRACT_TEST","AGENTFORGE_AGENT_SERVICE_URL",
    "AGENTFORGE_RESOLUTION_SMOKE_URL","NO_PROXY"
)
```

```powershell
if (-not $plan.conditions.stageEnvironmentIsolation -or -not $plan.conditions.childExitCodesChecked -or
    -not $plan.conditions.runtimeRandomCredentials) { throw ... }
```

- **Description**：M1 已修复（当前名单内的变量都在 `finally` 还原），但“环境隔离”的契约测试只断言 `-Plan` 输出的静态布尔值，无法发现将来新增注入变量却忘记加入 `$environmentNames` 的情况；那时同样的泄漏会以不可见的方式回归。
- **Suggested Fix**（可选，不阻塞）：把注入变量的设置改为数据驱动（例如由一个 `$contractEnvironment` 表同时生成注入与 snapshot），或在契约测试中额外断言注入集合与还原集合相等，使“加入新变量即自动纳入还原”成为结构性保证。

---

## 四、主开发（Codex）评估回填区

| ID | 是否成立 | 事实判断与原因 | 处理决定 | 修复范围 / 补测 | 备注 |
| --- | --- | --- | --- | --- | --- |
| S1 | 成立 | Review 期间的两种过程状态用词不一致，但节点已满足关闭条件。 | 修复 | README、roadmap、system overview、feature/changes 索引和变更记录统一为 Implemented。 | 仅状态回填，不改实现。 |
| S2 | 成立但不阻塞 | `-Plan` 的职责是输出完整计划，当前无 `-Plan -Only` 消费方或误报执行结果；未知执行阶段已失败关闭。 | 不修复 | 记录建议，后续若计划接口支持过滤再统一定义组合语义并补契约。 | 不扩大已 PASS 的 release runner。 |
| S3 | 成立但不阻塞 | 重复 `-Only` 会重复执行，但当前 CI/文档均不传重复值，不影响完整 gate 或单阶段正确性。 | 不修复 | 后续 runner 参数演进时考虑稳定去重。 | 性能/健壮性建议。 |
| S4 | 成立但不阻塞 | 当前手工清单已由 sentinel 公共 seam 验证成功/失败恢复；静态 plan 布尔本身不能防未来维护遗漏。 | 不修复 | 未来新增环境注入时改为数据驱动或扩展 sentinel 契约；当前清单与注入集合已人工核对。 | 可维护性建议。 |

---

## 五、边界与证据声明

- 本轮为完全只读审查：未执行任何命令、未修改文件、未变更 Git 状态；结论仅来自提供的 diff、变更记录、testing-strategy 与 roadmap。
- 未发现安全越权、跨用户/跨项目隔离破坏、未捕获异常、HTTP 状态码契约冲突、乐观锁/幂等破坏或敏感信息进入仓库；生产改动只有驱动重试时长，安全与失败语义未被削弱。
- 变更记录声明的机器证据（4/4 stages PASS、Java 201 tests / 0 failure、条件阶段 12+1 tests / 0 skip、Python 199 passed、Web 73 passed + build 295 modules）来自 Codex 记录，Pi 未执行、不改写为自身结果。
- V3-09 当前未标记 Implemented，未创建/推送 `v3-stable`，符合「全部门禁、文档一致性、V3 Release Milestone Review 与 Node Close Gate 通过前不得 tag」的要求。基于本轮结论，可在完成上述 4 项建议（或明确不采纳的理由）后进入 Node Close Gate。
