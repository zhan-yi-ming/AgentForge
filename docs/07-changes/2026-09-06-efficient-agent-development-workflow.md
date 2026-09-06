# 高效且不跑偏的 Agent 开发流程

- 日期：2026-09-06
- 状态：Implemented
- 阶段：工程治理
- 交付目标：`codex/optimize-agent-workflow`

## 背景

现有流程通过文档先行、Node Boundary、全量回归、敏感扫描和 Pi Review 控制偏航，但把风险等级与测试覆盖面绑定得过紧：节点结束、重要提交或 L3 经常无条件执行 Java、Python、Web 和跨进程全套门禁，即使实际只修改一个模块。随着仓库扩大，全文重读、长日志进入上下文、同一任务内重复测试以及任务末尾才处理 Git 基线，使单个节点的时间和 Codex 额度消耗持续上升。

## 目标

- 保留文档先行、Node Scope、TDD、真实验证、敏感扫描、Pi 独立审核和提交/推送核验。
- 把“风险等级”和“受影响系统范围”拆成两个维度：高风险不等于无条件全仓，只有 Release Gate、影响未知或真实跨全仓变化才跑完整仓库。
- 建立可执行的变更门禁规划器，根据路径、跨模块范围和显式里程碑/发布标志输出最低验证集合；未知路径自动升级。
- 同一任务内允许复用输入未变化的本次测试证据；发生相关源码/配置变化、失败或 Review 指出跨模块风险时再重跑或扩大。
- 将 Agent 必读上下文收敛为任务上下文包，减少重复加载治理全文和无关历史。
- 默认只把命令、版本、退出码、数量、失败摘要和清理结果写入上下文；完整成功日志不重复展开。
- 在任务开始时完成远端、分支、base commit 和用户已有改动检查。

## 非目标

- 不取消文档先行、Node Start/Close Gate、用户授权、TDD、真实容器测试或敏感扫描。
- 不允许用历史任务、旧缓存或 Pi PASS 代替当前任务机器证据。
- 不降低 Schema、权限、安全、状态机、公共契约和 Release Gate 的验证要求。
- 不实现 CI 平台、后台 monitor、自动提交、自动推送或自动开始下一 Node。
- 不修改 AgentForge 产品行为，也不在本变更中处理 TLS/域名兼容。

## 受影响文档

- `docs/00-governance/efficient-validation.md`：新增风险、范围、上下文、证据复用和门禁规划的唯一细则。
- `AGENTS.md`：只保留不可削弱的不变量与权威入口，删除重复展开的流程细节。
- `docs/00-governance/change-workflow.md`：调整上下文读取、Git preflight、验证选择和提交后衔接。
- `docs/00-governance/definition-of-done.md`：以影响范围和有效证据定义完成，不再把 Node 结束等同于全仓回归。
- `docs/00-governance/v2-v3-node-development-protocol.md`：Node 继续做 Milestone Review，但验证范围由门禁矩阵决定。
- `docs/05-development/testing-strategy.md`：增加两维门禁、同任务证据复用和成功日志压缩规则。
- `docs/08-reviews/README.md`、`docs/06-operations/pi-review-connection.md`：收敛 Pi 输入与复审触发。

## 设计决定

采用“风险等级 × 影响范围”双轴模型。风险决定审查强度与必须覆盖的失败类型，影响范围决定运行哪些模块和集成链路。门禁规划器是保守建议器：它只输出最低集合，Codex 可以因调用关系或证据向上升级，不能忽略命中的强制门禁。路径无法分类时至少 L2，并要求人工确认影响范围。

测试 seam 已由用户确认：`scripts/validation/plan-change-gates.ps1` 的命令行 JSON、退出码和人类可读摘要。代表性测试覆盖 docs-only、Python-only、TLS、跨服务/API、Schema/安全和未知路径升级，不测试脚本内部函数。

## 实现

- `scripts/validation/plan-change-gates.ps1` 支持 `WORKTREE`、`INDEX`、两个 Git ref 或显式 `-Paths`，输出风险、影响域、最低门禁、Pi 模式、理由和内容 fingerprint；未知路径至少 L2，`-Milestone` 只扩大 Review，`-ReleaseGate` 才固定全仓。
- `scripts/validation/test-plan-change-gates.ps1` 从公共 CLI 验证 docs-only、Python-only、TLS、跨服务 API、Schema、未知路径、共享构建、Node Milestone 与 Release Gate，并覆盖 Git INDEX/WORKTREE/ref、人类摘要和无变更/绝对路径失败契约。
- `AGENTS.md` 只保留不可削弱的边界与权威入口；验证矩阵、上下文包、证据复用、Pi 输入和推送后衔接分别由对应文档维护。
- `change-workflow.md`、DoD、Node 协议、测试策略、Review 与 Pi 连接说明统一采用风险 × 影响域双轴，并允许已经明确授权排序的非 Node 后续任务在独立提交后继续。
- 变更记录模板要求优化生效后的前三个任务记录耗时、读取文件数、测试/重复测试/Pi 次数、fingerprint 与返工原因。
- 不新增运行时依赖；脚本只使用 PowerShell、Git 和 .NET 标准加密 API。

## 验证计划

