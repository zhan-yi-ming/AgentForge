# Pi 代码审查报告：efficient-agent-development-workflow / Attempt 1

- 日期：2026-09-06
- 审查阶段：efficient-agent-development-workflow
- 审查对象：INDEX@382ac71（基线：382ac71f8362c67e95a5aa8889143686f3c2c113）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- REVIEW_RESULT: NEEDS_FIX
- 状态：RESOLVED（由 Attempt 2 `PASS` 闭环）
- Pi 进程超时上限：600 秒

---

REVIEW_RESULT: NEEDS_FIX

# AgentForge 只读代码审查报告

- 审查阶段：`efficient-agent-development-workflow`
- 审查模式：Diff Review（第 1 / 3 轮）
- 审查目标：INDEX@382ac71
- Reviewer：DeepSeek V4-pro（固定模型，未降级）

## 概述与总体结论

**总体结论：存在 1 项必须修改的实质缺陷，修复后再交付。**

本次变更是一条工程治理/流程优化改动，核心产物是新增的 PowerShell 门禁规划器 `scripts/validation/plan-change-gates.ps1` 与其公共 CLI 自检 `scripts/validation/test-plan-change-gates.ps1`，以及将 `AGENTS.md` 与各治理文档切换到“风险等级 × 影响范围”双轴模型。

逐项核验结果：

- 我按 `plan-change-gates.ps1` 的实际分类算法逐行推演了自测脚本的 7 组场景（docs-only / agent-only / TLS / 跨服务 API / Schema / 未知路径 / Milestone / Release Gate），断言与算法输出一致，脚本本身在 `Set-StrictMode -Version Latest` 下无越界取值、无未初始化变量、无 `$LASTEXITCODE` 误判，JSON 序列化/反序列化链路自洽。
- 文档间交叉引用（`AGENTS.md`、`change-workflow.md`、`definition-of-done.md`、`efficient-validation.md`、`testing-strategy.md`、`08-reviews/README.md`）对 L2→Diff Review、L3/节点/Release→Milestone Review、L3 ≠ 全仓等新规则表述一致。
- 未发现敏感信息、密钥/Token 泄漏；Gitleaks 证据与 `git diff --cached --check` 记录合理。
- 未触碰 V1 边界：无 Neo4j/GraphRAG、Langfuse Trace、LiteLLM、MCP 任何产品组件引入。
- 但存在一处**契约级缺陷**：规划器对“共享构建/根级依赖/根级 Compose”这类已知的全仓性路径没有识别分支，会把它们误归入“未知路径”的弱化桶，违反 `efficient-validation.md` 明确写死的强制升级条件，存在“少跑全仓回归”的真实风险——这正是变更记录自述的核心风险“路径规则漏判导致少跑测试”。

---

## 详细发现清单

### 必须修改

| ID | 严重级别 | 文件 | 行号（约） | 核心问题 |
| --- | --- | --- | --- | --- |
| M1 | High | `scripts/validation/plan-change-gates.ps1` | 分类链 86–160 / Unknown 分支 156–160 | 未识别根级共享构建/根级依赖/根级 Compose/CI 等全仓性路径，落为“Unknown → L2 + manual-impact-review”，违反 `efficient-validation.md` 强制升级条件，可能漏跑受影响全仓回归 |

### 建议修改

| ID | 严重级别 | 文件 | 行号（约） | 核心问题 |
| --- | --- | --- | --- | --- |
| S1 | Medium | `scripts/validation/test-plan-change-gates.ps1` | 9–23、48–107 | 公共 CLI 契约自检只覆盖 `-Paths` 单输入模式 + JSON 单输出模式，未覆盖文档主用法 `-BaseRef HEAD -TargetRef INDEX`、`WORKTREE`/双 ref、人类可读摘要及错误路径 |
| S2 | Low | `scripts/validation/plan-change-gates.ps1` | 190–235 | change fingerprint 在不同输入模式下取哈希源不同（工作树文件哈希 vs index/ref blob），且删除文件统一记 `missing`，削弱指纹可比较性 |

### 无需修改

| ID | 严重级别 | 文件 | 行号 | 核心问题 |
| --- | --- | --- | --- | --- |
| N1 | Info | `docs/07-changes/2026-09-06-efficient-agent-development-workflow.md` | 验证结果节 | 变更记录称 AGENTS.md “56 行 → 38 行”，与 diff 头 `@@ -1,75 +1,51 @@` 不一致（可能按非空行/另一修订统计），仅信息性偏差 |
| N2 | Info | `scripts/validation/plan-change-gates.ps1` | fingerprint 段 | 显式 `-Paths` 传入绝对路径时 `Join-Path` 拼接会导致 Test-Path 判为 missing，指纹降级但不影响规划结果 |

---

## 逐个 Issue 展开

### 必须修改

#### M1 — 规划器未识别共享构建/根级依赖/根级 Compose，违反强制升级条件（High）

