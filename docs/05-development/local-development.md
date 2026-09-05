# Day 1–Day 6 本地启动与体验教程

- 状态：Implemented
- 适用系统：Windows PowerShell
- 当前可运行应用：React Web、Core API、Agent Service
- HTTP 命令可精确观察接口，React Web 可完成主要演示流程

## 1. 今天完成了什么

启动后可以实际体验以下链路：

1. Day 1：PostgreSQL、用户和项目基础数据。
2. Day 2：注册、登录、JWT、项目权限、Wiki 与 Task CRUD。
3. Day 3：Java Core API 完成鉴权和项目授权后，通过真实 HTTP/1.1 调用 Python FastAPI + LangGraph Chat。
4. Day 4：Chat 从当前项目的 Wiki/Task 构建 Chunk，以 Embedding + BM25 + RRF 检索，并返回来源。
5. Day 5：Chat 返回 create/update task 待确认预览；confirm 后才由 Java 写入，reject 不写入。
6. Day 6：React 工作区组合登录、Project、Wiki、Task、Chat、Markdown 预览和人工确认，AI 文本只有显式应用并保存后才写入 Wiki。

Day 4 仍使用 deterministic responder，不调用生成式 LLM；回答会展示检索到的项目片段与结构化来源。默认 hash Embedding 不需要外部密钥，便于完整体验混合检索链路。

## 2. 前置条件

在新的 PowerShell 中逐项确认：

```powershell
git --version
java -version
python --version
docker version
docker compose version
```

要求：

- Java 必须是 21。
- Python 必须是 3.12、3.13 或 3.14。
- Docker Desktop 已启动，`docker version` 同时显示 Client 和 Server。
- 不需要全局安装 Maven；仓库自带 Maven Wrapper。

## 3. 创建仅供本机使用的环境文件

打开第一个 PowerShell，进入仓库根目录。若你的路径不同，只修改第一行：

```powershell
Set-Location 'C:\Users\86134\Documents\ChatGPT\AgentForge'
Copy-Item .env.example .env
$random = [Security.Cryptography.RandomNumberGenerator]::Create()
$jwtBytes = New-Object byte[] 32
$tokenBytes = New-Object byte[] 32
$coreTokenBytes = New-Object byte[] 32
$random.GetBytes($jwtBytes)
$random.GetBytes($tokenBytes)
$random.GetBytes($coreTokenBytes)
$random.Dispose()
$jwtSecret = [Convert]::ToBase64String($jwtBytes)
$internalToken = -join ($tokenBytes | ForEach-Object { $_.ToString('x2') })
$coreInternalToken = -join ($coreTokenBytes | ForEach-Object { $_.ToString('x2') })
$content = Get-Content .env -Raw
$content = $content.Replace('REPLACE_WITH_BASE64_32_BYTE_RANDOM_VALUE', $jwtSecret)
$content = $content.Replace('REPLACE_WITH_RANDOM_INTERNAL_TOKEN', $internalToken)
$content = $content.Replace('REPLACE_WITH_RANDOM_CORE_INTERNAL_TOKEN', $coreInternalToken)
Set-Content .env $content -NoNewline
```

`.env` 已被 Git 忽略，禁止提交或把其中的随机值粘贴到 Issue、日志和聊天中。以后已有 `.env` 时不要重复覆盖，除非你明确希望更换本地密钥。

从 Day 3 升级的已有 `.env` 需要参照 `.env.example` 手动补入 Day 4 变量，并单独生成 `AGENTFORGE_CORE_INTERNAL_TOKEN`；不要把它复制成已有的 Agent token。上面的新建脚本会同时生成两个不同的内部 token。

## 4. 启动 PostgreSQL

Redis 是后续阶段预留组件，Day 1–Day 4 只需要带 pgvector 的 PostgreSQL：

```powershell
docker compose --env-file .env -f infra/compose.yaml up -d postgres
docker compose --env-file .env -f infra/compose.yaml ps
```

