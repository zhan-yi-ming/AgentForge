# Codex 与 Pi Agent 代码审查桥接机制与 5 小时限额调度

- 日期：2026-09-03
- 状态：Implemented
- 阶段：V1 开发治理与工程协同
- 交付目标：`origin/main`

## 背景

在 AgentForge 研发过程中，Codex (GPT) 作为主开发 Agent，按天/阶段执行功能落地并在完成交付后中断；Pi Agent (DeepSeek) 作为独立只读 Reviewer 承担代码审查职责。
然而当前存在以下协同痛点：
1. 缺少两端协同的中间媒介与格式标准，审查结果无法稳定沉淀与异步交接；
2. Pi Agent 为全新安装环境，尚未配置针对 AgentForge 项目架构规范与只读审查边界的系统提示词；
3. Codex 存在 5 小时速率限制（Rate Limit），限额耗尽后无法获知恢复时间，缺乏自动等待并在配额恢复后自动唤醒续跑的机制；
4. Codex 阶段完成后，需要人工拉起 Pi 审查，缺乏自动检测阶段提交与触发审查的自动化闭环。

## 目标

- 建立基于持久化 Markdown 文档的标准审查机制（`docs/08-reviews/`）与报告模板，规范 `severity`、`file`、`line`、`evidence`、`suggested_fix` 等输出格式。
- 为 Pi Agent 定制并配置系统提示词与审查模版，确保其遵循项目 V1 边界与只读审查准则，不随意修改代码。
- 编写跨平台自动化桥接监控脚本（`scripts/agent-bridge/`），实现：
  1. 阶段 Git 提交完成自动感知；
  2. 自动触发 Pi 执行只读审查并生成报告文档；
  3. 当 Codex 5 小时配额耗尽时，自动进入 5 小时倒计时休眠并在窗口重置后自动生成唤醒指令与续跑信号。
- 遵循“文档先行”规范，更新项目治理与工作流文档。

## 非目标

- 不改变 Java Core API 与 Python Agent 的核心业务代码与数据库架构。
- 不允许 Pi Agent 直接在工作区执行自动写入或代码替换（维持严格只读审查原则）。
- 不引入外部复杂调度框架（如 Celery、Airflow 等），保持轻量级原生脚本化。

## 受影响文档

- `AGENTS.md`：增加主开发 (Codex) 与独立审查员 (Pi Agent) 的协同与文档流转协议。
- `docs/README.md`：增加 `08-reviews` 审查中心目录说明。
- `docs/05-development/git-workflow.md`：在阶段交付与中断步骤中增加 Pi Review 节点。
- `docs/07-changes/README.md`：登记本次变更。
- `docs/templates/review-record-template.md`：新增标准审查文档模板。

## 设计决定

1. **评审报告持久化**：统一保存在 `docs/08-reviews/YYYY-MM-DD-review-day-<X>.md`，与 Git 提交同生命周期管理，支持追溯。
2. **只读保护**：Pi 运行采用 `--tools read,grep,find,ls` 限制内置修改工具，仅生成报告并由主开发评估实施。
3. **配额倒计时与阶段感知**：通过定时轮询 `git rev-parse HEAD` 及变更文档标记感知阶段结束；当捕获到 5 小时限额时进入 18000 秒自适应等待，到期自动唤醒。
4. **审查模型锁定**：Pi 代码审查固定使用完整选择器 `deepseek/deepseek-v4-pro`；禁止自动或人工降级到 Flash。每次审查前通过 Pi 模型目录校验 ID，并把外部进程非零退出码或空输出视为失败；失败时不得生成伪报告或把该提交标记为已审查。

## 实现

### 2026-09-04 审查模型修正

本机 `pi --list-models v4-pro` 确认可用模型 ID 为 `deepseek/deepseek-v4-pro`。`run-review.ps1` 默认模型与文档锁定为该 ID，并增加拒绝非 V4-pro 参数、目录预检、非零退出码与空输出的快速失败校验；原 `deepseek/deepseek-v4-flash` 默认值不得用于代码审查。

1. **全局系统指令与项目模板**：
   - `C:\Users\86134\.pi\agent\AGENTS.md`：为 Pi Agent 注入独立代码审查员身份、只读原则、V1边界（不做 Neo4j/Langfuse/MCP/LiteLLM）与审查维度。
   - `.pi/prompts/review.md`：项目级 `/review` 快捷审查模板。
2. **文档规范与模板**：
   - `docs/templates/review-record-template.md`：标准化审查报告模板，包含 Severity、File、Line、Evidence、Suggested Fix 及主开发回填区。
   - `docs/08-reviews/README.md`：审查结果归档中心规范说明。
3. **治理与工作流集成**：
   - `AGENTS.md`：补充双 Agent 分工协同规则与审查交接协议。
   - `docs/README.md`：增加 08-reviews 目录索引。
   - `docs/05-development/git-workflow.md`：增加阶段交付后的 Pi 审查与 Codex 修复流程。
4. **自动化桥接工具**：
   - `scripts/agent-bridge/prompts/stage-review-system.md`：审查提示词定义与插值模板。
   - `scripts/agent-bridge/run-review.ps1`：跨平台/PowerShell 只读审查执行脚本，输出 Markdown 报告至 `docs/08-reviews/`。
   - `scripts/agent-bridge/bridge-monitor.ps1`：监听阶段 Git 提交、触发 Pi 审查并管理 5 小时配额等待/自动生成续跑指令的守护脚本。
   - `scripts/agent-bridge/README.md`：桥接调度工具使用指南。
   - `.gitignore`：排除桥接状态、额度标记与续跑信号，避免本机运行信息进入公开仓库。

## 验证结果

1. **PowerShell 语法与解析验证**：
   - `scripts/agent-bridge/run-review.ps1` 与 `bridge-monitor.ps1` 使用单一 UTF-8 BOM；PowerShell AST 解析均返回 0 个语法错误。
2. **单一提交/工作区 Diff 兼容性验证**：
   - 修复了根提交无 `HEAD~1` 时的回退兼容逻辑，自动降级对比当前未提交工作区。
3. **全链路文档追踪验证**：
   - `docs/README.md`、`docs/07-changes/README.md`、`docs/08-reviews/README.md` 索引链条完整，与 `AGENTS.md` 保持完全一致。
4. **公开仓库运行时隔离**：
   - `.bridge-state.json`、`.codex-quota-exhausted` 与 `.codex-resume-signal.md` 均已加入忽略规则，不会进入阶段提交。
5. **审查模型目录验证**：
   - `pi --list-models v4-pro` 返回 `deepseek/deepseek-v4-pro`，与 `run-review.ps1` 的默认值和唯一允许参数一致；未执行 Flash 回退。

## 风险与回滚

- 若审查脚本或提示词误报率高：Codex 在评估时具备最终技术决策权，可将误报标记为免除并在报告中说明原因。
- 回滚策略：桥接脚本与文档模板属于辅助治理工具，不侵入业务服务逻辑，直接移除对应脚本或回退提交即可。
