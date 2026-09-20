# P3-07 Wiki 链接图谱

- 状态：Implemented
- 日期：2026-09-20
- 基线：`fcaa02217d00c5d2228b1e5eb41eb3f76624106a`；目标远端 `origin/codex/pre-v3-chat-experience`
- 用户已有改动：旧审计记录、`.worktrees/`、产品规划 docx 保留；受 ACL 保护的 pytest 目录不处理。
- 用户授权：继续完成 P3-07；保留完整 V2 和线上登录页视觉。

## 范围与验收

新增独立 `/wiki/graph` 路由与按需加载的可视化页面，从当前项目已获取的 Wiki 内容解析明确的 `[[页面标题]]`、`[[页面标题|别名]]` 以及指向 Wiki ID 的 Markdown 链接。仅当目标页面在当前项目列表中存在时画出连线；未解析目标不制造节点或关系。页面提供搜索、选中详情、缩放、返回 Wiki 与打开页面。保留 V2 登录视觉和编辑流程。

不新增图数据库、语义抽取或 GraphRAG；不提前实现 V3-04/05/07。页面只读取现有 Wiki 列表，不改业务写入权限和 API。

## 测试公共 seam

`parseRoute` 的公开路径解析，以及用户从 Wiki 工作台进入图谱、查看显式链接、返回和打开页面的可见行为。先记录红灯，再最小实现。影响域 Web；运行门禁规划器、Web 测试与生产构建、视觉核对；Node Milestone Pi 只读审核。

## 验证与收尾

- 规划器：L1 / Docs + Web；Node 协议额外要求 Pi Milestone Review。
- TDD：`/wiki/graph` 先返回首页（红灯），图谱页面导入不存在（红灯）；实现后两个公共 seam 测试通过。
- `cd apps/web; npm test -- --run`：Vitest 3.2.7，6 文件 / 67 passed / 0 failed；新增直达路由回归后重跑完整套件；既有 V2 登录、聊天、Wiki、审批与语音测试均通过。npm 提示既有 `home` 配置警告。
- `cd apps/web; npm run build`：TypeScript 和 Vite 生产构建通过；`WikiGraphPage` 独立 JS/CSS chunk。最初 ref 类型报错已修复；画布拖动命中修复后待最终重跑。
- 隔离预览服务在 5174 启动并截图，临时入口已删除。随后 `docker compose --env-file .env -f infra/compose.yaml build` 一次成功构建 Web/Core/Agent；`up -d core-api agent-service web` 后四容器健康。`http://127.0.0.1:5173/` 的 HTML 与本次 Web dist 一致，`/wiki/graph` HTTP 200 且 SPA fallback 正常。
- Pi Milestone Attempt 1：PASS，无阻塞项；S-01 直达路由测试补齐并通过；其它低风险建议在报告中逐项判断。
- Node Close Gate：P3-07 范围完成；独立路由、显式来源关系、浏览/跳转、项目隔离已验证。未实现 V3 图模型、自动抽取、GraphRAG。只改 Web 与文档，README 无需变化。测试与审核通过，敏感扫描 0 命中；Gate YES。提交和远端核验待完成；阿里云 ASR Key/Workspace 仍未配置，P3-05 真实语音验收需用户本地填写后执行。
