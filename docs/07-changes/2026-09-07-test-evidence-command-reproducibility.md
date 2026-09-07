# 测试证据必须记录可执行命令

- 日期：2026-09-07
- 状态：Implemented
- 风险等级：L0（治理文档，不改变产品运行行为）

## 背景

仅记录“通过”、勾选标记或结果摘要，无法让用户和后续维护者确认测试是否真实执行，也无法复现相同门禁。用户要求每次测试成功后必须把对应成功命令一并写入文档，避免模型文字或记忆被误当成机器证据。

## 规则

- 每个成功的测试、构建、配置校验或 smoke 都必须在当次变更记录中写出实际执行的完整命令；不能只写 `PASS`、✅、“同一命令”或测试数量。
- 命令证据同时记录工作目录或足以确定工作目录的模块上下文、工具与版本、退出码、passed/failed/skipped 数量（适用时）和清理状态。
- 失败后重跑成功时，失败命令与成功重跑命令都要分别写明；权限提升、参数修正、环境切换等差异不得省略。
- Pi PASS、旧报告、口头总结、缓存产物和界面勾选均不能替代可执行命令。
- 命令不得包含密码、Bearer token、私钥、API key 或其他真实凭据；含认证的 smoke 应使用不回显凭据的脚本/环境边界，并只记录脱敏命令。

## 修改范围

- 更新文档先行制度、标准变更流程、完成标准、测试策略和变更记录模板。
- 在当前 AI 文本整理变更中补齐最新 main 集成阶段的成功命令。
- 不修改代码、配置、数据库、部署脚本或 API。

## 验证

- 首次把 PowerShell 检查与包含 Markdown 反引号的双引号 `rg` 正则拼为一个命令时，命令在输出证据前退出 1；这是验证命令转义错误，不作为文档结论。随后拆分并改用单引号正则重跑。
- 在仓库根目录执行 `git diff --check`，退出码 0。
- 在仓库根目录执行 `$required=@('docs/00-governance/documentation-first-policy.md','docs/00-governance/change-workflow.md','docs/00-governance/definition-of-done.md','docs/05-development/testing-strategy.md','docs/templates/change-record-template.md','docs/07-changes/2026-09-07-test-evidence-command-reproducibility.md'); $missing=@($required | Where-Object { -not (Test-Path -LiteralPath $_) }); Write-Output "REQUIRED=$($required.Count) MISSING=$($missing.Count)"`，退出码 0；6 个必需路径存在、0 缺失。
- 在仓库根目录执行 `rg -l '同一命令.*证据|同一命令.*数量|同一命令.*完成标准' docs/00-governance docs/05-development/testing-strategy.md docs/templates/change-record-template.md docs/07-changes/2026-09-07-test-evidence-command-reproducibility.md`，ripgrep 15.2.0，退出码 0；6 个目标文件命中硬规则。
- 在仓库根目录对 `git diff` 执行私钥头与 Bearer credential 脱敏正则计数，退出码 0；两类命中均为 0。Git 版本为 2.23.0.windows.1。
- 本次只修改文档，未运行应用测试、构建或 Pi；原因是产品代码、配置和运行行为均未变化，风险为 L0。当前 AI 整理实现的 L2 测试与 Pi Attempt 2 PASS 仍由其独立变更记录保存，不以本次文档检查替代。

## 回滚

如需回滚，仅恢复上述治理文档；不会影响应用或生产数据。该规则用于增强证据可复现性，原则上不建议回滚。
