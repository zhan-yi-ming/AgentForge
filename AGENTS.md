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

- 保持单仓库与 Java 确定性业务 / Python Agent 边界；遵守现有 ADR，不提前引入后续阶段组件。
- 禁止提交真实密钥、令牌、密码、私钥或敏感日志。
- Codex 负责文档、实现并亲自执行与风险相称的测试；Pi 只负责实现和测试完成后的独立只读审核。L0/L1 默认不调用 Pi，L2 做 Diff Review，L3 和节点/累计触发做 Review 或 Milestone Review；具体分级和升级规则见 `docs/00-governance/change-workflow.md`。禁止委托 Pi 执行测试、启动 Pi monitor、使用旧报告宣称当前工作树通过，或让 Pi 自动推进阶段。
- 交付必须有本次真实命令、退出码、工具版本、测试数量、失败/跳过数和清理证据；模型文字 PASS 不是测试证据。Java clean verify 和 Python pytest 不得以旧缓存产物代替。
- Day 1–4 全面复核完成前不推进 Day 5；之后下一阶段需用户明确授权。用户已于 2026-09-05 明确授权：Day 5 审核通过、提交并完成远端核验后开始 Day 6。
- 完成验证并回填变更记录后创建真实可读提交，随后先推送并核验；仅在用户已明确授权下一阶段时，远端核验完成后才继续实现。
- 最终汇报依次说明完成事项、验证与限制、用户可介入事项（是否必需）、下一阶段计划。

## 工程 skills 规范

遵循 https://github.com/mattpocock/skills ，按任务读取相关 SKILL.md，不把整个仓库当作单一技能，不盲目套用与 Java/Python 无关的工具。

- 排障使用 `skills/engineering/diagnosing-bugs/SKILL.md`：建立可重复的失败信号，复现和最小化，提出可证伪假设，针对证据修复，回归并清理。
- 行为修改使用 `skills/engineering/tdd/SKILL.md`：通过已约定的公共接口测试行为，一次一个纵向切片，先实际运行失败测试，再实现最小修复；避免测试私有实现、同义反复断言和过度 mock。
- 测试边界沿用功能/API/测试策略文档；范围已经由用户授权时继续执行，无需重复确认。
- 文档保存至 docs/，架构决定保存至 docs/02-architecture/decisions/，变更与验证证据保存至 docs/07-changes/ 和 docs/08-reviews/。
- 使用技能前读取原文与必要引用；用户最新指令优先，不能把技能当作跳过真实验证的理由。

## Pi 审核规则

- 用户已于 2026-09-05 明确重新授权 Pi 执行代码审核；测试始终由 Codex 执行并提供机器证据。
- 用户持续授权：风险评级要求审核时，Codex 完成开发、相称的真实测试和敏感信息扫描后，可将源码、配置、测试和文档 diff 发送给外部 DeepSeek Pi V4-pro 做一次性只读审核。
- 上述授权无需逐次确认：代码和文档写完且 Codex 门禁通过后直接触发审核。可使用本机 Pi 已有登录状态建立连接，但禁止读取、打印、复制或把 `.env`、密钥、Token、密码、私钥及实际认证凭证发送给 Pi。
- Pi 的唯一连接和触发说明见 `docs/06-operations/pi-review-connection.md`。启动器优先读取 `AGENTFORGE_PI_CMD`，其次才检查 PATH 中的 `pi.cmd`；两者均不可用时立即请用户设置，禁止全盘搜索、安装尝试、猜测路径或循环重试。
- 审核是否需要返工以可运行性和实质风险为准：只处理可复现的严重缺陷、代码无法运行、重大安全或数据一致性问题、真实冲突、架构边界破坏或明显偏离阶段目标。若架构正确且可维护性、可扩展性足够，纯风格、偏好或未来优化建议记录后可不改。
- 不恢复 OnCodexWake、monitor、自动阶段推进或 Pi validation。旧 bridge 自动入口继续保持 `DISABLED`；后续仅允许一次性、可控的只读审核调用。
- 保留历史报告用于排查，但不得复用其结论代替当前审核或测试。
- Pi 遇到登录、额度、模型或环境问题时立即中断并请用户处理，不得无限循环重试。

## 风险分级验证规则

- 每次实现完成后先根据 Git diff、调用关系和最大影响范围评级，不以行数判断：L0 为文案/CSS/注释/README/无行为整理；L1 为边界明确的单组件、小函数、局部交互或接口不变修复；L2 为 API/数据库/缓存/Tool/Agent 节点/Workflow/状态/外部调用/多模块；L3 为架构、Schema、权限安全额度限流、Agent Runtime/状态机、全局上下文、核心流程、API Contract 或公共基础模块。
- 公共函数、共享结构、API 入参与返回、数据库字段、Agent 状态、权限安全额度限流、异常/Retry/Timeout、多模块或影响不明至少 L2；命中 L3 类别直接 L3。
- L0 只做必要语法/类型/lint/构建；L1 做相关测试和必要 smoke；L2 做模块测试、核心 smoke 和 Pi Diff Review；L3 做全量测试、核心业务回归和 Pi Review。
- 连续 5 次 L0/L1、节点结束、重要 merge/commit、进入下一节点前、低风险修改扩散到多模块或测试异常时，强制全量测试、核心 smoke 和 Pi Milestone Review。每份变更记录维护累计计数；L2/L3 Review 或 Milestone Review 通过后归零。
- 每次最终汇报必须包含风险等级、修改范围、可能影响、本次验证、未执行项及原因。L0/L1 跳过全量测试或 Pi 时必须明确说明，不得无限累计跳过。

## 文档入口

- 文档地图：`docs/README.md`
- 文档制度：`docs/00-governance/documentation-first-policy.md`
- 变更流程：`docs/00-governance/change-workflow.md`
- 完成标准：`docs/00-governance/definition-of-done.md`
- 公开仓库安全：`docs/00-governance/public-repository-security.md`
- 代码审查中心：`docs/08-reviews/README.md`
- Pi 连接与触发：`docs/06-operations/pi-review-connection.md`
- 当前变更：`docs/07-changes/2026-09-06-pi-review-connection-governance.md`
