# Pi 只读审核模型切换至 DeepSeek V4 Flash

- 日期：2026-09-18
- 状态：In Progress
- 范围：Pi 提交前只读审核入口与当前治理文档；不改变业务服务或历史审核报告

## 背景与决定

用户明确要求将本项目 Pi 审核从 `deepseek/deepseek-v4-pro` 切换为 Flash。用户口述的 `deepseek-flash` 在本机 Pi 模型目录中对应精确的非视觉 ID `deepseek/deepseek-v4-flash`。本机已用 `--no-session --no-context-files --no-tools --model deepseek/deepseek-v4-flash -p 'Reply with exactly READY.'` 获得 `READY`，DeepSeek 认证检查为 `ready`。视觉实验版 `deepseek-v4-flash-vision-exp` 不在范围。

## 目标与边界

- 当前根/子目录规则、连接指南、审核脚本和报告元数据均指向精确模型 `deepseek/deepseek-v4-flash`。
- 保留 Pi 只读、Codex 自行测试、敏感扫描先于外发、一次性审核、显式超时和失败即停止；禁止自动换用任何其他模型。
- 不改写既往 V4-pro 报告、历史变更记录、禁用的 monitor/validation 流程或产品代码。

## 目标文档与验证

- 更新 `AGENTS.md`、`scripts/agent-bridge/pi/AGENTS.md`、`docs/06-operations/pi-review-connection.md`、`docs/08-reviews/README.md`、`docs/00-governance/change-workflow.md` 与 `docs/00-governance/definition-of-done.md`。
- 更新 `scripts/agent-bridge/run-review.ps1` 与只读审核提示词；通过 PowerShell 解析、模型目录/认证/最小真实响应、风险规划、diff check 与敏感扫描验证。审核入口仍只接收精确 ID，目录缺失即失败。

## 当前验证证据

- 仓库根目录运行本机 Pi `--list-models deepseek-flash`，退出码 0；精确非视觉目录行为 `deepseek  deepseek-v4-flash`，另列视觉实验版但未选用。`auth check --provider deepseek --model deepseek-flash` 退出码 0、返回 `ready`，不输出凭据。
- 仓库根目录运行本机 Pi `--no-session --no-context-files --no-tools --thinking minimal --model deepseek/deepseek-v4-flash -p 'Reply with exactly READY.'`，退出码 0、返回 `READY`；不含仓库差异或敏感数据。
- 仓库根目录执行 `& .\scripts\validation\pi-flash-review-contract.ps1`：修改前退出码 1，精确 Flash ID 被原 `ValidateSet` 拒绝；最小实现后退出码 0，Flash 通过目录预检并在空 diff 守卫处停止，旧 V4-pro ID 被参数绑定拒绝。两次均未向外部模型发送仓库 diff。
- 扩展默认值合同后首次运行退出码 1：测试自身使用了 PowerShell 数组位置 splatting，把 `HEAD` 错传给 `Attempt`。修正为具名 hashtable splatting 后，`& .\scripts\validation\pi-flash-review-contract.ps1` 退出码 0；显式/默认 Flash 均通过预检，旧 V4-pro 被拒绝，仍无仓库 diff 外发。
- 风险规划：仓库根目录执行 `plan-change-gates.ps1 -BaseRef HEAD -TargetRef WORKTREE -Paths <本次 11 个显式路径> -Json`，退出码 0，L2 / Governance、Docs、Unknown / Diff Review，fingerprint `e441790259e91ba3e3e2f5214cfd0259ee5ba7942a1e220d4db86304d43f7afb`。Unknown 为审核入口和新增合同测试，人工确认仅影响提交前审核机制、不影响业务运行链路。
- PowerShell AST 解析 `run-review.ps1` 和 `pi-flash-review-contract.ps1` 均无错误；`git diff --check` 退出码 0，仅有既有行尾转换 warning。
- 暂存的 11 文件差异经 Gitleaks v8.30.1 扫描约 18.57 KB，退出码 0、`no leaks found`；用户原有的未暂存 Markdown、`.worktrees/` 与 DOCX 不在范围内。
- 一次性只读 Pi Diff Review 调用被本机安全审批在进程启动前拒绝：虽然用户授权模型切换，审批认为尚未明确授权将这 11 文件差异发送至外部 DeepSeek Flash；没有项目差异外发、没有 Pi 报告或 PASS。用户随后要求先做本地提交，因此本次仅建立可回滚检查点，保持 `In Progress`，不宣称完成审核、不推送远端。后续若需正式交付，须先取得该差异外发的明确授权并通过 Diff Review，再另作收尾提交与推送。
