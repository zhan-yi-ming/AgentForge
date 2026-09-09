# V2 Demo 界面修复与可体验默认数据

- 状态：Accepted
- 日期：2026-09-09
- 风险：L2（Web 行为、部署种子数据、Agent 配置）
- 影响域：Web、Agent Service 配置、生产 Demo 数据、部署验证

## 背景

V2 公网 Demo 的浏览器图标仍使用默认图标，顶部版本仍显示 V1.2；对话页返回按钮可能覆盖品牌标识，顶栏随页面滚动，紧凑输入框的放大按钮视觉未居中。历史会话恢复又假设消息永远严格按 USER/ASSISTANT 两条一组，遇到不完整或遗留消息序列时会漏渲染 AI 回答。已有 Demo 项目还会让种子脚本直接返回，无法为线上账号补齐可立即体验的 Wiki 与 Task 数据。

## 目标

- 提供不含 “AF” 字样的 AgentForge 品牌图标，并用于页面品牌位与浏览器 favicon。
- 顶栏固定在视口顶部，返回按钮与品牌区域互不覆盖；版本显示为 `V2 Live Demo`。
- 放大/缩小按钮内容在紧凑 Composer 中保持水平、垂直居中。
- 历史详情按消息角色稳健恢复，保留可配对的 AI 回答，不因非严格交替序列丢失。
- 生产种子脚本对已有项目幂等补齐面试体验数据；每个 Demo 用户持有独立副本，任一用户删除不影响其他用户。
- 支持通过服务器私有环境附加运行时 Prompt 指引；具体线上 Prompt 内容不进入仓库或公开文档。

## 明确不做

- 不引入跨用户共享可变实体或全局软删除 Schema。
- 不提交生产凭据、私有 Prompt 内容或生产数据库导出。
- 不启动 V3 Node，不改变 Java 负责权限与写入的边界。

## 验证计划

- Web 公共 DOM seam：历史消息恢复、版本文本、品牌图标、返回按钮/顶栏类名与 Composer 控件。
- 部署脚本 seam：Shell 语法与静态契约，验证已有项目不会阻止缺失种子项补齐。
- Agent 配置 seam：可选私有 Prompt 后缀进入 system message，默认配置保持原行为。
- 运行门禁规划器，并按最终影响域执行 Web、Agent 相关测试和生产配置验证。

## 实现

- Web 使用新的 `brand-mark.svg` 作为 favicon、登录品牌图标和顶栏标识，顶栏固定并为对话返回按钮保留安全区，版本更新为 V2。
- 历史详情按角色流构建问答：连续 USER 以最后一条为待回答问题，连续 ASSISTANT 合并到当前回答，从而兼容遗留或不完整序列。
- Demo 种子脚本不再因同名项目存在而退出，而是按标题幂等补齐 3 篇 V2 Wiki 与 3 个体验 Task。每个账号仍通过自己的项目 API 创建独立副本，没有新增共享实体或删除传播。
- Agent Service 增加默认空值的 `AGENTFORGE_AGENT_SYSTEM_PROMPT_SUFFIX` 运行时注入点。仓库只包含通用机制；具体数据定向内容保留在服务器私有环境。

## 验证回填

- `npm test -- --run tests/app.test.tsx`（`apps/web`，Node/npm 现有工作区工具链）：退出码 0，1 test file、31 tests passed；覆盖非严格消息序列恢复、V2 品牌标识及既有 Web 回归。
- `npm run build`（`apps/web`）：TypeScript `tsc -b` 完成，Vite/esbuild 因沙箱拒绝读取工作区父目录退出 1；属于执行环境权限，尚未取得本地 production bundle 成功证据，需在生产标准 Docker build 中复验。
- `.venv/Scripts/python.exe -m pytest tests/test_llm.py -q`（`services/agent-service`）：退出码 0，22 passed。
- `.venv/Scripts/python.exe -m pytest -q`（`services/agent-service`）：退出码 1，97 passed、3 failed、0 skipped；3 个失败均在创建 Testcontainers 前因沙箱拒绝 Docker named pipe，未进入产品断言。变更直接相关的 LLM 配置套件已单独全绿。
- Git for Windows Bash `-n scripts/deploy/seed-demo-v12.sh`：退出码 0。
- `pwsh -NoProfile -File scripts/validation/v2-prep-demo-experience.ps1`：退出码 0，固定账号、浏览器凭据边界及 V2 幂等种子契约通过。
- `scripts/validation/plan-change-gates.ps1 -BaseRef HEAD -TargetRef WORKTREE -Paths <本次文件> -Json`：退出码 0，风险 L2，影响域 AgentService/Deployment/Docs/Web；Unknown 仅为验证脚本分类，人工确认不改变运行时边界。
- `git diff --check`：退出码 0；PowerShell 文件提示未来 checkout 的 LF/CRLF 转换 warning，无 whitespace error。
- 测试未创建数据库或生产数据，当前无需本地数据清理；生产种子数据与私有 Prompt 在标准发布阶段验证。
- Pi V4-pro Diff Review 预检执行模型目录查询时退出码 1；按连接治理立即停止且未重试、未降级。用户于 2026-09-09 明确授权“本次豁免 Pi 审核”，因此本变更按一次性豁免继续提交与发布。
