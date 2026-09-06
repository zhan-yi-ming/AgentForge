# Pi 代码审查报告：efficient-agent-development-workflow / Attempt 2

- 日期：2026-09-06
- 审查阶段：efficient-agent-development-workflow
- 审查对象：INDEX@382ac71（基线：382ac71f8362c67e95a5aa8889143686f3c2c113）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- REVIEW_RESULT: PASS
- 状态：RESOLVED
- Pi 进程超时上限：600 秒

---

REVIEW_RESULT: PASS

# AgentForge 只读代码审查报告

- 审查阶段：`efficient-agent-development-workflow`
- 审查模式：Diff Review（第 2 / 3 轮）
- 审查目标：Commit INDEX@382ac71（382ac71f8362c67e95a5aa8889143686f3c2c113 .. INDEX@382ac71）
- Reviewer：DeepSeek V4-pro（固定模型，未降级）

## 概述与总体结论

**总体结论：通过。上一轮 5 项发现已全部处理，本次未发现必须修改的阻断项。**

逐项复核 Attempt 1 发现：

- **M1（共享构建未识别 → 已修复）**：`plan-change-gates.ps1` 现于第 98–104 行显式识别根级 `pom.xml`、Gradle 构建/配置、根级依赖与锁文件、根级 Compose、`Makefile`、`.github/workflows/`，命中即 `SharedBuild + full-repo-regression + L3`；`test-plan-change-gates.ps1` 增加 `pom.xml` 用例断言 `L3/Milestone/full-repo-regression`。
- **S1（Git 派生模式、人类摘要与失败契约未覆盖 → 已修复）**：测试脚本现通过临时 Git 仓库覆盖 `WORKTREE`、`INDEX`、双 ref、无变更失败、人类可读摘要与非法绝对路径。
- **S2（fingerprint 跨模式不可比 → 已记录契约）**：`efficient-validation.md` 明确 fingerprint 只在相同 source 与路径集合内比较。
- **N1（AGENTS.md 行数口径 → 已修正）**：变更记录已改为 75 物理行 → 51 物理行、3467 → 2709 字符。
- **N2（绝对路径静默成功 → 已收紧）**：`-Paths` 对 rooted 路径显式抛出 `repository-relative paths only`，并有红灯测试。

未发现敏感信息、权限绕过、并发/幂等、数据一致性、产品模块越界或 V2/V3 组件引入问题；脚本使用 PowerShell、Git 与 .NET 标准 SHA256 实现，符合本次“不新增运行时依赖”的非目标。以下为 2 项非阻塞建议与 1 项信息性确认。

## 详细发现清单

### 必须修改

无。

### 建议修改

| ID | 严重级别 | 文件 | 行号（约） | 核心问题 |
| --- | --- | --- | --- | --- |
| S-1 | Medium | `scripts/validation/plan-change-gates.ps1` | 117–124 | Core API 安全路径正则中的 `auth` 未做边界限定，会把 `Author*.java` 等普通领域类误判为安全/权限变更，产生虚假 L3 与 `database-integration`/Milestone Review |
| S-2 | Low | `scripts/validation/plan-change-gates.ps1` | 82–95 | `isGovernance` 将治理文档与 `skills/` 变更和 PowerShell 校验脚本混在同一分支，纯 Markdown 变更也会被追加 `powershell-parser` 与 `gate-planner-contract` |

### 无需修改

| ID | 严重级别 | 文件 | 行号 | 核心问题 |
| --- | --- | --- | --- | --- |
| N-1 | Info | `docs/08-reviews/2026-09-06-review-efficient-agent-development-workflow-attempt-1.md` | N1 小节 | 历史报告仍记录旧口径 56 行/38 行，但同报告 Codex 评估区已采纳 N1 修正，变更记录也已更正为 75/51，无需处理 |

## 逐个 Issue 展开

### 建议修改

#### S-1 — Core API 分类正则中 `auth` 子串边界过宽，`Author` 类会被误判为安全/权限变更（Medium）

**Severity**: Medium

**File & Line**: `scripts/validation/plan-change-gates.ps1`，第 117–124 行，尤其第 121 行。

**Evidence**:

```powershell
if ($path -match '^services/core-api/') {
    $areas += "CoreApi"
    $gates += "java-clean-verify"
    Raise-Risk 2 "Core API implementation or configuration changed."
    if ($path -match '(?i)(/db/migration/|/security/|auth|jwt|permission|quota|approval|idempot)') {
        $gates += "database-integration"
        Raise-Risk 3 "Schema or security-sensitive Core API behavior changed."
    }
    ...
}
```

**Description 分析**:

`auth` 没有 `/` 前缀、没有词边界，`Author.java`、`AuthorController.java`、`LibraryAuthorDTO.java` 等路径中包含 `Author` 字样的普通领域类也会命中该分支，被输出为 L3、Milestone Review，并追加 `database-integration` 门禁。这不会造成少跑测试的安全风险，但属于明确误报，会违反本次变更“根据真实影响域选择最低充分验证”的目标，并可能对后续普通功能修改产生不必要的审核与集成测试成本。

