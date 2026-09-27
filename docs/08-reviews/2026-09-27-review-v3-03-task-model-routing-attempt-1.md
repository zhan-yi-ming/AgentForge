# Pi 代码审查报告：v3-03-task-model-routing / Attempt 1

- 日期：2026-09-27
- 审查阶段：v3-03-task-model-routing
- 审查对象：INDEX@6fce271（基线：6fce27142f2c7591bc6ac8678d810179ad090700）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: PASS

# V3-03 确定性任务模型路由 · Milestone Review 报告

## 一、概述与总体结论

**审查对象**：Commit INDEX@6fce271（V3-03 Multi-model Routing），18 个文件，+496/-5。
**审查模式**：Milestone（只读，无工具执行，仅依据提供的 diff、文件清单与变更记录）。

**总体结论：通过（PASS）。**

- 节点 Scope 匹配：`FORMAT/REWRITE/PLAN/REVIEW/ANSWER` 固定规则分类 + 部署候选排序 + 每任务独立 Gateway + 有界回退，未引入 LLM 自动选模型，未用到原生 Tool Calling。
- 边界保持：路由仅影响生成模型选择，不产生权限；Tool 意图固定 PLAN 且沿用严格 JSON 解析；Java 授权/Risk/Approval 未被触碰。
- 无越界：未出现 V3-04 图领域、GraphRAG、Neo4j、MCP 扩展；无 DB schema 变化。
- 失败关闭设计合理：无覆盖、能力不兼容、候选目标缺凭据、重复 name、含额外字段（含凭据）都在首次请求前抛 `LlmDependencyError`，不会调用 provider。
- 测试覆盖与变更记录基本吻合（`test_model_routing.py` 共 25 个用例，与记录一致），已覆盖分类、排序、能力失败关闭、去重防自回退、回退次数有界、流式已输出后不切换、跨槽凭据、observation 合并与 HTTP/NDJSON 入口。

未发现具备明确证据的可运行性、正确性、安全、权限、并发/幂等、数据一致性或契约阻断问题，因此不触发 NEEDS_FIX。以下 6 项为建议性改进，不阻塞本节点收口。

---

## 二、详细发现清单

| ID | 严重级别 | 文件 | 行号（近似） | 核心问题 |
|----|----------|------|--------------|----------|
| F-01 | 建议（中） | docs/06-operations/production-single-host.md；services/agent-service/src/agentforge_agent/llm.py | 文档 “故障处理” 段；`build_responder` 路由分支 ≈227–231 | 紧急降级指引与实现冲突：`provider=disabled` 且 `LLM_ROUTES` 非空会直接抛错（503），不再提供确定性降级 |
| F-02 | 建议（低） | services/agent-service/src/agentforge_agent/llm.py | `_update_usage` ≈213 | 条件 `(model != self.model_name or self.provider)` 中 `or self.provider` 恒真，判断退化为“只要有 model/provider 就更新”，与意图（provider 变化）不符 |
| F-03 | 建议（低） | services/agent-service/src/agentforge_agent/llm.py | 路由分支 `updates = {...}` ≈266–269 | 单目标任务只把 `llm_fallback_provider` 置 None，未清空 `llm_fallback_api_key/base_url/model`，残留旧配置语义不明 |
| F-04 | 建议（中） | services/agent-service/src/agentforge_agent/model_routing.py；tests/test_model_routing.py | `classify` ≈21–34 | FORMAT 路由依赖客户端精确前缀；无契约测试把 Web/Java 真实“AI 整理”提示词与 `classify` 绑定，前缀漂移会导致 FORMAT 路由不可达 |
| F-05 | 建议（低） | services/agent-service/src/agentforge_agent/model_routing.py | `RoutedResponder.plan_tool` ≈84–86 | Tool 意图调用未经过 `RouteObservation`，缺失 `task_type/route/rank` metadata，与变更记录“新增 task_type、route 和 rank”不完全一致 |
| F-06 | 建议（低） | services/agent-service/src/agentforge_agent/model_routing.py | `RoutedResponder._select` ≈63–70 | 回退命中备选时，metadata 的 `route` 仍是主候选 name，仅 provider/model 被纠正，无法从 metadata 直接看出实际服务候选 |

---

## 三、逐个 Issue 展开

### F-01 紧急降级指引与 `disabled + routes` 行为冲突（建议，中）

**File & Line**：`docs/06-operations/production-single-host.md`（“故障处理”中 “紧急时可把 provider 设为 `disabled`，但这只提供确定性降级”，本次 diff 未修改该句）；`services/agent-service/src/agentforge_agent/llm.py` `build_responder` 路由分支 ≈227–231。

