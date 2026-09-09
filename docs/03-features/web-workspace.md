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
5. 响应包含 pending action 时，Web 展示 action 类型、Task、字段和预期 version。确认/拒绝只调用 Java action API，并为该 action 生成稳定 Idempotency Key；网络失败重试复用该 key。只有 `EXECUTED` 成功才刷新 Task，`FAILED` 显示稳定失败信息。
6. “AI 文本整理”保留原始输入，通过独立、无 conversationId 的 SSE 请求在 complete 前真实展示已到达的 delta。未闭合的全文 Markdown 围栏以流式安全文本展示，避免整个文档变成深色代码块；complete 后再交给共用 Markdown 渲染器。整理与项目 Chat 互斥，结果不改变 Chat 状态或接受 tool proposal。
7. 点击“应用到 Wiki 草稿”总是进入新的未保存页面：清空旧页面 ID/version，以 fenced code block 外的第一个 H1 作为 title，无真实 H1 时使用默认标题，并滚动到 Wiki 编辑区。原页面保持不变；本次结果应用后按钮禁用，防止保存后重复应用造成重复页面；用户再次点击保存时调用 create API。

## 状态与错误

- loading、empty、success、error 均有可见状态；Chat 和整理按钮在任一生成请求期间互斥禁用，避免重复提交和 pending action 竞态。
- 登录提交失败统一展示“请联系我 向我索要体验账号”，避免泄露账号存在性或后台细节；已登录请求返回 401 时清除当前会话并返回登录，其他 403/404/409/503 展示 Problem Details 的安全 detail 和 requestId。
- 整理流在 complete 前发生非取消错误时清除半成品，避免失败内容仍可应用到 Wiki；项目切换等主动取消不显示错误。
- 整理流尚未 complete 时禁止应用；应用完成结果会解除当前旧 Wiki 选择，防止后续保存覆盖旧页面。
- Wiki 409 不自动覆盖；提示用户刷新后重新合并。
- Wiki 保存成功显示明确反馈，并以服务端返回和刷新列表中的最新页面、version 更新当前草稿；失败时只展示错误，不显示成功。
- 保存 Wiki 不会创建 Task；“执行任务”只展示 Task API 数据，并在界面明确说明只有 Agent 提出且用户确认的任务操作才会改变这里。用户应以 Wiki 编辑区的保存状态和版本号判断保存结果。
- confirm/reject 达到终态后清除 pending action 与本地 key；confirm 返回 `EXECUTED` 才刷新 Task，返回 `FAILED` 时显示失败且不误刷新；网络失败保留两者供安全重试。
- 切换项目取消 Chat 与整理流，并清空项目相关草稿、conversation 和 pending action；旧项目迟到事件不可写入当前视图。
- V2-05 左侧历史入口按当前项目加载认证用户自己的持久化会话。选择历史后进入聊天内容页、恢复已完成消息并复用 conversationId；切换项目会丢弃已加载详情并重新查询，前端不提交 userId 或权限 Metadata。

## 测试边界

测试通过 DOM 与网络 client 的公共接口观察行为，不断言私有 state。至少覆盖：登录页不出现凭据明文、只显示简历邮箱与微信号提示、任一凭据错误显示统一联系文案、首次引导关闭/持久化/重新打开、登录后 Chat 是主内容首个功能、项目加载、Markdown 原始 HTML 不成为 DOM、完整外层 Markdown 围栏被移除但正文内部代码块保留、项目对话最新展开且旧记录可展开、执行任务语义说明、AI 整理在 stream promise 完成前展示真实 delta、未闭合 fence 不产生整页代码块、与 Chat 互斥、项目切换取消旧流、失败半成品不可应用、意外 proposal 不污染 Chat、应用后形成带标题的新 Wiki 草稿并在保存时调用 create API、滚动定位与反馈、Wiki 保存成功反馈、Chat 展示 pending action、确认请求携带稳定 key、网络重试复用 key、`EXECUTED` 后刷新 Task、`FAILED` 不误刷新、拒绝不写，以及 Problem Details 可见。

## 已知限制

当前没有 refresh token、多标签引导同步、复杂路由、分页、自动保存或历史会话删除/搜索。当前页面内存中的对话视图在切换项目或刷新时清空，但已完成问答已持久化到 Core API/PostgreSQL，可在左侧“历史”抽屉中按当前 Project/User 作用域恢复并继续使用原 conversationId；恢复逻辑按角色顺序识别 USER 与 ASSISTANT，不因遗留的不完整消息序列漏掉后续完整回答。引导完成标记只属于当前浏览器 profile；清除站点数据后会再次显示。AI 整理复用现有流式 Agent Chat，仍消费一次用户日配额；文本质量与首段延迟取决于服务器配置的真实模型。

公网 V2 Demo 使用固定顶栏和独立返回按钮安全区，品牌图标不使用字母缩写，浏览器 favicon 与页面品牌位保持一致。版本状态显示 `V2 Live Demo`。生产种子脚本会为每个 Demo 用户分别创建或补齐同名体验项目中的 Wiki 与 Task；这些数据是用户隔离的副本，A 用户删除自己的条目不会删除 B 用户的对应条目。
