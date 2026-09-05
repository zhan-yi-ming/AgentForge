# Day 6 React 联调与 AI Markdown 工作区

- 状态：Implemented
- 日期：2026-09-05
- 阶段：V1 / Day 6

## 背景

Day 1–Day 5 已在 Java/Python/API 层跑通项目、Wiki、Task、RAG、Tool proposal 与人工确认，但浏览器端尚未实现。路线图 Day 6 要把已有公共 API 组合成可演示的 React 工作区，并让用户在 Markdown 预览中检查 AI 文本后再明确应用，不能让浏览器或模型绕过 Java 权限和确认边界。

Day 5 Pi Attempt 2 遗留一项 Low 文档问题 AF-D5-06：功能文档误称 version conflict 由 E2E 覆盖，实际由 Java 服务测试覆盖。按用户要求，本项登记到 Day 6 审核统一处理，不在 Day 5 反复修改/复审。

## 范围

- 在 `apps/web` 建立 React + TypeScript + Vite 应用，提供登录、项目选择和项目工作区。
- 工作区加载 Wiki 与 Task；Wiki 支持创建、选择、编辑、保存和 Markdown 安全预览。
- Chat 调用 Core API，展示 Markdown 回答、来源和 conversationId；出现 pending action 时展示结构化预览并提供 confirm/reject。
- AI 文本整理保留用户原始文本与 Agent 回答，先预览；只有用户点击“应用到 Wiki 草稿”才改变前端草稿，保存仍走 Wiki API 与 version 校验。
- API client 统一 Bearer token、JSON、Problem Details 和 request ID；开发服务器将 `/api` 代理到 Core API。
- 使用 Vitest + Testing Library 覆盖公共交互，并执行 TypeScript/build 验证。

不在本次范围：生产静态资源托管、复杂路由/状态库、刷新 token、SSO、富文本编辑器、附件、离线同步、流式 Chat、完整浏览器 E2E、真实 LLM provider 或自动保存。上述部署与端到端收口属于 Day 7。

## 影响

- 新增第一个可运行的 Web 应用及 npm lockfile。
- 不修改数据库模型，不新增后端业务写入口；前端只消费已实现的公开 HTTP API。
- Markdown 渲染不启用原始 HTML，避免把 Wiki/Agent 文本作为可信 DOM。
- token 仅保存在当前标签页的 `sessionStorage`；401 时清除会话并返回登录界面。

## 验证计划

1. 文档完成后，按 TDD 从 API client、Markdown 预览、登录/项目加载、Chat pending action 和显式应用 AI 草稿等公共 seam 写失败测试。
2. `npm test -- --run`：记录测试数量、失败与跳过。
3. `npm run build`：执行 TypeScript 编译与 Vite production build，记录版本和退出码。
4. 使用本地 Core API/Agent Service 的真实接口做浏览器或 HTTP 联调；完整三应用自动化 E2E 可在 Day 7 固化，但 Day 6 至少验证关键页面请求链路。
5. 扫描敏感信息、运行 `git diff --check`，清理 `node_modules`、coverage、dist 和临时日志。
6. 将经 Codex 验证的完整 Day 6 diff 交给 DeepSeek Pi V4-pro 一次性只读审核；只处理重大问题，小问题连同 AF-D5-06 集中记录，不反复修改与复审。

## 回滚思路

`apps/web` 是独立应用，回滚可删除 Web 构建产物与源码，不影响 Core API、Agent Service 或数据库。浏览器草稿未保存时不是业务事实；已保存的 Wiki/Task 仍按 Java API 与 version 规则保留。

## 实施与验证回填

### 实施结果

- 新建 React 19 + TypeScript + Vite 单页应用和锁文件，API client 统一处理 Bearer token、Problem Details 与 request ID。
- 完成登录、项目选择、Wiki 创建/编辑/保存、Task 列表、Chat 来源、pending action 确认/拒绝，以及 AI Markdown 预览和显式应用草稿。
- Markdown 使用 `react-markdown` + GFM 渲染，不启用原始 HTML，也未使用 `dangerouslySetInnerHTML`。
- 开发服务器通过 `/api` 代理 Core API；未新增数据库、后端写入口或跨越 Java 权限边界的前端逻辑。
- 将 `.tsbuildinfo` 和 TypeScript 构建产生的 Vite 配置文件加入忽略规则，防止生成物进入提交。
- 集中修正 Day 5 遗留 Low AF-D5-06：版本冲突实际由 Java 服务测试覆盖，而不是双服务 E2E。

### TDD 与自动验证证据

- RED 1：测试首次运行因 `src/api`、`src/App`、`src/MarkdownPreview` 尚不存在而失败。
- RED 2：实现后 6 项中 4 项失败，暴露原生 `Headers` 断言错误和测试树未清理导致的重复元素；修正测试 seam 后转绿。
- 审核修正前 RED：新增 2 项行为测试真实失败，分别复现格式化请求复用正式 conversationId、切换项目保留整理草稿；实现最小修正后转绿。
- `npm test -- --run`：最终退出码 0；Vitest 3.2.7；3 个测试文件、10 项测试全部通过，0 失败、0 跳过。
- `npm run build`：退出码 0；Vite 7.3.6；TypeScript 编译及 production build 成功，共转换 283 个模块。
- 工具版本：Node.js 24.14.0，npm 11.9.0。npm 输出一条本机未知 `home` 配置警告，不影响安装、测试或构建。

### 真实链路与视觉验证

- 使用隔离的 pgvector PostgreSQL、真实 Core API 和 Vite dev server，通过 `http://127.0.0.1:5173/api/v1` 完成注册、Bearer 鉴权、创建项目、创建 Markdown Wiki，并读取 Project/Wiki/Task 接口；结果 PASS，证明 Web origin → Vite proxy → Core API 主链路可用。
- 为补齐核心动作契约，执行 `scripts/validation/day5-e2e.ps1` 的隔离跨进程验证：真实 Java Core API → Python Agent Service 的 Chat/confirm/reject 全部 PASS；确认前 Task=0，确认及重复确认后 Task=1，更新后 version=1，拒绝后最终 Task 仍为 1，跨用户操作返回 403。该结果同时核验前端 `AgentChat` / `AgentAction` 使用的 `pendingAction`、`actionType`、`status`、`resultTask` 等 JSON 字段。
- `mvnw.cmd clean verify`：Java 21.0.12.1，退出码 0；75 项测试，0 失败、0 错误、6 跳过（需单独契约环境的 6 项）；干净 JAR 构建成功。
- 使用应用内浏览器打开真实 Vite 页面，确认窄视口登录页标题、表单、对比度和响应式布局正常；未输入凭据或产生业务写入。
- 冒烟与 E2E 结束后停止 Vite、Core API 和 Agent Service，并执行隔离 Compose `down -v`；临时容器、网络与卷均已移除。最终删除 `node_modules`、`dist`、TypeScript 构建元数据、Maven `target`、Python `.venv` 和测试缓存。

### 审核与限制

DeepSeek Pi V4-pro Attempt 1 已完成，只读结论为 `NEEDS_FIX`，明确无 Blocking，提出 3 项 Medium 和 3 项 Low。按用户约定不进行小问题反复复审；6 项已在一批修改中全部解决，随后由 Codex 完成 10 项前端测试、production build、Java clean verify 和真实 Chat/confirm/reject E2E。最终处置为 `ACCEPTED_AFTER_BATCH_FIXES`，不启动 Pi Attempt 2。

Day 6 不包含生产托管、完整浏览器自动化 E2E、刷新 token、路由/全局状态库、流式 Chat 或真实 LLM provider；这些限制与本次范围一致。
