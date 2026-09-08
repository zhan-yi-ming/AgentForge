# Web 前端架构

- 状态：Accepted
- 所属阶段：V1 / Day 6
- 相关决策：ADR-0002、ADR-0003、ADR-0011、ADR-0014、ADR-0015

## 边界

`apps/web` 是独立的 React + TypeScript 应用，只依赖 Core API 的公开 REST/JSON 与 SSE 契约。它不访问 PostgreSQL、Agent Service 内部 API或 Java/Python源码，也不把按钮隐藏当作权限控制。所有项目权限、version、确认和写入规则继续由 Core API执行。

## 组件与状态

```text
App
├─ AuthGate              登录与当前标签页 token
└─ ProjectWorkspace
   ├─ ProjectPicker      项目加载与选择
   ├─ WikiWorkspace      Markdown 草稿、预览、保存
   ├─ TaskPanel          明确创建并确认的 Task 当前状态列表
   └─ AgentPanel         当前项目内存问答历史、来源、pending action、确认/拒绝
```

- 远端数据通过一个 typed API client 访问；它负责 Bearer header、Problem Details、request ID 与 401 会话失效。
- V1 使用 React 局部 state 和少量自定义 hook，不引入 Redux/Query 等状态框架；数据量小且请求关系清楚，避免 Day 6 提前承担缓存一致性复杂度。
- conversationId 在当前项目会话中复用；已完成问答保存在页面内存，最新一条默认展开、旧记录可逐条展开。切换项目时清空 Chat 历史/pending action，避免跨项目拼接上下文。
- V2-05 增加由 Core API 提供的持久化会话列表与详情。页面内存继续承载当前流式增量；选择历史时用服务端详情替换当前完成消息并进入聊天页。切换项目必须取消在途请求、清空旧详情并按新项目重新加载列表。
- Agent Chat 使用 `fetch` 读取 SSE 字节流；客户端用流式 `TextDecoder` 处理被任意拆分的 UTF-8 和 SSE frame，收到 `delta` 后立即增量渲染 Markdown。切换项目或组件卸载时终止旧请求，旧项目的迟到事件不得写入新项目状态。
- V2-06 对每个待决 action 在浏览器内存中生成一次不透明 Idempotency Key；网络失败保留 action 与 key，重试复用同一 key。`EXECUTED`/`REJECTED`/`FAILED` 终态清除 action 与 key，其中 `FAILED` 显示稳定错误且不以刷新 Task 暗示写入成功。该客户端机制只改善重试体验，最终幂等、权限和状态机仍由 Core API 与 PostgreSQL 保证。
- AI 整理的原始输入、流式返回 Markdown、完成状态和 Wiki 草稿是显式状态；整理复用公共 SSE Chat 接口但使用独立 AbortController、无 conversationId 的调用和独立展示状态，不改写项目 Chat 的 conversation、answer、sources 或 pending action。delta 到达后立即显示；未闭合的全文围栏先使用安全纯文本预览，complete 后再渲染 Markdown，避免流中整页黑色代码块。Chat 与整理互斥，切换项目或卸载组件时同时取消旧流，迟到事件不得写入新项目。
- 只有用户操作才能把完成的整理内容复制到草稿。应用时强制切换为新 Wiki 页面，清空旧页面 ID/version，并从 fenced code block 外的首个 H1 提取 title（缺失时使用默认值）；同一整理结果只能应用一次，只有保存按钮才能调用 create API 写入业务数据。整理入口忽略意外 tool proposal。

## V1.2 视觉与演示信息架构

- 登录页以简约项目标语作为第一屏引导，不展示个人署名或面试官问候。登录和介绍合并为居中单卡片；只提示账号是简历上的邮箱、密码是微信号，不在页面展示、内置或一键填入凭据，也不把凭据写入 URL、日志、`sessionStorage` 或 `localStorage`。
- 工作区采用顶部左侧无框 Logo、右侧独立的产品说明、版本状态和退出操作，配合左侧悬浮入口与居中单列内容；项目空间与执行任务通过抽屉展开，不占用固定侧栏。首页使用紧凑多行 Composer；打开 Wiki、Task 或整理工具时 Composer 默认收为单行，放大/缩小由发送按钮左侧的独立按钮控制且不阻断输入聚焦。只有发送消息后才进入独立会话布局，此时消息区域随页面外层滚动、Composer 固定在视口底部；项目、任务与三个工作台入口在会话内只打开悬浮窗，不改变会话模式，左上角返回按钮才回到首页。首页存在当前会话时提供重新进入入口，并在离开期间收到完整回答后显示未读角标。
- 首次登录后显示 DOM 可访问的模块化引导，模块说明使用可展开内容，关闭时只在 `localStorage` 保存完成标记；顶栏可重新打开，引导不改变项目、对话或业务状态。
- Wiki 新建可选择空白页面或进入 AI 整理；整理结果应用到草稿后自动回到 Wiki 工作台。Wiki 实时预览只在存在内容且用户打开后显示，以居中、可拖拽、可缩放、可关闭的悬浮窗呈现。
- 窄屏下悬浮入口与抽屉不得产生横向溢出；主内容仍为单列。
- 颜色以暖灰白、近黑和细线为主，状态颜色只用于优先级和错误反馈；所有主要文字、边框、焦点与按钮满足清晰对比，不以颜色作为唯一状态提示。顶栏为白色并使用 1px 底线，卡片采用 14px 圆角、控件采用 8px 圆角，阴影保持为轻量的 `0 1px 2px`。
- 流式回答期间展示明确的生成状态、增量正文和停止/禁用发送反馈；来源与待确认操作只在对应事件到达后展示，避免把未完成结果误认为最终答案。

## Markdown 与安全

Markdown 使用共用 React 节点渲染器，默认不解析原始 HTML，也不使用 `dangerouslySetInnerHTML`。渲染边界只剥离一个包住全文的 `markdown` / `md` 围栏，使历史 Wiki 与实时草稿保持一致，正文内部代码块不变。链接使用安全属性；后端文本、Wiki 内容和 Agent 回答均按不可信输入处理。V1 不支持用户自定义 HTML、脚本、iframe 或远程组件。

## 开发与部署

Vite 开发服务器把 `/api` 代理到 `http://127.0.0.1:8080`，浏览器保持同源请求，因此不扩大 Core API CORS。生产由同源 Nginx 提供静态资源并代理 JSON/SSE；Agent stream 路径关闭代理缓冲，避免 token 被聚合后一次性到达浏览器。