**Severity**: High

**File & Line**: `scripts/validation/plan-change-gates.ps1`，分类链约第 86–160 行，Unknown 分支约第 156–160 行；对照 `docs/00-governance/efficient-validation.md`「强制升级条件」节。

**Evidence**:

政策明文写死（`efficient-validation.md`）：

> 修改共享构建、根级依赖或会影响所有模块的基础脚本：执行受影响全仓回归。
>
> 以下任一条件不得降级：……

而规划器的分类链只存在：`isDocument` → `isGovernance` → `^apps/web/` → `^services/core-api/` → `^services/agent-service/` → deployment（`infra/`、`scripts/deploy/`、`.env.*.example`）→ 最后 Unknown。

```powershell
foreach ($path in $changedPaths) {
    $isDocument = $path -match '(?i)(^docs/|\.md$)'
    ...
    if ($isGovernance) { ...; continue }
    if ($path -match '^apps/web/') { ...; continue }
    if ($path -match '^services/core-api/') { ...; continue }
    if ($path -match '^services/agent-service/') { ...; continue

    $isTls = ...
    $isDeployment = $path -match '^(scripts/deploy/|infra/|\.env(?:\.production)?\.example$)'
    if ($isDeployment) { ...; continue }
    if ($isDocument) { continue }

    $areas += "Unknown"
    $gates += "manual-impact-review"
    Raise-Risk 2 "At least one path is unknown and requires manual impact review: $path"
}
```

**Description 分析**:

传入根级 `pom.xml`（Maven 父 POM，影响 Java 各模块）、根级 `docker-compose.yml` / `docker-compose.prod.yml`（部署编排）、根级 `package.json`、`.github/workflows/*`、`settings.gradle` 等时，没有任何分支命中，全部落入 Unknown → 输出 `L2`、`manual-impact-review`，**不会**加入 `full-repo-regression`。

这与政策的强制升级条件直接冲突：共享构建/根级依赖变化本应“不得降级、执行受影响全仓回归”，但工具对这类**可分类的已知类别**输出了比政策更弱的门禁。这是本变更自述中最核心的风险落地点——“路径规则漏判导致少跑测试”。虽然 Unknown 分支附带了 `manual-impact-review`，但该分支的政策语义是“无法分类”，而根级构建文件并非无法分类；把它们与 `tools/new-format.bin` 等同对待，等于把政策要强制的全仓回归退化为“人工确认一下”。

**Suggested Fix**:

在 `agent-service` 分支之后、deployment 分支之前，显式加入共享构建/根级依赖识别，命中的加 `full-repo-regression` 并升级到 L3（共享基座属于核心共享基础）：

```powershell
# 新增：共享构建/根级依赖/根级 Compose/CI = 全仓性变化
if ($path -match '(?i)(^pom\.xml$|^(build|settings)\.gradle(\.kts)?$|^package\.json$|^(package-lock\.json|yarn\.lock|pnpm-lock\.yaml)$|^docker-compose[^/]*\.ya?ml$|^\.github/workflows/)') {
    $areas += "SharedBuild"
    $gates += "full-repo-regression"
    Raise-Risk 3 "Shared build, root dependency, or repository-wide base configuration changed."
    continue
}
```

并在 `test-plan-change-gates.ps1` 增加对应代表场景（如 `Invoke-Plan -Paths @("pom.xml")` 断言 L3 / Milestone / 含 `full-repo-regression`）。

---

### 建议修改

#### S1 — 公共 CLI 自测未覆盖 Git 派生模式、人类可读摘要与错误路径（Medium）

**Severity**: Medium

**File & Line**: `scripts/validation/test-plan-change-gates.ps1`，`Invoke-Plan` 9–23 行；7 组场景 48–107 行。

**Evidence**:

```powershell
function Invoke-Plan {
    param([string[]]$Paths, [switch]$Milestone, [switch]$ReleaseGate)
    $parameters = @{ Paths = $Paths; Json = $true }
    ...
    $raw = & $planner @parameters
    return ($raw -join "`n") | ConvertFrom-Json
}
```

全部 7 组场景都以显式 `-Paths` 进入 planner，且只断言 JSON 输出。

**Description 分析**:

- 规划器实际有四条输入路径（显式 `-Paths`、`INDEX`、`WORKTREE`、任意两个 Git ref）和两条输出路径（`-Json` 与人类可读摘要），并有一条显式失败路径（无变更时 `throw "No changed paths..."`）。
- 文档主用法 `.\scripts\validation\plan-change-gates.ps1 -BaseRef HEAD -TargetRef INDEX -Json`（同时出现在 `efficient-validation.md`、`pi-review-connection.md` 与本次变更记录的验证结果里）在自测中没有任何自动化覆盖。
- 变更记录把 seam 定义为“命令行 JSON、退出码和人类可读摘要”，但人类可读摘要分支与失败退出码契约均未断言。
- 实际影响：若 `Invoke-GitLines`、路径规范化、INDEX 指纹等 Git 链路回归损坏，7 组场景全绿也无法发现，而这些是本工具真实生产调用中唯一会用到的路径。

**Suggested Fix**:

在 `test-plan-change-gates.ps1` 增加至少一组 Git 派生场景（可用临时 git 仓库或直接对当前工作树执行 `-TargetRef WORKTREE -Json` 只断言输出形状，避免依赖具体仓库内容），并补充：① 非 `-Json` 摘要冒烟；② 无变更时退出码非 0（`try/catch` 断言 throw）。将“7 组代表场景”更新为覆盖输入/输出/失败三类契约的描述。

---

#### S2 — change fingerprint 各模式哈希源不同，删除文件统一记 `missing`（Low）

**Severity**: Low

**File & Line**: `scripts/validation/plan-change-gates.ps1`，fingerprint 段约 190–235 行。

**Evidence**:

```powershell
$contentHash = if ($Paths.Count -gt 0 -or $TargetRef -eq "WORKTREE") {
    (Get-FileHash ... SHA256)        # 工作树文件内容
} elseif ($TargetRef -eq "INDEX") {
    ($indexEntry[0] -split '\s+')[1] # git index blob
} else {
    $objectId[0].Trim()              # 目标 ref blob
}
# 删除文件：Test-Path 为 false / rev-parse 失败 → "missing"
```

**Description 分析**:

同一批路径在不同模式下取的是“当前工作树内容”“已暂存 blob”“目标 ref blob”三种不同来源，fingerprint 在不同模式间不可直接比较；删除文件一律记 `missing` 也降低了指纹对变更集合的区分度。由于该指纹的用途是“本次 change fingerprint”并写回变更记录用于证据复用判据，跨模式不可比虽不阻断运行，但会削弱后续“同一任务内证据复用”判定的可靠性。

**Suggested Fix**:

（可选）统一为“对每个路径计算 diff 输出第 2 阶段的内容哈希”，或至少在变更记录中明确 fingerprint 只对同一 source 模式有效、跨模式不比较；删除文件可记 `deleted` 而非 `missing` 以保留语义。

---

### 无需修改

#### N1 — AGENTS.md 行数统计与 diff 头不一致（Info）

**File & Line**: `docs/07-changes/2026-09-06-efficient-agent-development-workflow.md`，「验证结果」节。

> `AGENTS.md` 从 56 行/3467 字符缩短为 38 行/2709 字符

而 git diff 头为 `@@ -1,75 +1,51 @@`（旧 75 行 → 新 51 行）。两者对不上，且差异量（75-56=19 行 vs 51-38=13 行）不像是统一的空行扣除。推测来自另一次修订的统计口径，不影响交付正确性，建议在回填时顺手校准口径。

#### N2 — 显式 `-Paths` 传绝对路径时指纹判 missing（Info）

**File & Line**: `scripts/validation/plan-change-gates.ps1`，fingerprint 段 `Join-Path $projectRoot $path`。

绝对路径与 `Join-Path` 拼接会产生非法路径，`Test-Path` 失败记为 `missing`。仅影响指纹精度，不影响 risk/areas/gates 规划结果，按个人偏好处理即可。

---

## 主开发（Codex）评估回填区

> 由 Codex 在复审前逐条填写，Pi 不代填。

| Issue ID | Codex 结论（采纳 / 误报 / 豁免） | 判定依据 | 修复或记录位置 |
| --- | --- | --- | --- |
| M1 | 采纳 | 共享构建属于已知强制升级路径，当前 Unknown/L2 与正式文档冲突。 | `plan-change-gates.ps1` 增加 `SharedBuild` L3/full-repo 分类；自检先复现 L2 红灯再通过。 |
| S1 | 采纳 | Git 派生输入与人类/失败输出是已声明公共 CLI 契约，应有最小自动化覆盖。 | `test-plan-change-gates.ps1` 增加临时 Git 仓库 INDEX/WORKTREE/ref、摘要与无变更失败测试。 |
| S2 | 记录契约 | source 已进入 fingerprint；证据复用只在相同 source/路径集合内比较，不要求跨模式相等。 | 在 `efficient-validation.md` 明确限制。 |
| N1 | 采纳信息修正 | 原统计使用 `Measure-Object -Line`，与物理行口径不同。 | 变更记录修正为 75 → 51 物理行。 |
| N2 | 采纳并收紧 | 显式绝对路径不符合仓库路径分类语义，应明确失败。 | `-Paths` 拒绝绝对路径；自检先复现静默成功红灯再通过。 |

---

**复审说明**（供下一轮）：本报告为 Diff Review，仅聚焦本次 14 个文件的变更与新增两个脚本的公共 CLI 行为；未扩展到产品模块或架构重设计。确认 M1 修复并补齐相应自测后，建议回到 `REVIEW_RESULT: PASS`。