等待 `postgres` 显示 `healthy`。首次启动需要拉取 `pgvector/pgvector:pg17`，会比后续启动慢。

## 5. 启动 Python Agent Service

打开第二个 PowerShell：

```powershell
Set-Location 'C:\Users\86134\Documents\ChatGPT\AgentForge'
Get-Content .env | Where-Object { $_ -match '^[A-Za-z_][A-Za-z0-9_]*=' } | ForEach-Object {
    $name, $value = $_ -split '=', 2
    [Environment]::SetEnvironmentVariable($name, $value, 'Process')
}
Set-Location services\agent-service
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -e ".[test]"
uvicorn agentforge_agent.main:app --reload --host 127.0.0.1 --port 8000
```

Day 4 需要以下新增环境值，它们由 `.env.example` 提供：

- `AGENTFORGE_AGENT_CORE_API_URL`：Python 回调 Core API 的地址。
- `AGENTFORGE_CORE_INTERNAL_TOKEN`：Python→Java 专用 token，必须与 Java 进程一致，且不能与 `AGENTFORGE_AGENT_INTERNAL_TOKEN` 共用。
- `AGENTFORGE_AGENT_RAG_DB_DSN`：Python 只用于 `rag_chunk` 派生索引的 PostgreSQL DSN。
- `AGENTFORGE_AGENT_EMBEDDING_PROVIDER=hash`：默认无密钥模式。切换为 `openai` 时另行在本机设置 `AGENTFORGE_AGENT_OPENAI_API_KEY`，不得写入 `.env.example` 或 Git。

看到 uvicorn 正在监听后不要关闭窗口。若 PowerShell 禁止激活脚本，可以不激活，改用：

```powershell
.\.venv\Scripts\python.exe -m pip install -e ".[test]"
.\.venv\Scripts\python.exe -m uvicorn agentforge_agent.main:app --reload --host 127.0.0.1 --port 8000
```

## 6. 启动 Java Core API

打开第三个 PowerShell：

```powershell
Set-Location 'C:\Users\86134\Documents\ChatGPT\AgentForge'
Get-Content .env | Where-Object { $_ -match '^[A-Za-z_][A-Za-z0-9_]*=' } | ForEach-Object {
    $name, $value = $_ -split '=', 2
    [Environment]::SetEnvironmentVariable($name, $value, 'Process')
}
Set-Location services\core-api
.\mvnw.cmd spring-boot:run
```

首次运行 Maven 会下载依赖。日志出现应用启动成功后保留此窗口。

## 7. 检查两个服务

打开第四个 PowerShell：

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
Invoke-RestMethod http://localhost:8000/health
```

两个请求都应返回包含 `UP` 或健康状态的信息。

## 7A. 启动 React Web

打开第五个 PowerShell：

```powershell
Set-Location 'C:\Users\86134\Documents\ChatGPT\AgentForge\apps\web'
npm install
npm run dev
```

Vite 默认监听 `http://127.0.0.1:5173`，并把浏览器发往 `/api` 的请求代理到 `http://127.0.0.1:8080`。在浏览器打开 Vite 输出的地址后，可以登录、选择项目、编辑并安全预览 Wiki、查看 Task、调用 Chat，以及确认或拒绝 Agent 提议。若账号下没有项目，先按第 10 节 HTTP 命令创建一个项目，再刷新页面。

AI 文本整理会先保留原文并展示 Agent 返回的 Markdown 预览；点击“应用到 Wiki 草稿”只修改浏览器草稿，仍需点击保存才会调用 Wiki API。当前 deterministic responder 的整理质量有限，真实生成式模型不属于 Day 6。

## 8. 注册并取得 JWT

以下命令会在本地数据库创建演示用户。邮箱重复时改一个邮箱，或直接使用第 9 节登录：

