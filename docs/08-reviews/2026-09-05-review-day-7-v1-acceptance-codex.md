# Day 7 / V1 最终 Codex 审核报告

- 日期：2026-09-05
- 审核对象：Day 7 文档、Compose、三应用容器构建、本地配置、演示数据和验收脚本
- 审核者：Codex
- 结论：PASS

## 审核结论

未发现会导致代码不可运行、跨层边界冲突、权限旁路、数据错误或偏离 V1 路线的重大问题。Day 7 没有提前引入 V2/V3 组件；浏览器继续只访问 Core API，Python 只生成回答或结构化意图，最终业务写入仍由 Java 校验和执行。

本阶段按用户决定不调用 Pi。Codex 除阅读完整差异外，亲自执行 Java、Python、Web、Compose、HTTP 验收与真实浏览器操作。

## 真实浏览器审核

- 使用演示账号登录，Project、Wiki 与 Task 正确加载。
- 从页面向 Agent 提交 create-task 意图，页面显示来源与待确认动作。
- 点击确认后 Task 数量从 1 刷新为 2，新任务内容和优先级正确。
- 再提交一个动作并拒绝，待确认卡片消失，Task 数量保持 2。
- AI 文本整理先返回 Markdown 预览；显式应用仅修改当前浏览器 Wiki 草稿。新开页面重新登录后，服务端 Wiki 仍为原始内容，证明不存在自动写回。
- 窄视口页面可操作，未观察到横向溢出、按钮遮挡或不可达控件。

## 测试与安全证据

- Java `clean verify`：75 项，0 失败、0 错误、6 跳过；真实 Compose 验收覆盖被跳过的外部 Agent 契约链路。
- Python pytest：17 项通过，0 失败、0 跳过；含真实 pgvector Testcontainers。
- Web Vitest：10 项通过；production build 成功。
- V1 HTTP 验收：Web 200、Core/Agent UP、RAG 来源、confirm/reject 和最终 Task 计数全部通过。
- 配置生成负向检查：已有文件被拒绝覆盖且内容哈希不变；JWT 解码 32 字节；两个内部 token 相互独立；生成文件被 Git 忽略。
- 本机未安装 Gitleaks；使用与仓库 review gate 相同的 6 类敏感模式扫描 24,348 个 diff 字符，0 命中。推送后的 GitHub Actions Gitleaks 仍是远端最终门禁。
- `git diff --check` 和 `docker compose config --quiet` 均以退出码 0 完成。

## 接受的非阻断限制

- 默认 hash Embedding 和 deterministic responder 用于无外部 key 的可重复演示；真实生成式模型不属于 V1。
- Java 的 6 个契约测试需要显式外部 Agent URL，因此在普通 `clean verify` 中跳过；本次真实容器验收已验证同一跨进程链路。
- Python 测试有 2 条第三方弃用 warning；不影响当前运行和架构边界，留待依赖升级时集中处理。
- 容器配置面向本地开发，不等同于生产安全部署。
