# React 项目工作区

- 状态：Accepted
- 所属阶段：V1 / Day 6
- 相关架构：`../02-architecture/frontend-architecture.md`

## 用户价值

用户可在一个浏览器工作区查看项目知识和任务、与 Agent 对话、检查来源、确认或拒绝 Task 写回，并在保存前预览 Markdown，而不需要手工拼接 HTTP 请求。

## 关键流程

1. 用户登录；Web 将短期 access token 保存到当前标签页的 `sessionStorage`。
2. Web 加载用户自己的项目，选择项目后并行加载 Wiki 和 Task。
3. 用户选择或新建 Wiki，在编辑区修改 Markdown；预览区安全渲染，保存时发送当前 version。
4. 用户发送 Chat；Web 复用项目内 conversationId，展示回答与来源。
5. 响应包含 pending action 时，Web 展示 action 类型、Task、字段和预期 version。确认/拒绝只调用 Java action API；成功后刷新 Task。
6. “AI 文本整理”保留原始输入，把 Agent 回答展示为 Markdown 预览；点击明确应用后才覆盖 Wiki 草稿，仍需再次点击保存。

## 状态与错误

- loading、empty、success、error 均有可见状态；按钮在请求期间禁用，避免重复提交。
- 401 清除当前会话并返回登录；403/404/409/503 展示 Problem Details 的安全 detail 和 requestId。
- Wiki 409 不自动覆盖；提示用户刷新后重新合并。
- confirm/reject 后清除 pending action；confirm 成功刷新 Task 列表。
- 切换项目清空项目相关草稿、conversation 和 pending action。

## 测试边界

测试通过 DOM 与网络 client 的公共接口观察行为，不断言私有 state。至少覆盖：登录后加载项目、Markdown 原始 HTML 不成为 DOM、Chat 展示 pending action、确认后刷新 Task、拒绝不写、AI 返回内容必须经用户点击才进入 Wiki 草稿，以及 Problem Details 可见。

## 已知限制

V1 没有 refresh token、多标签同步、复杂路由、分页、自动保存、流式输出或真实 LLM provider。AI 整理复用现有 Agent Chat；在配置真实模型前，其文本质量受当前确定性回答能力限制。
