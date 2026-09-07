# React 项目工作区

- 状态：Accepted
- 所属阶段：V1 / Day 6
- 相关架构：`../02-architecture/frontend-architecture.md`

## 用户价值

用户可在一个浏览器工作区查看项目知识和任务、与 Agent 对话、检查来源、确认或拒绝 Task 写回，并在保存前预览 Markdown，而不需要手工拼接 HTTP 请求。

首次访问者从居中登录卡片获知账号是简历上的邮箱、密码是微信号，页面不显示或内置凭据明文；登录后，Agent Chat 位于单列主内容中央和首位，Wiki、Task、AI 文本整理按纵向次序保留。轻量新手引导说明项目选择、提问、写入确认与下方工具，降低 HR/面试官首次体验成本。

## 关键流程

1. 用户根据登录卡片提示填写受限 Demo 凭据；短期 access token 只保存到当前标签页的 `sessionStorage`，密码不进入页面源码、URL 或浏览器持久化。
2. Web 加载用户自己的项目，选择项目后并行加载 Wiki 和 Task；首次登录显示可关闭的新手引导，完成标记保存在 `localStorage`，顶栏可重新打开。
3. 用户在主内容中央发送 Chat；Web 复用项目内 conversationId，并在当前项目的浏览器内存会话中保留问答。最新一条默认展开，旧记录默认收起且可逐条展开；切换项目或刷新页面后清空。
4. 用户向下选择或新建 Wiki，在编辑区修改 Markdown；预览区安全渲染，保存时发送当前 version。
5. 响应包含 pending action 时，Web 展示 action 类型、Task、字段和预期 version。确认/拒绝只调用 Java action API；成功后刷新 Task。
6. “AI 文本整理”保留原始输入，通过独立、无 conversationId 的 SSE 请求增量展示 Markdown；共用 Markdown 渲染器会移除单个完整外层 `markdown` / `md` 围栏，但保留正文内部代码块。整理与项目 Chat 互斥，结果不改变 Chat 状态或接受 tool proposal；点击明确应用后才覆盖 Wiki 草稿，页面滚动到 Wiki 编辑区并显示反馈，仍需再次点击保存。

## 状态与错误

- loading、empty、success、error 均有可见状态；Chat 和整理按钮在任一生成请求期间互斥禁用，避免重复提交和 pending action 竞态。
- 登录提交失败统一展示“请联系我 向我索要体验账号”，避免泄露账号存在性或后台细节；已登录请求返回 401 时清除当前会话并返回登录，其他 403/404/409/503 展示 Problem Details 的安全 detail 和 requestId。
- 整理流在 complete 前发生非取消错误时清除半成品，避免失败内容仍可应用到 Wiki；项目切换等主动取消不显示错误。
- Wiki 409 不自动覆盖；提示用户刷新后重新合并。
- Wiki 保存成功显示明确反馈，并以服务端返回和刷新列表中的最新页面、version 更新当前草稿；失败时只展示错误，不显示成功。
- 保存 Wiki 不会创建 Task；“执行任务”只展示 Task API 数据，并在界面明确说明只有 Agent 提出且用户确认的任务操作才会改变这里。用户应以 Wiki 编辑区的保存状态和版本号判断保存结果。
- confirm/reject 后清除 pending action；confirm 成功刷新 Task 列表。
- 切换项目取消 Chat 与整理流，并清空项目相关草稿、conversation 和 pending action；旧项目迟到事件不可写入当前视图。

## 测试边界

测试通过 DOM 与网络 client 的公共接口观察行为，不断言私有 state。至少覆盖：登录页不出现凭据明文、只显示简历邮箱与微信号提示、任一凭据错误显示统一联系文案、首次引导关闭/持久化/重新打开、登录后 Chat 是主内容首个功能、项目加载、Markdown 原始 HTML 不成为 DOM、任一预览中的完整外层 Markdown 围栏被移除但正文内部代码块保留、项目对话最新展开且旧记录可展开、执行任务语义说明、AI 整理使用无 conversationId 的流式接口并增量预览、与 Chat 互斥、项目切换取消旧流、失败半成品不可应用、意外 proposal 不污染 Chat、应用后滚动定位与反馈、Wiki 保存成功反馈、Chat 展示 pending action、确认后刷新 Task、拒绝不写，以及 Problem Details 可见。

## 已知限制

当前没有 refresh token、多标签引导同步、复杂路由、分页、自动保存或聊天历史服务端持久化。对话历史仅存在于当前项目的页面内存中；切换项目或刷新页面会清空。引导完成标记只属于当前浏览器 profile；清除站点数据后会再次显示。AI 整理复用现有流式 Agent Chat，仍消费一次用户日配额；文本质量与首段延迟取决于服务器配置的真实模型。
