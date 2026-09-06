# V2/V3 节点开发协议

- 状态：Accepted
- 适用范围：所有 V2/V3 Node 的启动、实现、Review 修复、提交和节点切换
- 路线唯一来源：`../01-product/v2-v3-node-roadmap.md`

## 每次开发的强制阅读入口

任何 V2/V3 工作都不得凭记忆开始。开始新 Node、修改当前 Node、处理 Pi Review Issue、准备 Commit 或准备进入下一 Node 前，必须重新读取：

1. 本协议。
2. `../01-product/v2-v3-node-roadmap.md` 中的当前 Node。
3. 当前产品、架构、功能、API、ADR 和最近变更记录。
4. 当前 Node 相关代码与测试。
5. 上一个 Node 的真实实现和验证结果。

本协议负责“如何开发”，节点路线图负责“按什么顺序、每个 Node 做什么”。二者均为仓库正式文档，不在 `AGENTS.md` 中重复维护。

## 1. 项目定位与确定性边界

AgentForge 是面向研发协作场景的 AI Agent Workspace，不是 AI 客服、Chat With PDF、普通知识库、单纯 RAG Demo 或 LLM Wrapper。它解决的是 AI Agent 如何在真实项目上下文中持续工作，并安全、可靠、可恢复、可观察、可评估地操作真实业务系统。

核心原则是：**AI 决策，确定性系统执行。**

- Python / Agent 负责 LLM、Planning、Context、RAG、Tool Calling 和 Agent State。
- Java / Business 负责用户、Project、Wiki、Task、RBAC、Risk Policy、Approval、数据写入和确定性业务规则。
- LLM 只能产生 `update_task(...)` 一类 Action Intent，不拥有最终权限；Java 必须在执行前重新判断是否允许。
- MCP、GraphRAG、多模型等后续能力不得破坏这一信任边界。

## 2. V1 冻结原则

V1 已完成。进入 V2/V3 后，不得为了 GitHub 展示、架构形式、代码统一或新技术接入而大规模重构 V1。只有当前 Node 确实需要时，才允许对 V1 做最小必要修改；不影响主流程的已有额外能力不得主动删除或重写。

## 3. Node Boundary：禁止跨节点开发

当前 Node 只能实现路线图声明的 Scope。可以预留接口、留下明确 TODO 或调整当前 Node 必需的数据结构，但不能提前实现下一 Node 的业务能力。例如 V2-05 只能完成 RBAC 与 Risk Engine，不得顺手完整实现属于 V2-06 的 Approval、Audit 和 Idempotency。

发现后续需求时记录到对应未来 Node；不得把“实现起来方便”当作越界理由。每个 Node 的 diff 必须可理解、可测试、可 Review、可回滚。

## 4. Node Start Gate

当用户说“开始 V2-XX”或“开始 V3-XX”时，第一轮禁止修改实现。完成强制阅读后，先输出并停在以下 Gate，等待用户明确确认：

```text
【Node Start Gate】

Node：
目标：
当前项目状态：
本节点解决的问题：

本节点 Scope：
- ...

明确不做：
- ...

预计修改模块：
- ...

预计新增 / 修改数据结构：
- ...

风险点：
- ...

测试计划：
- ...

GitHub 展示价值：
- ...

预计需要更新的公开文档：
- README / architecture / docs / none

与下一节点的边界：
- ...
```

## 5. 固定开发 Gate

每个 Node 固定执行：

1. 用户指定 Node。
2. Codex 完成强制阅读。
3. Codex 输出 Node Start Gate。
4. 等待用户确认。
5. 先按文档先行制度更新变更记录、功能/架构/API 等目标文档，再实现。
6. Codex 运行当前 Node 必要测试并记录机器证据。
7. 按风险等级触发 Pi 一次性只读 Review；Review 输入为当前 Node requirement、git diff、必要关联代码和测试结果。
8. Codex 逐条判断 finding 是否真实、原因、是否需改、最小修复和补测范围，再实施必要修复。
9. Codex 重跑相关测试，并按风险决定更大范围 Regression。
10. 同步必要的 GitHub 公开文档与证据。
11. 重新读取本协议和当前 Roadmap Node，输出 Node Close Gate。
12. Gate 为 YES 后才可 Commit；随后按现行流程推送、核验并停止。
13. 等待用户明确开始下一 Node，禁止自动推进。

若本协议与 `change-workflow.md` 的通用门禁同时适用，二者都必须满足；Pi 的连接方式和风险触发标准以现行治理文档为准。

## 6. Codex 与 DeepSeek Pi 分工

Codex 是唯一主开发者，负责读取需求、设计、文档、实现、测试、修复、重构、GitHub 维护及判断 Review 是否成立。DeepSeek Pi 只做独立 Reviewer，不修改代码、不替 Codex 执行测试、不自动推进阶段。

