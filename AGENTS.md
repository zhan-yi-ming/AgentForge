# AgentForge 项目级指令

本文件是仓库内 AI 开发代理的最高优先级项目规则。子目录 `AGENTS.md` 只能补充，不能削弱这些规则。

## 不可削弱的边界

- **文档先行**：任何增、删、改必须依次执行“变更记录 → 受影响目标文档 → 实现 → 验证回填”。紧急修复也先写精简记录。新增功能更新 `docs/03-features/`；系统边界、依赖方向、数据模型或公共接口变化必须更新架构文档并按需新增 ADR。
- **确定性职责**：保持 Java 业务执行 / Python Agent 边界，遵守现有 ADR，不提前实现后续 Node。LLM 只产生意图，Java 负责权限、审批和业务写入。
- **安全**：禁止提交、输出或发送真实密钥、Token、密码、私钥、生产 `.env` 或敏感日志。保留用户已有 staged / unstaged / untracked 内容，不把无关变更混入提交。
- **真实证据**：Codex 亲自运行风险与影响范围相称的测试。Pi PASS、历史报告、其他工作树和旧缓存不能代替当前机器证据；Java clean verify 与 Python pytest 不得使用旧构建产物冒充通过。

## 开始任务与控制上下文

1. 先按 `docs/00-governance/efficient-validation.md` 做 Git preflight：fetch 目标远端、确认分支/base、记录用户已有改动。
2. 读取 `docs/README.md`、当前变更记录、受影响功能/API/架构/ADR、直接调用方和相关测试。只有边界变化、冲突、失败或影响未知时扩大上下文。
3. 在 `docs/07-changes/` 建立记录并先修改目标文档，再改实现。
4. V2/V3 必须额外遵守 `docs/00-governance/v2-v3-node-development-protocol.md` 和当前路线 Node；Start Gate 等待用户确认，一次只实现一个 Node。

## 实现与测试

- 行为修改使用 `skills/engineering/tdd/SKILL.md`：在已确认的公共 seam 上一次一个纵向切片，先实际红灯，再做最小实现；避免私有实现测试、同义反复断言和过度 mock。
- 排障使用 `skills/engineering/diagnosing-bugs/SKILL.md`：建立可重复信号，提出可证伪假设，按证据修复、回归并清理。
- 先运行 `scripts/validation/plan-change-gates.ps1`。风险 L0–L3 决定 Review 强度，Docs/Web/Core API/Agent Service/TLS/跨服务影响域决定测试套件；L3 不自动等于全仓。
- Schema、权限安全、额度限流、状态机、公共契约属于 L3；外部调用、部署配置、普通 API/Tool/Workflow 或多模块至少 L2；未知路径至少 L2 并向上确认。
- Release Gate、共享全仓变化或影响无法收敛必须全仓回归。节点结束触发 Milestone Review，但不自动运行无关模块测试。
- 同一任务内，只有相关源码、配置、依赖、契约和 base 均未变化时可以复用本次已通过证据；相关输入变化、失败或 Review 指向该范围时必须重跑。
- 成功日志默认只保留命令、工具版本、退出码、passed/failed/skipped、关键 smoke 和清理摘要；不得隐藏 warning、skip、失败或清理异常。

## Pi 一次性只读审核

- L0/L1 默认不调用 Pi；L2 做 Diff Review；L3、Node、累计门禁和 Release 做 Milestone Review。只有真实阻塞项修复后才复审，纯建议不触发下一轮。
- Codex 完成相称测试、清理和敏感扫描后，可使用用户持续授权调用 DeepSeek Pi V4-pro；Pi 只读，不测试、不修改、不 monitor、不提交、不推进阶段。
- 唯一连接说明是 `docs/06-operations/pi-review-connection.md`。启动器或模型不可用时立即请用户处理；禁止全盘搜索、安装尝试、降级模型或循环重试。
- 只发送已扫描的本次 diff、必要接口、当前范围文档和结构化测试摘要；不得发送凭据或完整敏感日志。Codex 逐条判断 finding，只修复可复现的严重缺陷、不可运行、安全/数据一致性、真实契约冲突、架构边界破坏或明显偏离目标。

## 提交、推送与汇报

- 回填变更记录，确认暂存区只含本次范围，完成 diff check、临时产物清理和敏感扫描后创建可读提交。
- V2/V3 Node 提交前核对协议、当前 Roadmap 条目、上下文包和最终 diff，输出 Node Close Gate；Gate 为 YES 才能提交。
- 提交后不再修改该变更；非 force 推送到已确认远端并核验 commit。远端出现意外历史或推送失败时停止。
- V2/V3 下一 Node 必须等待用户新的明确授权。用户已明确授权并排序的独立非 Node 后续任务，可在前置提交远端核验后另建变更记录继续，禁止混入同一提交。
- 最终依次报告：完成事项；风险、影响、验证与限制；用户介入是否必需；下一步计划。

## 权威入口

- 文档制度：`docs/00-governance/documentation-first-policy.md`
- 变更流程与完成标准：`docs/00-governance/change-workflow.md`、`docs/00-governance/definition-of-done.md`
- 增量验证：`docs/00-governance/efficient-validation.md`
- Node 协议与路线：`docs/00-governance/v2-v3-node-development-protocol.md`、`docs/01-product/v2-v3-node-roadmap.md`
- 测试与审核：`docs/05-development/testing-strategy.md`、`docs/08-reviews/README.md`
- Pi 连接：`docs/06-operations/pi-review-connection.md`
