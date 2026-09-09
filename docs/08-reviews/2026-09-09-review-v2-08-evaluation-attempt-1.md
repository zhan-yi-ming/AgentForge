# Pi 代码审查报告：v2-08-evaluation / Attempt 1

- 日期：2026-09-09
- 审查阶段：v2-08-evaluation
- 审查对象：INDEX@28951f3（基线：28951f3c385fcb73a3340df0c1216c832f412f08）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: PASS

## 概述与总体结论

本次 Milestone Review（V2-08 Evaluation Pipeline，Commit INDEX@28951f3）审查了新引入的离线评测包 `agentforge_agent.evaluation`、固定数据集 `v2-small.json`、真实报告 `v2-baseline.json` 以及配套文档。总体结论：**通过，可交付后进入下一轮**。

- 节点 Scope 与路线图 V2-08 一致：固定小数据集、RAG（Recall@K/MRR/Hit Rate）、Answer（Faithfulness）、Tool（Selection Accuracy + Task Success Rate）、可重复 runner + 真实 report；未越界实现 V2-09 Release Gate。
- gold 与 subject 输入正确分离：RAG subject 仅收 `query + corpus`，Tool subject 仅收 `input`，无数据泄漏。
- 无安全/权限/并发/幂等风险：本节点不新增 HTTP 契约、不写业务表、不接 JWT/服务间 token/生产库。
- 指标计算经独立手算复核正确（0.75/0.833/1.0 与报告一致）；Task Success 比较规范化后的完整参数而非仅工具名，符合路线图要求。
- 报告原子写入、dataset 加载失败时不产生半写文件；文档对 Faithfulness 词项代理指标的限制描述诚实。
- 发现问题均为低风险健壮性/一致性建议，没有可复现的必须修改项。

## 详细发现清单

### 必须修改

（无）

### 建议修改

| ID | 严重级别 | 文件 | 行号 | 核心问题 |
| --- | --- | --- | --- | --- |
| S-01 | Low | dataset.py | 165-176 | `_validate_tool_gold` 将任何非 `CREATE_TASK` 的 actionType 一律按 `UPDATE_TASK` 校验，未显式拒绝未知动作类型 |
| S-02 | Low | runner.py | 64 | `subject` 版本硬编码为 `v1`，未绑定真实代码修订，未来代码变更后报告可比性弱 |
| S-03 | Low | docs/03-features/evaluation.md | 5 | 引用 `ADR-0010` 但本次 diff 未包含该 ADR 文件，需确认其已存在，避免悬挂链接 |
| S-04 | Low | docs/07-changes/2026-09-09-v2-08-evaluation.md | 3-4 | 变更记录状态为 `In Progress`，而功能文档/README 已标 `Implemented / 已完成`，建议 Close Gate 后统一 |

### 无需修改

- 指标定义与手算结果（Recall@K、截断 MRR、Hit Rate、宏平均、Faithfulness 阈值 0.8）。
- gold 泄漏防护与 Tool 参数全量比较逻辑。
- 原子 JSON 写入与失败不残留半写报告。
- dataset schema/重复 ID/悬空 gold/非法 K/未知 Tool 参数校验。
- 测试覆盖（ranking 截断、多 gold、mixed support、no-tool、完整参数错误、重复执行稳定性、真实 dataset runner）。
- README 数字与真实报告一致，并明确声明仅作回归信号、非线上语义/事实正确性。

## 逐个 Issue 展开

### S-01 — 未知 actionType 会被误当作 UPDATE_TASK 校验

- **Severity**：Low（建议修改）
- **File & Line**：`services/agent-service/src/agentforge_agent/evaluation/dataset.py:165-176`
- **Evidence**：
  ```python
  def _validate_tool_gold(expected: dict[str, object]) -> None:
      action_type = expected["actionType"]
      if action_type == "CREATE_TASK":
          ...
          return

      if "taskId" not in expected or "expectedVersion" not in expected:
          raise ValueError("UPDATE_TASK gold requires taskId and expectedVersion")
      if not any(key in expected for key in ("title", "description", "status", "priority")):
          raise ValueError("UPDATE_TASK gold requires at least one changed field")
  ```
