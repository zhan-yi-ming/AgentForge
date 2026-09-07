# 工作区预览、对话历史与 Agent 瞬时故障热修

## 状态

In Progress

## 背景与证据

- 已保存的 Wiki 可能仍带一个完整的外层 `markdown` 代码围栏。此前只在“AI 文本整理”返回时移除围栏，Wiki 实时预览仍会把整篇内容显示成深色代码块。
- “任务脉搏”实际只展示 Task API 数据。普通项目问答不会创建业务数据；Wiki 保存只更新 Wiki，也不会新增 Task，现有命名和空状态容易让用户误以为所有操作都应在这里留下记录。
- 项目对话只保留屏幕上的最后一次回答，无法回看当前项目会话里的先前问答。
- 生产 Agent Service 日志出现过 `POST /internal/v1/chat` 503。随后对同一生产配置完成 provider `/models`、真实 responder 及 Core → RAG → Agent → provider 全链路探测，均成功，说明当前配置可用，已报告的失败更符合瞬时上游或网络故障。

## 目标与范围

- 在共用 Markdown 渲染边界移除且仅移除一个完整外层 `markdown` / `md` 围栏，使 Wiki、Agent 回答和整理结果使用同一安全渲染行为；正文内部代码块保持不变。
- 将“任务脉搏”改为语义明确的“执行任务”，并明确说明只有 Agent 提出、用户确认的任务操作才会改变这里；普通问答与 Wiki 保存不会新增 Task。不改变 Task API 或数据模型。
- 在当前项目的浏览器内存会话中保留项目问答历史：最新一条默认展开，旧记录默认收起且可逐条展开；切换项目时清空，不做服务端持久化。
- 为兼容模型客户端配置一次有限重试，降低瞬时 provider / 网络失败直接变成 503 的概率；持续故障仍返回脱敏 503，不改变配额、HITL 或 API 契约。
- 补充生产日志查看方式。

## 验证计划

- Web：先增加失败测试，再定向运行 Markdown 预览、项目对话历史、任务说明和既有 Wiki/Chat 交互测试；运行生产构建。
- Agent Service：先增加模型客户端重试参数失败测试，再定向运行 `test_llm.py`。
- 生产：只重建 Web 与 Agent Service，检查容器健康、HTTPS、线上静态文案与一次真实 Core → Agent Chat smoke。
- 完成真实测试与敏感信息扫描后，按 L2 执行一次 Pi Diff Review。

不运行 Java、Python、Web 或 E2E 全量套件；本次接口和数据库契约不变，验证集中在实际修改模块。

## 本地验证结果

- TDD 红灯：Web 定向测试退出码 1，20 个测试中新增的 3 个行为测试失败、既有 17 个通过，分别捕获外层围栏仍为代码块、执行任务说明缺失和旧问答不可展开。
- `npm test -- --run tests/markdown-preview.test.tsx tests/app.test.tsx`：退出码 0；Vitest 3.2.7，2 个文件、20 个测试通过，0 失败、0 跳过。
- `npm run build`：退出码 0；Vite 7.3.6，TypeScript 构建通过，283 个模块完成转换。
- 本机系统 Python 缺少项目依赖，首次 `pytest` 在 collection 阶段因缺少 `psycopg` 退出 2，未作为行为测试结论。随后复用项目 Python 3.14.7 Agent 镜像并在一次性容器中临时安装 pytest 8.4.2，`python -m pytest tests/test_llm.py -q` 退出码 0，12 个测试通过，0 失败、0 跳过；容器结束后已自动删除。
- Node.js 24.14.0、npm 11.9.0；定向敏感信息扫描未发现 JWT、私钥或常见 API key 形式，`git diff --check` 退出码 0。
- Pi `deepseek/deepseek-v4-pro` Diff Review Attempt 1：`PASS`。无阻塞缺陷；并发流守卫与 pending action 历史化属于后续独立交互设计，围栏尾随空格已由匹配前 `trim()` 覆盖，因此不扩大本次热修。

## 风险与回滚

风险等级预估为 L2：Web 会话显示涉及多个交互状态，模型客户端重试涉及外部调用。有限重试只在首次调用失败时增加一次上游请求，可能略微增加故障时延与失败请求成本。

回滚本次提交并只重建 Web 与 Agent Service；Core API、数据库、Wiki 和 Task 数据无需迁移或回滚。
