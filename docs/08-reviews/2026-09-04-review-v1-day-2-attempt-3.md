# Pi 代码审查报告：v1-day-2 / Attempt 3

- 日期：2026-09-04
- 审查阶段：v1-day-2
- 审查对象：468f280d1d44d8bfb00e21c92831fee02d8479e4（基线：de08b54965a829bbc5bcb2625b5e630ecbc41c64）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- REVIEW_RESULT: NEEDS_FIX
- Pi 进程超时上限：300 秒

---

REVIEW_RESULT: NEEDS_FIX

# AgentForge v1-day-2 代码审查报告（第 3/3 轮）

- 日期：2026-09-04
- 审查阶段：v1-day-2
- 审查轮次：3 / 3
- 审查对象：`468f280d1d44d8bfb00e21c92831fee02d8479e4`（基线：`de08b54965a829bbc5bcb2625b5e630ecbc41c64`）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- Pi 进程超时上限：600 秒
- Diff 状态：已截断（仅首/中/尾采样可见）；且 diff 中的中文呈现为 UTF-8 被按 GBK 解码的 mojibake，文档侧核验受限。

---

## 概述与总体结论

- **结论**：需修复后交付（NEEDS_FIX）
- **总体判断**：上一轮 N1（删除乐观锁缺少过期版本拒绝测试）与 R1 的根因已在本轮得到实质修复——`WikiPageServiceTest` / `TaskServiceTest` 均新增 `deleteRejectsStaleVersionWithoutDeleting` 用例，显式断言 `ConflictException` 且 `repository.delete` 从未执行，证明生产 `delete` 路径真正执行版本校验；同时补充了“先鉴权后查询”的负向测试，安全侧明显加强。但 N4（编排状态机自动化测试）仅以最小冒烟脚本部分关闭：新增 `Test-ReviewBridge.ps1` 的覆盖范围（AST、BUSY、异常释放锁、DryRun 不写状态、JSON 输出）不包含状态机最关键的“三次 NEEDS_FIX 上限”与“PASS 只写一次 NEXT_STAGE_READY”分支，这些人类接管边界仍无自动化证明。此外，N3 的容错实现与新增 STALLED 判定逻辑因 diff 截断无法在证据中核验，仅可依据 Codex 回填与 +117 行变更规模推定。
- **阻断性**：未发现已证实的生产运行阻断 Bug；当前问题是测试覆盖面与可验证性缺口，不满足“与改动相称的自动化测试”的 Definition of Done，因此不允许 PASS。

---

## 详细发现清单

| ID | 严重级别 | 文件 | 行号 | 核心问题 |
|----|---------|------|------|----------|
| F1 | Medium | `scripts/agent-bridge/Test-ReviewBridge.ps1`、`review-loop.ps1` | 文件级 | 冒烟脚本只覆盖 AST/BUSY/DryRun/JSON 等基础设施分支，未覆盖状态机业务关键分支：三次 NEEDS_FIX → HUMAN_REQUIRED、PASS 只写一次 NEXT_STAGE_READY、锁占用不消耗 attempt |
| F2 | Low | `scripts/agent-bridge/Show-ReviewStatus.ps1`、`Start-BridgeMonitor.ps1` | 文件级 | N3 容错与新增 STALLED 判定/`-Restart` 恢复逻辑因 diff 截断无法核验，且新增判定逻辑无独立测试，恢复动作的幂等性未被证明 |
| F3 | Low | `docs/02-architecture/decisions/ADR-0008-*.md` 等全部新改中文文档 | 文件级 | 审查证据中中文为 mojibake，ADR-0008 与编排/运维文档的决策内容无法完整核验，与“文档先行”（ADR-0001）的验证要求相抵触 |

---

## 逐项展开

### F1 — 状态机业务关键分支仍无自动化测试