**Evidence**（llm.py）：
```python
if settings.llm_routes:
    ...
    if settings.llm_provider == "disabled" or model_factory is not None:
        raise ValueError("routing requires enabled Gateway")
```
**Description**：V3-03 部署一旦配置 `AGENTFORGE_AGENT_LLM_ROUTES`，运维按既有 runbook 执行“紧急把 provider 设为 disabled”将不再得到文档承诺的确定性降级，而是所有 Chat/AI 整理请求 503（`LlmDependencyError`）。新文档 `docs/03-features/model-routing.md` 写了 “disabled 不允许启用路由”，但运维文档仍在宣称 disabled 可降级，且 V3-03 运维段落只说 “Empty array preserves existing behavior”，未提示必须同时清空数组。属于文档契约不一致，不是代码正确性缺陷（失败关闭本身是安全方向）。

**Suggested Fix**：在 `docs/06-operations/production-single-host.md` 与 `docs/05-development/local-development.md` 的 V3-03 段落明确写：紧急禁用模型须同时将 `AGENTFORGE_AGENT_LLM_ROUTES` 置为 `[]`，否则启动/首请求即失败关闭；或由 Codex 判断是否改为“disabled + routes 时忽略 routes 走确定性 responder”，二选一但必须与代码一致。

---

### F-02 `_update_usage` 条件恒真（建议，低）

**File & Line**：`services/agent-service/src/agentforge_agent/llm.py`，`_update_usage` ≈213。

**Evidence**：
```python
if isinstance(model, str) and isinstance(provider, str) and (model != self.model_name or self.provider):
    observation.update(model=model, metadata={"provider": provider})
```
**Description**：`self.provider` 是非空字符串（默认 `"unknown"`），布尔上恒真，因此整个条件退化为 `isinstance(model,str) and isinstance(provider,str)`。作者意图显然是 “模型名变化**或 provider 变化**”（配合新增的跨 provider 同模型名场景，见 `test_same_model_name_fallback_reports_actual_provider`）。当前写法不会造成可观测错误（重复以相同值 update 幂等），但判断与意图不符，属于易误导的逻辑残留。

**Suggested Fix**：
```python
if isinstance(model, str) and isinstance(provider, str) and (
    model != self.model_name or provider != self.provider
):
    observation.update(model=model, metadata={"provider": provider})
```

---

### F-03 单目标任务未清空 `llm_fallback_*` 残留字段（建议，低）

**File & Line**：`services/agent-service/src/agentforge_agent/llm.py`，路由分支 `updates` ≈266–269。

**Evidence**：
```python
updates = {"llm_routes": [], "llm_fallback_provider": None,
    **{"llm_" + key: value for key, value in endpoint(chosen).items()}}
if len(unique) > 1:
    updates.update({"llm_fallback_" + key: value for key, value in endpoint(unique[1]).items()})
```
**Description**：当某任务只有 1 个不同目标时（例如 `test_duplicate_destination_slots_do_not_create_self_fallback`），只把 `llm_fallback_provider` 置 `None`，原 `settings` 上的 `llm_fallback_api_key / base_url / model` 仍被 `model_copy(update=...)` 保留。若 V3-02 的 fallback 构造不是严格以 `llm_fallback_provider` 为唯一切换条件，就可能残留 “有 fallback 端点但 provider 为空” 的歧义配置。该测试用例中 completion 恒成功，无法证伪。属潜在隐患，非已确认 bug。

**Suggested Fix**：在基础 updates 中一并清空四个字段：
```python
updates = {"llm_routes": [], "llm_fallback_provider": None,
           "llm_fallback_api_key": None, "llm_fallback_base_url": None,
           "llm_fallback_model": None, **{...}}
```

---

### F-04 FORMAT 分类依赖客户端提示词前缀，缺少契约测试（建议，中）

**File & Line**：`services/agent-service/src/agentforge_agent/model_routing.py` `classify` ≈21–34；`services/agent-service/tests/test_model_routing.py`（字面前缀断言）。

**Evidence**：
```python
("FORMAT", ("FORMAT:", "请将以下内容整理为 Markdown")),
```
```python
("请将以下内容整理为 Markdown，保留事实，不执行写入：笔记", "cheap-model"),
```
**Description**：FORMAT 路由只有在真实请求文本以该中文字面前缀开头时才生效，否则落回 `ANSWER`（按 `-capability` 优先，会使用更贵的模型）。测试只用了手写字面串，没有引用 Web/Java 侧实际生成 “AI 整理” 提示词的常量，也无法证明线上提示词与该前缀一致（提示词由前端/DTO 组装）。若前缀漂移，V3-03 的成本收益目标在该任务上直接失效且无告警。

**Suggested Fix**：将 FORMAT 触发前缀提取为共享常量（或至少加一条以真实客户端提示词副本为输入的契约测试），并在 `docs/03-features/model-routing.md` 标注该前缀是与 Web/Java 的显式契约，任何一方修改必须同步。

---

### F-05 Tool 意图调用缺失 route metadata（建议，低）

**File & Line**：`services/agent-service/src/agentforge_agent/model_routing.py` `RoutedResponder.plan_tool` ≈84–86。

