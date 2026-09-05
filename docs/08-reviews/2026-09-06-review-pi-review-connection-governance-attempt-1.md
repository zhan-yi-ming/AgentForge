# Pi 代码审查报告：pi-review-connection-governance / Attempt 1

- 日期：2026-09-06
- 审查阶段：pi-review-connection-governance
- 审查对象：WORKTREE@852dc0d（基线：852dc0dcd566d47b66857ff3a1078ee246f52f55）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- REVIEW_RESULT: NEEDS_FIX
- Pi 进程超时上限：600 秒

---

REVIEW_RESULT: NEEDS_FIX

# Pi 只读审查报告

## 概述与总体结论

本轮审查范围为 `pi-review-connection-governance` 变更（WORKTREE@852dc0d），共 14 个文档 + 1 个 README，全为治理文档与说明性内容，未发现代码级 Bug、跨用户越权、API 契约冲突、并发/幂等缺陷，也未发现真实凭据或绝对路径泄漏（`C:\path\to\your\pi.cmd` 为占位符；`2026-09-06` 变更记录明确未把绝对路径写入仓库）。变更主旨（恢复“提交前一次性 Pi 只读审核”、固化 `AGENTFORGE_PI_CMD` 优先 / PATH 兜底、禁止 monitor/validation 复活）整体方向正确，未越出 V1 边界。

但存在 **3 个实质性治理矛盾 / 验证缺口**（可能诱导 Codex 重启已禁用的 monitor、未实际拦截模型降级、模型选择器字符串跨文档不一致）以及 3 个低严重度文档不一致。全部可通过纯文档修改闭环，无运行时代码阻断；按“任何真实问题或测试缺口均判 NEEDS_FIX”的审查口径，结论为 **NEEDS_FIX**。

---

## 详细发现清单

| ID | 严重级别 | 文件（行号按 diff 估算） | 核心问题 |
|----|----------|--------------------------|----------|
| 1 | Medium | `docs/06-operations/review-orchestration.md`：排错顺序 步骤 2（约 L54–56） | 排错步骤仍指示运行已被明确禁止的 `Start-BridgeMonitor.ps1 -Restart`，与 `pi-review-connection.md`「禁止入口」直接冲突 |
| 2 | Medium | `docs/06-operations/pi-review-connection.md`：十秒预检 代码（约 L36–47） | 预检只校验 `$LASTEXITCODE -ne 0`，未校验输出是否真的包含 `deepseek/deepseek-v4-pro`，硬模型约束无自动化兜底，可能静默通过 Flash 降级 |
| 3 | Medium | `docs/06-operations/pi-review-connection.md`：输出必须包含（约 L49–52） vs `docs/07-changes/2026-09-06-...`：实施与验证回填（约 L41–45） | 模型目录“必须显示”的字符串一个为 `deepseek  deepseek-v4-pro`（空格），另一个记录为 `deepseek/deepseek-v4-pro`（斜杠），选择器输出格式跨文档矛盾 |
| 4 | Low | `AGENTS.md`：项目工作方式（约 L14）；`docs/00-governance/change-workflow.md`：小改动如何处理（约 L54–59） | “所有代码/配置/脚本/文档变更提交前必须过 Pi”的硬门禁，在 Pi 环境故障时无任何批处理或应急通道，且“小改动批量闭环”一段未纳入该门禁，存在实际断流风险 |
| 5 | Low | `docs/00-governance/change-workflow.md`：小改动如何处理（约 L54–59） | 该段未同步更新为“批量/轻微变更仍须提交前过一次性 Pi 审核”，与新增步骤 7 及 `AGENTS.md` 的全量要求不一致，可能被读作轻微变更可跳过 Pi |
| 6 | Low | `docs/08-reviews/README.md`：审查流转机制 第 2 条（约 L8–10） vs `docs/03-features/review-orchestration.md`：范围（约 L12–13） | 报告命名模板两处不一致：`YYYY-MM-DD-review-day-<X>.md` vs `YYYY-MM-DD-review-<stage>-attempt-<n>.md` |

---

## 逐个 Issue 展开

### Issue 1 — 排错步骤仍指示运行已禁用的 `Start-BridgeMonitor.ps1`

- **Severity**: Medium
- **File & Line**: `docs/06-operations/review-orchestration.md`，「排错顺序」步骤 2（约 L54–56）
- **Evidence**（diff 中现存内容）:
  ```
  2. 运行 `Show-ReviewStatus.ps1`；若为 `STALLED`，使用 `Start-BridgeMonitor.ps1 -Restart` 安全停止 PID 文件对应的旧 monitor 并重启。
  ```
  对比 `docs/06-operations/pi-review-connection.md`「禁止入口」：
  ```
  - 禁止 `Start-BridgeMonitor.ps1` 和任何后台轮询。
  ```