**Suggested Fix**:

将 `auth` 改为带边界或词根的表达式，只匹配真正的安全包/安全类；同时补充一个反向用例，防止 `Author` 类被升级：

```powershell
if ($path -match '(?i)(/db/migration/|/security/|\bauth(?:entication|orization)?|jwt|permission|quota|approval|idempot)') {
    ...
}
```

建议在 `test-plan-change-gates.ps1` 增加例如对 `services/core-api/src/main/java/com/agentforge/core/author/AuthorController.java` 的断言：风险应为 L2 或按“public API controller”判定，但不应因 `Author` 中的 `auth` 子串触发 `database-integration`。

---

#### S-2 — `isGovernance` 将纯文档/技能变更与 PowerShell 脚本变更混用同一门禁（Low）

**Severity**: Low

**File & Line**: `scripts/validation/plan-change-gates.ps1`，第 82–95 行。

**Evidence**:

```powershell
$isGovernance = (
    $path -eq "AGENTS.md" -or
    $path -match '^docs/00-governance/' -or
    $path -eq 'docs/05-development/testing-strategy.md' -or
    $path -eq 'docs/06-operations/pi-review-connection.md' -or
    $path -eq 'docs/08-reviews/README.md' -or
    $path -match '^scripts/validation/(test-)?plan-change-gates\.ps1$' -or
    $path -match '^skills/'
)
if ($isGovernance) {
    $areas += "Governance"
    $gates += @("powershell-parser", "gate-planner-contract")
    Raise-Risk 2 "Governance or validation policy changes future delivery behavior."
    continue
}
```

**Description 分析**:

`docs/00-governance/` 下的 Markdown 治理文档、`AGENTS.md`、`docs/05-development/testing-strategy.md` 以及 `skills/` 下的 SKILL.md 都属于纯文档/流程说明；单独修改这些文件时，规划器仍会追加 `powershell-parser` 与 `gate-planner-contract`，即使没有任何 `.ps1` 文件变化。这比 `efficient-validation.md` 中“Docs only：文档一致性、diff check、敏感扫描”的最低门禁更重，属于过度门禁；不会导致漏测，但会削弱本次优化目标。

**Suggested Fix**:

把“Governance 文档影响”与“校验脚本变更”拆开，仅对真实 PowerShell 校验脚本附加 parser/contract 门禁：

```powershell
$isValidationScript = $path -match '^scripts/validation/(test-)?plan-change-gates\.ps1$'

foreach ($path in $changedPaths) {
    ...
    if ($isGovernancePath) {
        $areas += "Governance"
        Raise-Risk 2 "Governance policy changes future delivery behavior."
        if ($isValidationScript) {
            $gates += @("powershell-parser", "gate-planner-contract")
        }
        continue
    }
}
```

---

### 无需修改

#### N-1 — Attempt 1 报告中的 N1 旧口径仅为历史记录，已在同报告内闭环（Info）

**File & Line**: `docs/08-reviews/2026-09-06-review-efficient-agent-development-workflow-attempt-1.md`，「无需修改 → N1」小节。

Attempt 1 报告原文仍描述 `AGENTS.md` 从 56 行/38 行变化，但该报告附带的「Codex 评估回填区」已明确：`N1 采纳信息修正，变更记录修正为 75 → 51 物理行`；`docs/07-changes/2026-09-06-efficient-agent-development-workflow.md` 的验证结果也已使用 75/3467 → 51/2709 口径。历史报告作为当时快照保留，无需返工。

## 主开发（Codex）评估回填区

> 由 Codex 在复审前逐条填写，Pi 不代填。

| Issue ID | Codex 结论（采纳 / 误报 / 豁免） | 判定依据 | 修复或记录位置 |
| --- | --- | --- | --- |
| S-1 | 豁免（非阻塞） | `Author` 误升只会扩大门禁，不会漏测；当前仓库无该路径，本次在 PASS 后继续修改会触发第三次复审，收益不足。 | 作为规划器精度候选，在首次真实误判时用反向契约测试修复。 |
| S-2 | 豁免（非阻塞） | 治理文档多跑 parser/contract 属安全侧过度验证，当前完整检查仅约 3 秒；拆分不是本次正确性要求。 | 后续若治理文档任务的门禁成本形成可测瓶颈，再独立优化。 |
| N-1 | 确认无需修改 | Attempt 1 是历史审核快照，Codex 回填与正式变更记录均已采用 75 → 51 物理行口径。 | Attempt 1 评估区与本次变更记录。 |

---

**复审说明**：本报告为 Diff Review（第 2 轮），仅复核本次 15 个文件及两个新增脚本的公共 CLI 契约；未扩大至产品模块或架构重设计。因无“必须修改”项，结论为 `PASS`。