- **Description**：`else` 分支隐式假设非 CREATE 即 UPDATE。当前受 `ToolProposal.model_validate` 约束，且 dataset 仅含 CREATE/UPDATE，尚不触发问题；但一旦 actionType 枚举扩展（如 DELETE_TASK）或 gold 拼写未经 pydantic 拦截，会被静默套用 UPDATE 规则，报错信息也具有误导性。
- **Suggested Fix**：显式分支：
  ```python
  if action_type == "CREATE_TASK":
      ...
      return
  if action_type != "UPDATE_TASK":
      raise ValueError(f"unsupported actionType: {action_type}")
  # UPDATE_TASK 校验...
  ```

### S-02 — report subject 版本与真实代码修订脱钩

- **Severity**：Low（建议修改）
- **File & Line**：`services/agent-service/src/agentforge_agent/evaluation/runner.py:64`
- **Evidence**：
  ```python
  "subject": "agentforge-current-code-offline-v1",
  ```
- **Description**：该字符串为硬编码常量。生产算法（`bm25_rank`/`reciprocal_rank_fusion`/`plan_tool`）变更后，报告中的 subject 仍是 `v1`，无法单独从报告追溯“这份数字是哪一版代码跑出来的”。当前靠 `docs/07-changes` 记录基线 commit 弥补，但报告自身可比性弱。
- **Suggested Fix**：可从环境读取 git commit 或包版本注入：
  ```python
  import os
  _SUBJECT = os.environ.get("AGENTFORGE_EVAL_SUBJECT", "agentforge-current-code-offline-v1")
  ```
  并在 CLI/CI 中传入 `git rev-parse --short HEAD`；保持默认值不变以免破坏现有报告 schema。

### S-03 — ADR-0010 引用需确认存在

- **Severity**：Low（建议修改）
- **File & Line**：`docs/03-features/evaluation.md:5`
- **Evidence**：
  ```markdown
  - 相关 ADR：ADR-0010
  ```
- **Description**：本次 diff 未新增任何 `docs/02-architecture/decisions/` 文件，无法确认 ADR-0010 已存在。治理协议规定普通代码修改不创建 ADR；若 ADR-0010 是既有记录则无问题，否则该处为悬挂引用。
- **Suggested Fix**：在 Close Gate 前核对 `docs/02-architecture/decisions/` 中 ADR-0010 是否真实存在；如不存在，删除该行或将编号指到真实存在的 ADR。

### S-04 — 变更记录与功能文档状态不一致

- **Severity**：Low（建议修改）
- **File & Line**：`docs/07-changes/2026-09-09-v2-08-evaluation.md:3-4`
- **Evidence**：
  ```markdown
  - 状态：In Progress
  ```
  而 `docs/03-features/evaluation.md:3` 为 `- 状态：Implemented`，README 已写“已完成：V2-08 …”。
- **Description**：文档间状态口径不统一。若 Review 尚未关闭，`In Progress` 可接受；但功能文档与 README 已提前宣称 Implemented/已完成。
- **Suggested Fix**：Node Close Gate 后统一将所有位置改为 `Implemented / 已完成`；或在 Review 关闭前不提前在功能文档/README 写死完成状态。

## 主开发（Codex）评估回填区

| Finding ID | 是否属实 | 是否修复 | 修复说明 / 拒绝理由 |
| --- | --- | --- | --- |
| S-01 | 否（当前不可触发） | 否 | `ToolProposal.action_type` 是 Pydantic `Literal["CREATE_TASK", "UPDATE_TASK"]`；未知动作在进入 `_validate_tool_gold` 前已失败。若未来正式扩展 Tool 枚举，应随新 Tool 的 gold 契约同步增加显式分支。 |
| S-02 | 部分属实 | 否 | `v1` 表示离线 subject 契约版本，不声称是 Git revision；报告同时记录 dataset hash，变更记录记录真实 base。未提交 diff 无法安全写入最终 commit hash，环境变量注入也会削弱默认可重复性。未来可新增由发布流程注入、允许为空的 revision 字段。 |
| S-03 | 否 | 否 | 已核对既有文件 `docs/02-architecture/decisions/ADR-0010-day-4-rag-boundary-and-ranking.md`，引用真实存在且与 BM25/RRF 边界直接相关。 |
| S-04 | 是 | 是 | Pi PASS 后进入 Close Gate，将 V2-08 变更记录状态统一更新为 `Implemented`。仅文档状态回填，不影响已通过实现与测试输入。 |

> 结论：本轮未发现必须修改项（PASS）；上述 4 条均为低风险建议，不阻塞 Node Close。Codex 可逐条判断并记录处理结果。
