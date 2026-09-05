# V1 接入 DeepSeek、智谱与通义千问

- 日期：2026-09-05
- 状态：Accepted
- 阶段：V1 体验增强
- 交付目标：当前分支本地提交

## 背景

V1 已跑通 Web、Java、Python Agent、RAG 与人工确认闭环，但 LangGraph 的 `respond` 节点仍使用确定性模板，用户无法体验真实生成式回答。项目需要接入 DeepSeek、智谱和通义千问，且不得把请求发送到 OpenAI，也不得把任何真实 API key 提交到 GitHub。

## 目标

- 在现有 LangGraph `respond` 节点通过 LangChain 的 OpenAI-compatible 客户端调用三家模型。
- 通过 `AGENTFORGE_AGENT_LLM_PROVIDER=deepseek|zhipu|qwen` 切换厂商；每次只要求所选厂商的本地 key。
- 将检索上下文与用户问题交给模型生成中文优先、带来源约束的回答；现有确定性 Tool proposal 与 Java 确认写回保持不变。
- 默认 `disabled` 模式仍可无外部 key 启动和执行自动化测试；本地体验时显式选择 provider。
- key 只保存在被 Git 忽略的 `.env`，健康检查、错误响应与日志均不回显 key。

## 非目标

- 不接入 OpenAI 服务或 OpenAI 模型，不使用 OpenAI 的默认 API 地址。
- 不把 Embedding 改为收费外部服务；RAG 继续默认使用本地 hash Embedding。
- 不实现多模型自动路由、故障转移、流式输出、对话持久化或让模型直接执行 Tool。

## 受影响文档

- `docs/02-architecture/decisions/ADR-0012-compatible-llm-provider-adapter.md`：记录兼容协议、配置与信任边界。
- `docs/03-features/agent-chat.md`：把 responder 的目标行为更新为可配置真实模型。
- `docs/04-api/agent-service.md`：记录 provider 故障的 503 行为。
- `docs/05-development/local-development.md`：记录三家配置、切换和本地启动步骤。
- `README.md`：更新快速开始与密钥提示。

## 设计决定

见 ADR-0012。LangGraph 继续负责 `prepare -> retrieve -> plan -> respond`；LangChain `ChatOpenAI` 仅作为兼容协议客户端，base URL 由受限 provider 映射确定。用户可覆盖模型名和 base URL 以适应账号地域或模型版本，但 provider 不允许配置为 OpenAI。

## 实现

已新增 `agentforge_agent/llm.py` 兼容 LLM adapter，并把它作为 responder 注入现有 LangGraph。`Settings`、`.env.example` 和 Compose 新增 LLM provider/key/base URL/model 配置；运行时 provider 只允许 `disabled|deepseek|zhipu|qwen`，并显式拒绝 `openai.com` 覆盖地址。原可选 OpenAI Embedding 实现与配置已移除，RAG 固定使用本地 hash Embedding。

FastAPI 内部 Chat 在模型失败时返回脱敏 503。测试覆盖 responder 注入、prompt 中的问题/上下文、三家默认映射、缺 key、OpenAI 地址拒绝、空文本、异常脱敏和 HTTP 503。`.gitignore` 现在忽略所有 `.env.*`，仅放行 `.env.example`。

## 验证结果

- TDD 红灯：隔离环境建立后执行 `python -m pytest tests/test_llm.py tests/test_api.py -q`，因 `LlmDependencyError` 与 `get_responder` 尚不存在而收集失败（exit 1）；OpenAI base URL 拒绝测试在保护实现前单独失败（exit 1）。
- Python：Python 3.14.3、pytest 8.4.2；最终 `.venv\Scripts\python.exe -m pytest -q`（exit 0），29 passed、0 failed、0 skipped，真实 `pgvector/pgvector:pg17` Testcontainer 已运行并清理。2 条第三方弃用警告；另有 1 条 Windows ACL 导致 pytest cache 不可写警告，不影响测试，缓存目录被 Git 忽略。
- provider 初始化：`build_responder` 使用真实 `ChatOpenAI` 分别初始化 DeepSeek/智谱/千问（不发网络请求），输出 `3 providers initialized`（exit 0）。未使用真实厂商 key，因此未产生收费在线调用；需用户填入本地 key 后人工体验。
- Java：OpenJDK 21.0.12.1、Maven 3.9.11；`mvnw.cmd clean verify`（exit 0），75 tests、0 failures、0 errors、6 skipped。跳过的是需要外部 uvicorn 进程的既有 Java 契约类，随后由真实 Compose 验收覆盖 Java↔Python HTTP 链路。
- Web：Node v24.14.0、npm 11.9.0；`npm test -- --run`（exit 0），3 files / 10 tests 全通过；`npm run build`（exit 0），283 modules transformed。
- 容器：Docker Desktop Client/Server 29.5.3；`docker compose ... config --quiet` 与 Agent Service 镜像构建均 exit 0。独立端口完整栈四个服务全部 healthy，`v1-acceptance.ps1` exit 0 / PASS：Web 200、Core/Agent UP、RAG source=1、确认写回成功、拒绝不写入、最终 task=2。
- 安全：本机未安装 Gitleaks；使用仓库既有高信噪比模式扩展扫描私钥、GitHub/AWS token、Bearer/JWT 与常见 API key，未发现真实秘密。`git check-ignore -v` 确认 `.env.local` 与 `.env.deepseek` 被忽略，`.env.example` 仍可跟踪；运行时代码和默认配置均不含 OpenAI 服务地址，测试仅保留一个被拒绝地址的负向样例。
- 清理：独立验收 Compose 的 4 个容器、network、PostgreSQL named volume 与临时 `.env.codex-llm-validation-20260905` 已删除，`docker ps` 对该 project 无残留。旧 ACL 异常 `.pytest_cache` 无法删除，已忽略且不包含业务数据或密钥。

## 风险与回滚

外部厂商的模型名、区域地址、配额或兼容细节可能变化；运行时会把失败归一为不含凭据的 503。当前不保存多轮消息，`conversationId` 仍仅用于关联；模型只看到本次问题和本次受限 RAG context。回滚时将 `AGENTFORGE_AGENT_LLM_PROVIDER=disabled` 即可恢复确定性回答，无需迁移数据库；代码级回滚可还原本次提交。