- **Severity**：Medium
- **File & Line**：`scripts/agent-bridge/Test-ReviewBridge.ps1`（新增 51 行）、`scripts/agent-bridge/review-loop.ps1`（+31 行）
- **Evidence**：
```powershell
# scripts/agent-bridge/README.md（本轮新增说明，见 diff 中段）
- `Test-ReviewBridge.ps1`：不调用 Pi 的回归入口，验证脚本语法、BUSY 互斥、异常释放锁、DryRun 不写状态和 JSON 状态输出。
```
而 `docs/03-features/review-orchestration.md` 明确要求：
```
- 同一阶段最多三次审查；第三次仍需修复时，状态为 HUMAN_REQUIRED，必须等待用户确认。
- NEXT_STAGE_READY 只表示下一路线图阶段具备计划条件；必须等待用户明确确认后才能开始实现，heartbeat 不得自行消费为开发授权。
```
- **Description**：上一轮 N4 要求为状态机的三个关键分支（三次 NEEDS_FIX 上限、PASS 单次写入 NEXT_STAGE_READY、锁占用不消耗 attempt）提供自动化覆盖。本轮新增的 `Test-ReviewBridge.ps1` 按 README 自述仅覆盖“语法、BUSY 互斥、异常释放锁、DryRun 不写状态、JSON 输出”——全部是脚本基础设施行为，不触及 attempt 计数、`HUMAN_REQUIRED` 迁移与 `NEXT_STAGE_READY` 幂等消费。这三处正是人工接管安全边界与并发推进正确性的核心，仍完全依赖人工/手工验证。Codex 回填自评为“最小实现”，但最小实现并未覆盖上一轮点名的关键分支，N4 只能视为部分关闭。
- **Suggested Fix**：无需引入 Pester 依赖，沿用现有“无 Pi 冒烟脚本”思路，为 `review-loop.ps1` 增加可注入状态文件的纯函数化迁移层（或 `-DryRun` 场景），断言以下三条：
  1. 同一 stage 第三次 `NEEDS_FIX` 后状态置为 `HUMAN_REQUIRED`，且第 4 次执行不再触发 Pi；
  2. `PASS` 只写一次 `NEXT_STAGE_READY`，重复执行同一提交不重复推进、heartbeat 不二次消费；
  3. 跨进程锁被占用时，attempt 计数不递增。
  若仍评估超出 V1 复杂度，必须在变更记录中逐条写出豁免理由，并标注“人工验证指令”作为最低可复现手段。

---

### F2 — Show-ReviewStatus 容错与 STALLED 恢复逻辑不可核验

- **Severity**：Low
- **File & Line**：`scripts/agent-bridge/Show-ReviewStatus.ps1`（+117/−）、`Start-BridgeMonitor.ps1`（+14/−）
- **Evidence**：
```powershell
# diff evidence（截断）仅展示文件名与改动统计，未展示具体实现：
scripts/agent-bridge/Show-ReviewStatus.ps1  | 117 ++++++++--
scripts/agent-bridge/Start-BridgeMonitor.ps1 |  14 +-
```
README 新增用法：`Show-ReviewStatus.ps1 -Watch`、`Start-BridgeMonitor.ps1 -Restart`（用于 `STALLED` 恢复）。
- **Description**：上一轮 N3 要求对半写入/损坏 JSON 与空 PID 文件做结构化容错。本轮 `Show-ReviewStatus.ps1` 改动规模（+117 行）与 Codex 回填（损坏 JSON → 显式 `INVALID`、PID 仅纯数字解析）方向一致，但 diff 截断导致具体实现不可见，无法确认 `try/catch` 是否覆盖全部读取路径、`ConvertFrom-Json` 的 `-ErrorAction` 语义是否正确。同时本轮新增 `STALLED` 判定（锁长占 + 无 Pi 子进程 + 状态无推进）与 `-Restart` 恢复入口，这是新的编排逻辑：误判会触发非必要重启，漏判会让自动化静默卡死，恢复动作是否幂等也未验证，且无任何独立测试覆盖。
- **Suggested Fix**：在回填区贴出 `Show-ReviewStatus.ps1` 的状态读取函数与 `STALLED` 判定片段，供下一轮核验；为 `-Restart` 增加“已锁时拒绝或等待、重复执行不产生第二个 monitor”的冒烟断言。若 `STALLED` 判定包含时间阈值，需明确阈值常量并说明误判风险。