```powershell
$core = 'http://localhost:8080'
$registerBody = @{
    email = 'demo@agentforge.local'
    displayName = 'AgentForge Demo'
    password = 'demo-password-123'
} | ConvertTo-Json
$auth = Invoke-RestMethod -Method Post -Uri "$core/api/v1/auth/register" -ContentType 'application/json' -Body $registerBody
$token = $auth.accessToken
$headers = @{ Authorization = "Bearer $token" }
$auth.user
```

JWT 只保存在当前 PowerShell 变量中，不要打印或提交真实 token。

## 9. 已注册时登录

若注册返回 409，使用同一账号登录：

```powershell
$loginBody = @{
    email = 'demo@agentforge.local'
    password = 'demo-password-123'
} | ConvertTo-Json
$auth = Invoke-RestMethod -Method Post -Uri "$core/api/v1/auth/login" -ContentType 'application/json' -Body $loginBody
$token = $auth.accessToken
$headers = @{ Authorization = "Bearer $token" }
```

## 10. 创建项目、Wiki 和 Task

创建项目并保存项目 ID：

```powershell
$projectBody = @{
    name = "Local Demo $(Get-Date -Format 'HHmmss')"
    description = 'Day 1 to Day 4 local demonstration'
} | ConvertTo-Json
$project = Invoke-RestMethod -Method Post -Uri "$core/api/v1/projects" -Headers $headers -ContentType 'application/json' -Body $projectBody
$projectId = $project.id
$project
```

创建 Wiki：

```powershell
$wikiBody = @{
    title = 'Architecture'
    content = "# AgentForge`n`nJava owns authentication and writes. Python owns agent reasoning."
} | ConvertTo-Json
$wiki = Invoke-RestMethod -Method Post -Uri "$core/api/v1/projects/$projectId/wiki-pages" -Headers $headers -ContentType 'application/json' -Body $wikiBody
$wiki
```

创建 Task：

```powershell
$taskBody = @{
    title = 'Verify local chat'
    description = 'Run the Day 3 Java to Python request'
    status = 'TODO'
    priority = 'HIGH'
} | ConvertTo-Json
$task = Invoke-RestMethod -Method Post -Uri "$core/api/v1/projects/$projectId/tasks" -Headers $headers -ContentType 'application/json' -Body $taskBody
$task
```

读取刚创建的数据：

```powershell
Invoke-RestMethod -Uri "$core/api/v1/projects" -Headers $headers
Invoke-RestMethod -Uri "$core/api/v1/projects/$projectId/wiki-pages" -Headers $headers
Invoke-RestMethod -Uri "$core/api/v1/projects/$projectId/tasks" -Headers $headers
```

## 11. 体验 Day 4 RAG Chat

```powershell
$chatBody = @{
    message = 'Which service owns authentication and writes?'
    conversationId = $null
} | ConvertTo-Json
$chat = Invoke-RestMethod -Method Post -Uri "$core/api/v1/projects/$projectId/agent/chat" -Headers $headers -ContentType 'application/json' -Body $chatBody
$chat
```

预期结构：

```text
conversationId : 一个 UUID
answer         : 包含命中项目片段的确定性摘要
requestId      : 一个请求追踪 ID
sources        : 至少包含 Architecture Wiki 来源
```

这个请求实际经过：JWT 校验 → 项目权限校验 → Java HTTP 客户端 → Python FastAPI → LangGraph `prepare`/`retrieve`/`respond` → Core 内部来源授权 → Chunk/Embedding/BM25/RRF → Java 响应。

再询问 `Which task verifies chat?`，预期 `sources` 包含之前创建的 `Verify local chat` Task。查询与当前项目无关的随机文本时允许返回空数组，但不能引用其他项目。

## 12. 体验 Day 5 Tool Calling 与人工确认

创建提案：