**Evidence**：
```python
def plan_tool(self, bundle):
    return self.responders["PLAN"].plan_tool(bundle)
```
**Description**：`respond_observed`/`stream_observed` 都会经 `_select` 包裹 `RouteObservation` 写入 `task_type/route/rank`，但 `plan_tool` 直接委派，未包裹 observation。因此 Tool 意图生成的 trace 缺少 `task_type=PLAN` 与 route/rank，与变更记录 “Trace generation 沿用既有计时，新增 task_type、route 和 rank” 的表述不完全一致（该表述未限定仅回答路径）。

**Suggested Fix**：若 `plan_tool` 的调用方（Graph 节点）持有 observation，则同样经 `_select` 语义为 PLAN 写入 route metadata；若调用方无 observation，则在文档中明确 “route metadata 仅覆盖回答路径”。

---

### F-06 回退命中时 `route` 仍为主候选名（建议，低）

**File & Line**：`services/agent-service/src/agentforge_agent/model_routing.py` `RoutedResponder._select` ≈63–70。

**Evidence**：
```python
observation.update(metadata={"task_type": task, "route": chosen.name, ...})
```
配合 `test_routed_fallback_is_bounded_and_preserves_actual_usage`：回退到 `capable-model` 后 `observation.model == "capable-model"`，但 `metadata["route"]` 仍为 `chosen.name`（`cheap`）。

**Description**：`decisions[task]` 只记录“决策候选”，不含“实际服务候选”。provider/model 会被 Gateway 观测纠正，但 route 名称不会，使 trace 无法直接区分是主用还是回退成交，排查回退率时需额外推断。

**Suggested Fix**：在回退成功路径额外写入实际候选标识（如 `metadata["route"] = chosen.name` 保持决策语义，另加 `fallback_used: true` 或 `served_route`），或在文档中明确 `route` 语义为“路由决策”而非“实际服务者”。

---

## 四、无需修改项（确认项）

| ID | 文件/范围 | 结论 |
|----|-----------|------|
| N-01 | `model_routing.py` 分类与路由 | 分类只影响模型选择，不产生权限；Tool 意图固定 PLAN 且不参与分类；配置 `extra="forbid"` 阻止把凭据塞进路由 JSON。边界正确 |
| N-02 | `build_responder` 路由分支 | 候选全量预校验、能力失败关闭、按 `(provider, model)` 去重阻断自回退、单次有界回退；未见无限回退路径 |
| N-03 | `tests/test_model_routing.py` | 25 个用例覆盖分类/排序/能力校验/去重/回退边界/流式不切换/跨槽凭据/observation 合并/HTTP+NDJSON；与变更记录数目一致，无证据表明核心分支遗漏 |

---

## 五、主开发（Codex）评估回填区

| 编号 | Codex 判断（成立 / 不成立 / 部分成立） | 事实与理由 | 处理（修复 / 文档 / 不处理） | 复核要点 |
|------|----------------------------------------|------------|------------------------------|----------|
| F-01 | 成立 | 非空 routes 与 disabled 失败关闭，旧降级指引不完整。 | 文档：禁用须清空 routes 与 fallback provider。 | 配置失败关闭已有测试 |
| F-02 | 不成立 | 实际暂存 llm.py 条件为 provider != self.provider；报告引用缺失比较，跨 provider 测试已通过。 | 不处理 | 核对当前源码与 diff |
| F-03 | 不成立 | V3-02 build_responder 仅在 fallback_provider 非空时构建备用目标；None 时残留字段不使用。 | 不处理：潜在建议不扩大范围 | 自回退去重与有界回退已覆盖 |
| F-04 | 部分成立 | 已核对 Web App.tsx FORMAT_PROMPT_PREFIX 与规则相符；补完全一致输入和显式同步契约。 | 文档与现有测试输入澄清 | 仅 FORMAT_PREFIX 改动需同步 |
| F-05 | 成立 | plan_tool 既有 seam 无 observation，当前 route metadata 仅回答路径，未声称完整规划费用账单。 | 文档限定覆盖；V3-09 观测回归时评估 | 无业务或 HTTP 变化 |
| F-06 | 成立 | route 是初始决策名，实际 provider/model 独立记录，回退不重写决策。 | 文档明确语义 | 回退实际 usage/provider 已测试 |

---

## 六、Milestone 方向与下一节点检查

- **节点完成度**：V3-03 Scope 已实现（固定规则分类、部署候选与成本/延迟/能力排序、每任务独立 Gateway、有界回退、能力失败关闭）；未提前实现 V3-04。
- **公开描述真实性**：README/路线图/系统概览/Model Routing 均标注 `🚧 In Progress`，未出现未验证的省钱/提速指标；变更记录明确 “rank 是部署声明相对值”。pass。
- **后续风险提示（非阻塞）**：V3-09 需要把 “回退率/路由命中率” 纳入观测与 Release 回归；届时 F-05/F-06 的 metadata 缺口与 F-04 的客户端前缀契约需先收敛，否则无法用证据评估多模型路由的真实收益。

**结论**：本节点可在完成上述建议项（至少 F-01、F-04 的文档/契约澄清）后进入 Commit 关闭流程；当前无必须修改项，`REVIEW_RESULT: PASS`。