- **Description**: 本页 `pi-review-connection.md` 明确声明该页历史 monitor/validation stop 内容不可替代当前流程，但「排错顺序」列表仍保留可操作的 `Start-BridgeMonitor.ps1 -Restart` 指令。该页顶部虽有“Deprecated”与一句提示，但提示位于列表之后、且列表本身像现行操作，Codex 在排障时极易照做重启后台常驻进程，违反“不得恢复 monitor”的红线。这是两文档之间的“真实冲突”。
- **Suggested Fix**:
  1. 将「排错顺序」整段显式标注为“历史流程，当前停用，仅追溯”，或直接删除其中的 monitor 步骤；
  2. 保留步骤 1（按 `pi-review-connection.md` 解析 `AGENTFORGE_PI_CMD` + `--list-models v4-pro`）为唯一现行排障步骤，其余挪到历史说明区；
  3. 把顶部“Deprecated”与“不得用本页历史 monitor 内容替代当前流程”的说明移到「排错顺序」标题的正上方。

---

### Issue 2 — 十秒预检未实际校验模型目录内容，硬模型约束无自动化兜底

- **Severity**: Medium
- **File & Line**: `docs/06-operations/pi-review-connection.md`，「十秒预检」代码块（约 L36–47）
- **Evidence**:
  ```powershell
  & $piCommand --list-models v4-pro
  if ($LASTEXITCODE -ne 0) { throw "Pi model catalog failed with exit code $LASTEXITCODE" }
  ```
  同时 `AGENTS.md` / `scripts/agent-bridge/README.md` 声称：
  ```
  每次调用前检查模型目录...禁止降级...不能用速度换审查质量。
  ```
- **Description**: 预检仅以进程退出码为判定标准。若 `--list-models v4-pro` 退出码为 0 但输出中实际没有 `v4-pro`（例如模型改名、目录只列出 Flash、返回空表但退出 0），脚本不会失败，后续 `run-review.ps1` 才可能隐性降级。文档后文虽写了“输出必须包含 `deepseek  deepseek-v4-pro`”，但那是人工肉眼要求，未被脚本执行，属于验证缺口，与“硬约束”表述不符。
- **Suggested Fix**:
  ```powershell
  $catalog = & $piCommand --list-models v4-pro 2>&1 | Out-String
  if ($LASTEXITCODE -ne 0) { throw "Pi model catalog failed with exit code $LASTEXITCODE" }
  if ($catalog -notmatch 'deepseek[/\s]deepseek-v4-pro|deepseek-v4-pro') {
      throw 'deepseek/deepseek-v4-pro not found in model catalog; refusing to continue.'
  }
  ```
  并将“输出必须包含”的字符串捕获改为脚本强制断言，而非只靠人工核对。

---

### Issue 3 — 模型选择器/目录输出字符串跨文档不一致

- **Severity**: Medium
- **File & Line**: `docs/06-operations/pi-review-connection.md`「输出必须包含」（约 L49–52） vs `docs/07-changes/2026-09-06-pi-review-connection-governance.md`「实施与验证回填」（约 L41–45）
- **Evidence**:
  - `pi-review-connection.md`:
    ```text
    deepseek  deepseek-v4-pro
    ```
  - 同日变更记录证据:
    ```
    模型目录返回 `deepseek/deepseek-v4-pro`
    ```
  - `docs/06-operations/review-orchestration.md`«排错顺序步骤 1»:
    ```
    必须显示 `deepseek/deepseek-v4-pro`
    ```
- **Description**: 三处对“模型目录必须显示”的文本，一个用空格分隔、两个用 `/` 斜杠选择器。真实 `--list-models v4-pro` 只有一种输出格式，不可能同时满足两套记录。目前预检未做字符串匹配所以尚未爆雷，但 Issue 2 修复引入字符串断言后，必须统一这个格式，否则预检会对正确输出误报失败。
- **Suggested Fix**: 以一次真实的 `--list-models v4-pro` 输出为准，统一三处为同一字符串；建议直接采用完整选择器 `deepseek/deepseek-v4-pro`，并在预检断言中同时兼容 `/` 与空白分隔（见 Issue 2 的正则）。

---

### Issue 4 — 全量“提交前必须过 Pi”硬门禁在 Pi 故障时无应急/批处理出口

- **Severity**: Low
- **File & Line**: `AGENTS.md`「项目工作方式」（约 L14）；`docs/00-governance/change-workflow.md`「小改动如何处理」（约 L54–59）
- **Evidence**:
  ```
  所有代码、配置、脚本和文档变更都必须在创建 Git 提交前通过 Pi 审核，除非用户针对本次变更明确豁免并写入变更记录。
  ```
  而「小改动如何处理」一段只写“批量闭环……再统一审查和全量测试”，未提及该统一审查是否包含提交前 Pi 审核，也未定义 Pi 登录/额度故障时（正是本变更要解决的场景）如何继续提交。
