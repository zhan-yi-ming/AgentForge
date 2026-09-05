# Web 前端架构

- 状态：Accepted
- 所属阶段：V1 / Day 6
- 相关决策：ADR-0002、ADR-0003、ADR-0011

## 边界

`apps/web` 是独立的 React + TypeScript 应用，只依赖 Core API 的公开 REST/JSON 契约。它不访问 PostgreSQL、Agent Service 内部 API或 Java/Python源码，也不把按钮隐藏当作权限控制。所有项目权限、version、确认和写入规则继续由 Core API执行。

## 组件与状态

```text
App
├─ AuthGate              登录与当前标签页 token
└─ ProjectWorkspace
   ├─ ProjectPicker      项目加载与选择
   ├─ WikiWorkspace      Markdown 草稿、预览、保存
   ├─ TaskPanel          Task 当前状态列表
   └─ AgentPanel         Chat、来源、pending action、确认/拒绝
```

- 远端数据通过一个 typed API client 访问；它负责 Bearer header、Problem Details、request ID 与 401 会话失效。
- V1 使用 React 局部 state 和少量自定义 hook，不引入 Redux/Query 等状态框架；数据量小且请求关系清楚，避免 Day 6 提前承担缓存一致性复杂度。
- conversationId 在当前项目会话中复用；切换项目时清空 Chat/pending action，避免跨项目拼接上下文。
- AI 整理的原始输入、返回 Markdown 和 Wiki 草稿是三个显式状态；只有用户操作才能把返回内容复制到草稿，只有保存按钮才能写入业务数据。

## Markdown 与安全

Markdown 使用 React 节点渲染器，默认不解析原始 HTML，也不使用 `dangerouslySetInnerHTML`。链接使用安全属性；后端文本、Wiki 内容和 Agent 回答均按不可信输入处理。V1 不支持用户自定义 HTML、脚本、iframe 或远程组件。

## 开发与部署

Vite 开发服务器把 `/api` 代理到 `http://127.0.0.1:8080`，浏览器保持同源请求，因此 Day 6 不扩大 Core API CORS。生产静态资源服务、容器和跨服务健康检查由 Day 7 决定。