- 先运行尚不存在的规划器自检，确认红灯。
- 逐个纵向切片实现代表场景，最终运行完整规划器自检。
- 对两个脚本执行 PowerShell parser 检查；对当前真实 diff 运行规划并核对 L2 / Diff Review。
- 执行文档路径/链接、`git diff --check` 和 Gitleaks；不运行未受影响的 Java、Python、Web 产品测试，并在结果中说明。
- 完成 Pi V4-pro Diff Review；只有真实阻塞项才修复并复审。

## 风险与回滚

风险等级为 L2。主要风险是路径规则漏判导致少跑测试，或规则过宽仍然没有节省。防护包括未知路径升级、公共边界/Schema/安全强制 L3、跨模块自动加入集成 smoke、Release Gate 固定全仓，以及 Codex 基于调用关系向上升级。

回滚时恢复本次治理文档和 `AGENTS.md`，删除两个规划器脚本，即回到原来的节点级全量门禁；不涉及产品数据、部署环境或数据库。

## 验证结果

- TDD 红灯：首次执行 `scripts/validation/test-plan-change-gates.ps1` 因规划器不存在退出码 1；实现后第一轮暴露测试脚本错误地把 `-Json` 吞入数组参数且错误依赖 `$LASTEXITCODE`，修正为具名 splatting。Pi Attempt 1 后，共享构建用例先真实得到 `L2`（预期 `L3`）退出码 1，绝对路径用例先静默成功（预期报错）退出码 1；分别完成最小实现后转绿。
- PowerShell 7.6.5 parser 对规划器与自检脚本返回 0 errors、退出码 0；公共 CLI 自检退出码 0，15 项代表检查通过，覆盖显式路径、WORKTREE、INDEX、双 ref、JSON、人类摘要、无变更与非法绝对路径。
- 真实暂存差异规划：`plan-change-gates.ps1 -BaseRef HEAD -TargetRef INDEX -Json` 返回 `L2`、影响域 `Docs/Governance`、门禁 `diff-check/docs-consistency/gate-planner-contract/gitleaks-final/powershell-parser`、Pi `Diff`；没有 Java/Python/Web/TLS/跨进程门禁。修复后、结果回填前的 INDEX fingerprint 为 `8b7819fdea57cfb57f6a6b80c728568625b39e93ad8f912971e791bbc82fe3f7`；fingerprint 不跨 source 模式比较，后续纯证据回填会自然改变它。
- 文档引用核对 13 个权威路径全部存在；`AGENTS.md` 从 75 个物理行/3467 字符缩短为 51 个物理行/2709 字符，关键边界保留。
- 未运行 Java、Python、Web、Compose 或产品 E2E：本次没有修改产品源码、依赖、运行配置、Schema 或公共 API，规划器与人工调用关系判断均未命中这些影响域。
- `git diff --cached --check` 退出码 0；13 个权威文档/脚本路径全部存在。
- Gitleaks v8.30.1 使用本机缓存容器扫描修复后的暂存 diff 约 83.23 KB，耗时 67.2 ms；纳入 Attempt 2 报告后扫描完整暂存 diff 约 93.90 KB，耗时 250 ms；两次退出码均为 0，`no leaks found`。
- 本次相关门禁不启动 Docker、数据库、Java、Python 或 Web 产品进程，没有测试容器、命名卷、build 目录或临时产品数据需要清理。
- Pi Attempt 1 已由 `run-review.ps1` 调用 `deepseek/deepseek-v4-pro`，本地状态于 2026-09-06 23:06:16 +08:00 标记 `COMPLETED`；用户随后确认 DeepSeek 服务端出现对应扣费记录，因此该次远程审核证据有效。Attempt 1 结果为 `NEEDS_FIX`，报告已归档，修复后执行 Attempt 2。40%–70% 是后续三个真实任务的观察目标，本次不宣称已经达成。

### Pi Attempt 1 处置

- M1 接受：根级 Maven/Gradle/Node 依赖与锁文件、根级 Compose、`Makefile`、`.github/workflows/` 属可识别的共享构建变化，必须直接 L3 + `full-repo-regression`，不能只给 Unknown/L2。先补共享构建红灯测试，再实现最小分类分支。
- S1 接受：现有场景只走显式 `-Paths` JSON。增加临时 Git 仓库的 INDEX、WORKTREE、两个 ref、无变更失败，以及人类可读摘要契约；测试只通过公共 CLI。
- S2 记录契约：source 已进入 fingerprint，后续只允许在相同 source 模式与路径集合内比较，不要求跨模式相同；删除路径仍由路径名 + `missing` 区分，足够判断同一变更输入。
- N1 接受信息修正：改用物理行口径，`AGENTS.md` 为 75 行缩短至 51 行；字符口径为 3467 至 2709（不含换行）。
- N2 接受并收紧：`-Paths` 只接受仓库相对路径，绝对路径明确失败，不再静默降级指纹。

### Pi Attempt 2 结论

- Attempt 2 进程退出码 0，`REVIEW_RESULT: PASS`，上一轮 M1/S1/S2/N1/N2 全部闭环，报告状态为 `RESOLVED`。
- S-1（`Author` 可能被 `auth` 子串误升）与 S-2（纯治理文档多跑脚本门禁）只会扩大验证、不造成漏测，且当前契约检查约 3 秒。为避免在 PASS 后因非阻塞精度建议触发第三次付费复审，本次记录豁免；首次出现真实误判或形成可测成本时再用独立 TDD 切片处理。
- 本次共执行 2 次 Pi：Attempt 1 发现并推动 1 个高风险缺口修复，Attempt 2 通过；没有循环重试或第三次调用。
