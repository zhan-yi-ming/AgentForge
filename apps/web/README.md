# AgentForge Web

- 状态：Implemented
- 阶段：V1 / Day 6
- 技术：React + TypeScript + Vite

该应用承载登录、Project、Wiki、Task、AI Chat、Markdown 安全预览和人工确认交互。Web 只调用 Core API，不直接访问数据库或 Agent Service 内部接口，也不把前端状态当作权限保障。

开发命令：

```powershell
npm install
npm run dev
npm test -- --run
npm run build
```

开发服务器默认把 `/api` 代理到 `http://127.0.0.1:8080`。先启动 Core API 和 Agent Service，再访问 Vite 输出的本地地址。