```powershell
$proposalChat = Invoke-RestMethod -Method Post -Uri "$core/api/v1/projects/$projectId/agent/chat" -Headers $headers -ContentType 'application/json' -Body (@{
    message = '把登录模块的改造需求整理成任务，优先级设为高。'
} | ConvertTo-Json)
$proposalChat.pendingAction
```

此时读取 Task 列表不应出现新任务。确认后才写入：

```powershell
$actionId = $proposalChat.pendingAction.id
$executed = Invoke-RestMethod -Method Post -Uri "$core/api/v1/projects/$projectId/agent/actions/$actionId/confirm" -Headers $headers
$executed.resultTask
```

把最后路径改为 `/reject` 可以拒绝另一个 `PENDING` action；拒绝后不能再确认。更新表达必须包含 Task UUID 和当前 version，例如：

```text
update task <task-id> version 0: status=DONE; priority=HIGH
```

这项显式要求使 V1 的确定性 planner 不会猜测目标；Day 6 页面可从已加载 Task 自动构造上下文。

## 13. 观察数据库中的数据

```powershell
docker compose --env-file .env -f infra/compose.yaml exec postgres psql -U agentforge -d agentforge -c 'select id,email,role from app_user;'
docker compose --env-file .env -f infra/compose.yaml exec postgres psql -U agentforge -d agentforge -c 'select id,name,owner_id from project;'
docker compose --env-file .env -f infra/compose.yaml exec postgres psql -U agentforge -d agentforge -c 'select title,version from wiki_page;'
docker compose --env-file .env -f infra/compose.yaml exec postgres psql -U agentforge -d agentforge -c 'select title,status,priority,version from task_item;'
docker compose --env-file .env -f infra/compose.yaml exec postgres psql -U agentforge -d agentforge -c 'select action_type,status,title,result_task_id from agent_task_action;'
```

## 14. 停止服务

在 Java 和 Python 的两个运行窗口分别按 `Ctrl+C`，然后在仓库根目录执行：

```powershell
docker compose --env-file .env -f infra/compose.yaml down
```

该命令保留 PostgreSQL 命名卷，下次启动数据仍在。只有明确要删除所有本地演示数据时才使用 `docker compose --env-file .env -f infra/compose.yaml down -v`；这会不可恢复地删除本项目 Compose 卷，不是常规停止步骤。

## 15. 常见问题

- Java 构建提示 class 版本或 Enforcer 错误：`java -version` 没有指向 Java 21。
- 端口占用：确认本机 5432、8000、8080 没有其他服务，或调整 `.env` 中相应端口和 URL。
- 数据库连接失败：执行 `docker compose --env-file .env -f infra/compose.yaml ps`，确认 PostgreSQL 为 `healthy`。
- JWT 启动失败：确认 `.env` 中的 `AGENTFORGE_JWT_SECRET` 已替换，且 Base64 解码后至少 32 字节。
- Agent 返回 503：先检查 `http://localhost:8000/health`，再确认 Java 与 Python使用完全相同的 `AGENTFORGE_AGENT_INTERNAL_TOKEN`。
- Day 4 RAG 返回 503：再检查 `AGENTFORGE_CORE_INTERNAL_TOKEN` 两端是否一致、`AGENTFORGE_AGENT_CORE_API_URL` 是否指向 Core API，以及 PostgreSQL 镜像是否支持 `CREATE EXTENSION vector`。
- Day 5 Chat 没有 `pendingAction`：使用文档中的明确创建表达；更新必须包含合法 Task UUID、当前 version 和至少一个修改字段。
- confirm 返回 409：action 已拒绝，或目标 Task version 已变化；重新读取 Task 后创建新提案。
- openai Embedding 失败：确认 provider、API URL、模型、384 维配置和本机 key；排查时不得输出 key。需要无网络恢复时切回 `hash`。
- Bearer 请求返回 401：token 可能已超过默认 30 分钟，重新登录获取。
- 请求返回错误时：记录响应头 `X-Request-Id`，到 Core API 日志中搜索相同值。