---

### F3 — 审查证据编码损坏限制文档核验

- **Severity**：Low
- **File & Line**：`docs/02-architecture/decisions/ADR-0008-*.md`、`docs/03-features/review-orchestration.md` 等本轮全部新改中文文档
- **Evidence**（diff 中原文，mojibake）：
```
# ADR-0008锛氬崟宸ヤ綔鏍戝鏌?Worker銆佸彲瑙傛祴鐘舵€佷笌淇鍏宠仈
- 鐘舵€侊細Accepted
- 鏃ユ湡锛?026-09-04
```
- **Description**：diff 中所有中文均以 UTF-8 字节被按 GBK 解码后的乱码呈现（如 `锛?026-09-04` 实为 `：2026-09-04`）。鉴于上一轮报告中同样的文件可正常以中文读取，判断为 diff 提取管道的编码损坏，而非仓库文件实际损坏；但这直接导致 ADR-0008 的锁定释放要求、`Review-Fixes` trailer 关联规则、STALLED 定义等技术约束无法被本文本级核验。在“文档先行”的项目治理下，无法阅读的文档等同于未验证的契约。
- **Suggested Fix**：无需 Codex 改代码，但需在下一次 review 输入中以 UTF-8 重新生成 diff 证据，或明确标注“编码损坏、文档内容按上一轮 UTF-8 文本核验”的处理声明；若确认仓库文件本身为 GBK 编码，则应立即转存为 UTF-8 并修复提交。

---

## 主开发（Codex）评估回填区

| 发现 ID | 是否采纳 | 处理方式 | 验证结果 |
|--------|---------|---------|---------|
| F1 | 接受 | 提取纯函数并覆盖 PASS、第三次失败、确定性重复应用和锁占用不消耗 attempt；Pi 在 PS5/PS7 均实测通过。 | 已关闭 |
| F2 | 接受 | 状态脚本支持注入运行目录，损坏 JSON 与 STALLED 回归由 Pi 在 PS5/PS7 实测通过。 | 已关闭 |
| F3 | 接受 | 审查包装器显式设置原生命令 UTF-8 输出编码，后续报告以 UTF-8 输入。 | 已关闭 |

---

## 上一轮问题关闭情况核验

| 上一轮 ID | 判断 | 说明 |
|----------|------|------|
| N1 | 关闭 | `WikiPageServiceTest` / `TaskServiceTest` 均新增 `deleteRejectsStaleVersionWithoutDeleting`，断言 `ConflictException` 且 `repository.delete` 从未执行；该测试在不改动生产代码的前提下即可证明乐观锁校验真实存在 |
| N2 | 部分关闭（证据受限） | `ResourceApiTest` +18 行与 Codex 回填一致，但截断证据未直接展示 `taskGetMapsNotFoundAndForbiddenContracts`；本轮不能反向确认已实现，建议回填确认 |
| N3 | 部分关闭（证据受限） | `Show-ReviewStatus.ps1` +117 行与 README 的 `-Watch`/`INVALID` 描述表明已改造，但具体容错实现不可见；新增 `STALLED`/`-Restart` 亦无独立测试，已立为 F2 |
| N4 | 部分关闭 | `Test-ReviewBridge.ps1` 已新增（README 条目可见），但覆盖为 AST/BUSY/异常释放锁/DryRun/JSON，未触及三次上限与 PASS 单次写入口，已立为 F1 |

> 说明：本轮为只读审查，未修改任何文件。Codex 恢复后请按上表逐项回填，不要机械照改；F1 若评估认为超出 V1 复杂度，须在回填区明确豁免理由与可复现的人工验证命令。
