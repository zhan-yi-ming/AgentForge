# Pi 审核改用 DeepSeek V4.1 Flash

- 日期：2026-09-18
- 状态：Implemented（本机 Pi 与入口已修复，三次只读审核最终 PASS，待阶段提交）
- 关联：`2026-09-18-pre-v3-chat-experience-plan.md` 的 P3-01 审核门禁

## 背景与目标

DeepSeek 于 2026-09-10 发布 V4.1 Flash，官方 API 模型 ID 为 `deepseek-flash`。旧 `deepseek-v4-flash` 仅作临时兼容。当前 Pi 启动器曾因沙盒不能写用户目录 `trust.json.lock` 退出，且本机目录未刷新；仓库审核脚本和治理文档仍固定旧 ID。用户要求修复本机 Pi 并完成 P3-01 代码审核。

## 范围与非目标

- 仅刷新 Pi 模型目录，不升级程序或扩展；项目审核固定完整选择器 `deepseek/deepseek-flash`，目录预检必须精确匹配该 ID。
- 更新当前生效的审核指南、规则、入口脚本、合同测试和本地开发模型说明；历史变更记录和停用的 validation 脚本保留原貌。
- 保持只读、敏感扫描后外发、无自动换模型、失败即停；Pi 不测试、写文件或提交。
- P3-01 的暂存差异须在本次真实审核中一起检查。用户现有未暂存及未跟踪文件不纳入审核。

## 参考

- DeepSeek 官方更新日志：<https://api-docs.deepseek.com/updates/>
- DeepSeek 官方模型列表：<https://api-docs.deepseek.com/api/list-models/>

## 计划验证

Pi 模型目录精确行、审核入口空 diff 合同、PowerShell AST 解析、风险规划器、暂存差异与敏感扫描、一次性 Pi 只读 Milestone Review。完成后回填实际结果。

## 已执行证据

- 本机首次在沙盒内执行 `& $env:AGENTFORGE_PI_CMD --list-models deepseek-flash` 退出码 1，错误为用户目录 `trust.json.lock` 的 `EPERM`；沙盒外同命令退出码 0，但只列出旧本地目录项。
- 沙盒外执行 `& $env:AGENTFORGE_PI_CMD update --models`，退出码 0，输出 `Model catalogs refreshed`；没有升级 Pi 程序或扩展。
- 沙盒外再次执行 `& $env:AGENTFORGE_PI_CMD --list-models deepseek-flash`，退出码 0，精确行 `deepseek  deepseek-flash` 存在。
- `& .\scripts\validation\pi-flash-review-contract.ps1`：更新测试后、修改入口前退出码 1，`ValidateSet` 拒绝新 ID；修改后退出码 0，显式和默认新 ID 均通过目录预检，旧 V4 Flash 和 V4 Pro 均被拒绝，空 diff 守卫阻止外发。
- 沙盒外执行 `& $env:AGENTFORGE_PI_CMD --no-session --no-context-files --no-tools --thinking minimal --model deepseek/deepseek-flash -p 'Reply with exactly READY.'`，退出码 0，仅返回 `READY`；请求不含仓库差异或凭据。
- 首次 Pi Milestone Review 返回 `NEEDS_FIX`，识别一个真实的会话详情迟到回写路由问题；已记录在 `docs/08-reviews/2026-09-18-review-pre-v3-chat-routes-pi-v41-attempt-1.md` 并以先红后绿的 Web 回归修复。当前生效 README 说明同步为新模型；其余非阻塞建议逐项评估。复审前 `pi-flash-review-contract.ps1` 退出码 0，`git diff --cached --check` 退出码 0，暂存差异高置信敏感模式命中 0。
- 第二次 Pi Milestone Review 返回 `NEEDS_FIX`，识别已加载会话间切换、SSE 迟到 metadata 和无 ID `/chat` 失效条件；修复及逐项评估见 `docs/08-reviews/2026-09-18-review-pre-v3-chat-routes-pi-v41-attempt-2.md`。三项新 Web 回归先红后绿，最终全量 45/45 与生产构建通过，待最后一次只读复审。
- 第三次 Pi Milestone Review 返回 `PASS`，无阻塞项；非阻塞建议评估见 `docs/08-reviews/2026-09-18-review-pre-v3-chat-routes-pi-v41-attempt-3.md`。补充 metadata URL 验收后 Web 全量 46/46，通过构建；Pi 合同测试、差异检查及敏感模式扫描通过。