Pi finding 应至少包含 `severity`、`file`、`line`、`evidence`、`suggested_fix`。Codex 不得无脑照单执行；必须逐条给出事实判断，并只修复可复现的真实问题或实质风险。

## 7. 风险匹配的测试与 Review

沿用 `change-workflow.md` 的 L0–L3 正式分级；下表是 Node 规划时的简化映射，不能用于降低正式门禁：

| 规划风险 | 典型变化 | 最小验证方向 |
| --- | --- | --- |
| Low | 文案、样式、README、无逻辑 UI | 静态检查或相关局部测试 |
| Medium | 普通业务、Context、Retrieval、Formatter、非核心 API | 相关 Unit + 模块 Regression |
| High | Permission、Risk、Approval、Idempotency、Retry、Persistence、跨项目隔离、MCP 写工具、图数据写入、Release Gate | 相关 Unit + Integration + 必要 Regression |

V2-09 与 V3-09 必须执行完整 Release Regression。安全、状态、数据或 Agent 行为变化必须 Review；纯低风险文档/UI 调整按现行累计与节点触发规则处理。

## 8. GitHub 长期定位与文档维护

公开仓库不能把 AgentForge 描述成“可以问 Wiki 的 RAG 系统”。公开叙事应围绕：

`Project Context → Context Engineering → Retrieval → Agent Decision → Tool Calling → Risk / Permission → Human Approval → Deterministic Execution → Persistence / Recovery → Trace → Evaluation`

V3 实现后再扩展 GraphRAG、MCP、Model Gateway 和 Multi-model Routing。技术名词必须回答“解决了什么工程问题”。

每完成一个 Node 都要判断是否更新公开文档，但不得为了活跃度机械修改 README。README 只负责让首次访问者在 2–3 分钟理解项目定位、为何不是普通 RAG、核心架构、真实能力、Demo 与关键工程证据；复杂设计进入 `docs/`，并只在对应 Node 真正实现时建立。

重要架构决策进入 `../02-architecture/decisions/`，使用 Context、Decision、Alternatives、Trade-offs、Consequences 结构。普通代码修改不创建 ADR。

## 9. 公开描述真实性与 Evidence

禁止在没有证据时使用 `enterprise-grade`、`production-grade`、`revolutionary`、`state-of-the-art`、`industry-leading`、企业级、业界领先、完美解决等表述。禁止把 Planned 写成 Implemented，公开状态统一为：

- ✅ Implemented
- 🚧 In Progress
- 🧭 Planned

没有真实运行就不写指标、Accuracy 或 Production Ready。公开 Claim 应尽量同时有 Implementation、Test、Trace/Screenshot/Evaluation；高级感来自证据，不来自技术名词数量。

README 第一屏的长期方向是 `Reliable AI Agent Workspace for engineering collaboration`。优先展示 Context Engineering、Tool Calling、Human-in-the-loop、RBAC/Risk Control、Stateful Agent、Observability 和 Evaluation；GraphRAG、MCP、Multi-model Routing 只能在 V3 对应能力实现后加入。

最重要的 Demo 链路是：用户提出研发任务 → Project Context → Wiki/Task Retrieval → Agent Planning → Structured Tool Call → Risk Engine → Human Approval → Java RBAC Validation → Business Execution → Audit/Trace，而不是仅展示 Question → RAG → Answer。

## 10. Commit 与 Node Close Gate

一个 Node 尽量形成一个清晰 Commit。推荐 `feat(context): ...`、`feat(agent): ...`、`feat(security): ...`、`feat(eval): ...`、`feat(graphrag): ...`、`feat(mcp): ...`；禁止 `update`、`fix code`、`change`、`final`、`test123` 一类无意义信息。

Commit 前必须检查 Node 越界、下一 Node 混入、测试、公开文档、Planned/Implemented 真实性、密钥/Token/`.env`、临时日志/cache/IDE 文件。随后重新读取本协议与当前 Roadmap Node，并输出：

```text
【Node Close Gate】

Node：

Scope 完成情况：
- ...

本次实际修改：
- ...

明确没有实现的下一节点能力：
- ...

Tests：
- ...

DeepSeek Review：
- 已完成 / 待完成

Review Issue 处理：
- ...

GitHub Maintenance：
README：updated / not required
Architecture：updated / not required
Docs：...
Demo / Screenshot：recommended / not required
Evaluation / Evidence：...

公开描述真实性检查：pass / issue
推荐 Commit：...
是否满足进入下一 Node：YES / NO
```

只有 Gate = YES 才认为 Node 可关闭。完成提交、推送与远程核验后立即停止；即使下一 Node 明确，也必须等待用户再次授权。