- **Description**: 该门禁是设计意图（硬质量门），本身成立；但缺少以下闭环：当 Pi 启动器缺失/登录过期/额度耗尽时（PI 连接指南明确要求“立即停止并请用户处理”），意味着连文档级紧急修复也无法提交，唯一出口是用户对“每一次变更”逐一豁免，容易造成流程断流。这与 2026-09-05 “Codex 快速自主验证”的初衷产生张力。
- **Suggested Fix**: 在一处权威位置补充：Pi 环境不可用时的处理路径——例如“本次变更可整体记录为一次豁免、写明原因与受影响文件范围，待 Pi 恢复后补审归档”，并明确该豁免必须由用户确认；或将豁免粒度定义为“一次变更记录条目”而非“一个文件”，避免逐个文件豁免。

---

### Issue 5 — 「小改动如何处理」未纳入新门禁，存在跳过 Pi 审核的解读空间

- **Severity**: Low
- **File & Line**: `docs/00-governance/change-workflow.md`「小改动如何处理」（约 L54–59）
- **Evidence**:
  ```
  拼写修正或同一主题内的轻微整理，可以更新当前变更记录，不必机械地一字一记录。...
  同一大版本或同一阶段内发现的非阻断问题采用批量闭环：Codex 一次性完成相关文档与整批实现，再统一审查和全量测试。
  ```
- **Description**: 新步骤 7 与 `AGENTS.md` 已把所有变更（含文档、拼写）纳入提交前 Pi 审核；但“小改动”一节未同步，其中的“统一审查”没有明确指向 Pi 审核，“全量测试”对纯文档改动也不适用。Codex 容易据此对拼写修正跳过 Pi 门禁。
- **Suggested Fix**: 在该段末尾补一句：“批量/轻微变更合并为一次提交时，仍须按步骤 7 在提交前完成一次 Pi 只读审核，并按真实情况记录豁免。”

---

### Issue 6 — 报告命名模板两处不一致

- **Severity**: Low
- **File & Line**: `docs/08-reviews/README.md`「审查流转机制」第 2 条（约 L8–10） vs `docs/03-features/review-orchestration.md`「范围」（约 L12–13）
- **Evidence**:
  - `08-reviews/README.md`:
    ```
    审查报告命名规范为：`YYYY-MM-DD-review-day-<X>.md`
    ```
  - `03-features/review-orchestration.md`:
    ```
    每次审查生成独立报告：`YYYY-MM-DD-review-<stage>-attempt-<n>.md`
    ```
- **Description**: 两个模板并存，且实际 stage 名（`day-5-tool-calling-hitl`、`pi-review-connection-governance`）更贴近后者。模板不一致会导致报告归档命名漂移。
- **Suggested Fix**: 统一为包含 stage 与 attempt 的模板 `YYYY-MM-DD-review-<stage>-attempt-<n>.md`，并在 `08-reviews/README.md` 中同步示例。

---

## Codex 评估回填

| 发现 ID | Codex 结论 | 处理说明与证据 | 状态 |
|---------|------------|----------------|------|
| 1 | 采纳 | 当前排错仅保留启动器/模型预检；monitor 重启步骤明确标为禁止执行的历史内容。 | RESOLVED |
| 2 | 采纳 | 预检捕获目录输出，并用 provider/model 同行正则强制断言 V4-pro。 | RESOLVED |
| 3 | 采纳 | 明确目录表两列格式与完整命令选择器是两种表示，变更记录同步。 | RESOLVED |
| 4 | 不采纳 | Pi 环境失败必须立即请用户处理；只有用户明确豁免才提交，不能引入自动绕过门禁。 | ACCEPTED_AS_DESIGNED |
| 5 | 采纳 | 小改动章节明确批量提交仍须完成一次提交前 Pi 审核。 | RESOLVED |
| 6 | 采纳 | 报告命名统一为包含 stage 与 attempt 的格式。 | RESOLVED |

以上 finding 均不涉及不可运行代码、重大安全/数据风险、架构边界或目标偏离。按用户“仅重大问题触发复审”的规则，本批文档修正不再启动 Attempt 2。

---
## 审查边界声明
本轮为纯只读审查，未运行任何命令、未修改文件或 Git 状态；所有行号基于 diff 上下文估算。上述 6 项均为文档一致性与治理闭环问题，不涉及运行时安全、越权或 V1 边界破坏，修复应为低成本的文档同步，无需重跑测试。
