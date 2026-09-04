# AgentForge 项目级指令

本文件是仓库内所有 AI 开发代理的最高优先级项目规则。若子目录存在额外的 `AGENTS.md`，子目录规则只能补充本文件，不能削弱本文件。

## 文档先行：不可跳过的硬规则

**任何增、删、改都必须先更新文档，再开始修改代码、配置、脚本、数据库或基础设施。**

执行顺序固定如下：

1. 先阅读 `docs/README.md`、相关架构文档、功能文档、ADR 和最近的变更记录。
2. 在 `docs/07-changes/` 新建或更新本次变更记录，写明背景、范围、影响、验证方式和回滚思路。
3. 先修改受影响的产品、架构、功能、API、开发或运维文档，使文档描述目标状态。
4. 文档完成后，才允许修改实现。
5. 实现完成后回看文档，补齐实际文件、接口、数据结构、测试结果和已知限制。

禁止事项：

- 禁止“先改代码，之后再补文档”。
- 禁止只在聊天、提交信息或代码注释里记录设计决定。
- 禁止新增功能而不创建或更新 `docs/03-features/` 下的功能文档。
- 禁止改变系统边界、依赖方向、数据模型或公共接口而不新增或更新 ADR。
- 禁止删除行为或能力而不在文档中说明删除原因、迁移方式和影响。

如果请求要求直接改代码但没有文档，AI 必须先完成文档步骤，不需要等待额外提醒。紧急修复也不能跳过；可以写精简记录，但仍需先记录再修复。

## 项目工作方式

- 当前项目采用单仓库管理，目录职责以 `docs/02-architecture/repository-structure.md` 为准。
- Java 后端按业务能力分包，在功能内部保持 API、应用、领域、基础设施职责清晰。
- Python Agent 负责概率性决策；Java Core API 负责权限、校验和确定性写入。
- 不提前引入路线图后续阶段的组件。V1 不引入 Neo4j、Langfuse、LiteLLM 或 MCP。
- 新技术第一次引入时，相关文档必须解释：为什么需要、位于请求链路哪里、失败时如何排查。
- 每次交付前运行与改动相称的测试，并把命令和结果写入本次变更记录。
- 本仓库公开展示，禁止提交真实密钥、令牌、密码、私钥、生产连接串或敏感日志；具体执行 `docs/00-governance/public-repository-security.md`。
- 每个阶段完成并验证后，由 AI 创建一个真实、可读的 Git 提交；提交前必须确认变更记录已经回填。
- Git 提交成功后当前执行回合只允许推送并核验；自主 heartbeat 可在后续独立回合消费 Pi 报告、继续同阶段修复或在 Pi 通过后推进下一阶段。
- 推送后的最终汇报必须依次说明：本阶段已完成事项、验证与已知限制、用户可介入事项（明确标注是否必需）、下一阶段计划。
- 用户已授权自主交付循环：Pi 通过后可自动推进下一路线图阶段；第三次审查仍有问题时必须等待用户人工决定。

## 审查循环触发规则

- Codex 每次收到用户消息、从额度恢复或开始新的执行回合时，必须先运行 `scripts/agent-bridge/review-loop.ps1 -OnCodexWake`，再决定是否进行业务开发。
- 若循环返回 `WAITING_FOR_CODEX_FIX`，Codex 必须先阅读对应 `docs/08-reviews/` 报告，逐项研判并遵守文档先行后再修复；不得跳到下一阶段。
- 若循环返回 `HUMAN_REQUIRED`，或同一阶段第 3 次 Pi 报告仍为 `NEEDS_FIX`，Codex 必须立即停止并请求用户决定；不得进行第 4 次自动审查或自动修改。
- 在创建阶段提交前，Codex 必须确认该工作树的 bridge monitor 已启动；它是提交后仍能独立启动 Pi 审查的唯一后台执行者。monitor 不得使用 Flash，且不能自动改写业务代码。

## 双 Agent 开发与审查分工 (Codex 与 Pi Agent)

依据产品路线规划，项目采用“主开发 (Codex) + 独立审查员 (Pi Agent)”协同模式：
- **Codex (GPT)**：主开发 Agent。负责架构、编码、测试、修复及阶段 Git 提交与推送。完成交付后中断，并读取审查报告决定是否修复。
- **Pi Agent (DeepSeek V4-pro)**：独立代码审查员。审查模型固定为 `deepseek/deepseek-v4-pro`，禁止使用或降级到 Flash。强制只读审查模式，严禁直接修改业务代码。重点审查 Bug、权限绕过、API 契约不一致、并发/幂等问题与测试缺失。审查结果输出至 `docs/08-reviews/`。
- **异步交接**：双方通过 `docs/08-reviews/` 标准文档交接；Codex 恢复后评估修复，不盲目照改。遇到 5 小时限额时由调度器进入自动等待倒计时并在重置后自动续跑。

## 文档入口

- 文档地图：`docs/README.md`
- 文档制度：`docs/00-governance/documentation-first-policy.md`
- 变更流程：`docs/00-governance/change-workflow.md`
- 完成标准：`docs/00-governance/definition-of-done.md`
- 公开仓库安全：`docs/00-governance/public-repository-security.md`
- 代码审查中心：`docs/08-reviews/README.md`
- 当前变更：`docs/07-changes/2026-09-03-codex-pi-bridge-and-code-review.md`
