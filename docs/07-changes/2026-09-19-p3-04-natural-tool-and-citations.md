# P3-04 自然语言 Action 与真实引用

- 状态：Implemented
- 日期：2026-09-19
- 基线：`03261b3be35d8282d1827fe87b8b855b7da75e70`；目标远端 `origin/codex/pre-v3-chat-experience`
- 用户已有改动：`docs/07-changes/2026-09-05-disable-pi-and-day1-day4-audit.md`、`.worktrees/`、产品规划 docx 保持原状

## 现状与可复现根因

Python `plan_tool` 只匹配少数固定中文句式及英文命令；普通自然语言创建任务没有 Tool proposal，Web 因此无法收到 Java 持久化后的 pendingAction。引用来源来自 RAG 排名前列的检索候选，模型生成的回答不一定使用它们，底部列表会错配。Agent Service 的 DeepSeek 默认模型仍为旧兼容别名 `deepseek-v4-flash`；官方当前 API 名为 `deepseek-flash`。

## 设计边界

在启用真实 LLM 时，由 Python 在现有 retrieve→plan 步骤从用户请求和已授权、已检索的 Task 标识与版本中生成受严格 schema 校验的 Action Intent。无动作、目标不唯一、缺版本或无效字段时不产生提案；显式命令和无模型测试模式保留确定性 planner。LLM 只给意图，Java 继续独占项目权限、Risk Policy、审批、版本和写入。Python 不接受模型给出的 actor/project/risk/approval 元数据。

架构决定见 ADR-0026。

RAG 上下文为可引用来源编号，生成回答用 `【来源1】` 等专用标记标注实际用到的证据。Python 只把出现在完成回答中的有效编号映射为结构化 sources；没有有效编号就返回空数组，不把候选冒充引用。同步响应按完成回答筛选；流式 metadata 只给会话标识，最终 `complete` 再给准确 sources，Java SSE complete 与 Web client 同步更新。历史持久化使用最终 sources。模型未遵守引用格式时可见回答仍保留，但来源列表为空，不虚构依据。

将 Agent Service 的 DeepSeek 默认 API 模型名改为 `deepseek-flash`，自定义显式 `llm_model` 保持覆盖能力。官方依据：DeepSeek 2026-09-10 更新日志和 API 首次调用文档。

DeepSeek 的规划调用使用官方 Chat Completions JSON Output（`response_format=json_object`）；其它已配置 provider 仍按严格 JSON 解析和白名单验证。模型偶发空内容或无效 JSON 时不产生提案，不绕过 Java 边界。

## 验证

公共 seam 为 Python 内部 Chat JSON/NDJSON、Java SSE/同步 Chat、Web typed client 与 DOM。按 TDD 对自然语句卡片、歧义更新、来源错配、流式最终来源和模型默认名先跑红灯，再逐片修复。跨服务契约变化按 L3 做 Python pytest、Java clean verify、Web test/build、必要跨进程 smoke、敏感扫描和 Pi Milestone Review。

### 本机结果

- 风险规划：L3，影响 Agent Service、Core API、Web 与跨服务契约。
- TDD：自然语言 Action、无效模型字段、最终引用来源、流式传递及上下文截断均先见红灯，再修复见绿灯。
- Python：完整 `pytest -q -p no:cacheprovider --basetemp .pytest-p304-final` 为 111 passed、3 条第三方弃用 warning；第一次普通权限运行有 3 项 Docker 管道访问失败，提权重跑消除；复用旧临时目录又有 5 项目录 ACL 错误，改用新专用目录后全部通过。
- Java：`mvnw.cmd -q clean verify` 通过，120 tests、8 项既有外部服务测试 skip，Testcontainers PostgreSQL 运行成功；保留 Mockito 动态 agent warning。
- Web：`npm test -- --run --reporter=dot` 53 passed；`npm run build` 通过；保留 npm 用户配置 warning。
- 跨进程：Day4 RAG smoke 与 Day5 Action smoke 均通过；隔离 Compose 项目及容器资源已清理。首次 Day4 启动前因脚本需要预设测试 JWT 与内部 token 而失败，使用脚本已有的测试常量重跑通过。
- DeepSeek 线上密钥未读取，未执行真实线上模型调用；JSON Output 调用形式与模型名据官方文档核对，模型响应由本地 fake 测试覆盖。
- `.pytest-p304-final` 已清理；先前失败运行生成的 `.pytest-p304` 目录 ACL 拒绝当前用户及提权工具删除，待环境权限恢复后清理，未纳入变更。
- 暂存差异 `git diff --cached --check` 无错误，高置信敏感扫描 0 命中。Pi `pre-v3-natural-tool-citations` Attempt 1 Milestone Review PASS，无阻断项；建议项已在审核报告逐条复核。提交与远端核验待执行。
