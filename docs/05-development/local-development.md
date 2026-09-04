# Day 1–Day 3 本地启动与体验教程

- 状态：Implemented
- 适用系统：Windows PowerShell
- 当前可运行应用：Core API、Agent Service
- 当前没有 Web 页面：React Web 在 Day 6 才实现，本教程通过 HTTP API 观察功能

## 1. 今天完成了什么

启动后可以实际体验以下链路：

1. Day 1：PostgreSQL、用户和项目基础数据。
2. Day 2：注册、登录、JWT、项目权限、Wiki 与 Task CRUD。
3. Day 3：Java Core API 完成鉴权和项目授权后，通过真实 HTTP/1.1 调用 Python FastAPI + LangGraph Chat。

Day 3 当前使用 deterministic responder，所以 Chat 会返回 `Agent service received: 你的消息`。它证明 Java/Python 链路已经打通，但还不会检索 Wiki；RAG 属于尚未开发的 Day 4。

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
$random.GetBytes($jwtBytes)
$random.GetBytes($tokenBytes)
$random.Dispose()
$jwtSecret = [Convert]::ToBase64String($jwtBytes)
$internalToken = -join ($tokenBytes | ForEach-Object { $_.ToString('x2') })
$content = Get-Content .env -Raw
$content = $content.Replace('REPLACE_WITH_BASE64_32_BYTE_RANDOM_VALUE', $jwtSecret)
$content = $content.Replace('REPLACE_WITH_RANDOM_INTERNAL_TOKEN', $internalToken)
Set-Content .env $content -NoNewline
```

`.env` 已被 Git 忽略，禁止提交或把其中两个随机值粘贴到 Issue、日志和聊天中。以后已有 `.env` 时不要重复覆盖，除非你明确希望更换本地密钥。

## 4. 启动 PostgreSQL

Redis 是后续阶段预留组件，Day 1–Day 3 只需要 PostgreSQL：

```powershell
docker compose --env-file .env -f infra/compose.yaml up -d postgres
docker compose --env-file .env -f infra/compose.yaml ps
```

等待 `postgres` 显示 `healthy`。首次启动需要拉取 `postgres:17-alpine`，会比后续启动慢。

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
    description = 'Day 1 to Day 3 local demonstration'
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

## 11. 体验 Day 3 Agent Chat

```powershell
$chatBody = @{
    message = 'Explain what the Agent Service received'
    conversationId = $null
} | ConvertTo-Json
$chat = Invoke-RestMethod -Method Post -Uri "$core/api/v1/projects/$projectId/agent/chat" -Headers $headers -ContentType 'application/json' -Body $chatBody
$chat
```

预期结构：

```text
conversationId : 一个 UUID
answer         : Agent service received: Explain what the Agent Service received
requestId      : 一个请求追踪 ID
```

这个请求实际经过：JWT 校验 → 项目权限校验 → Java HTTP 客户端 → Python FastAPI → LangGraph `prepare`/`respond` 节点 → Java 响应。

## 12. 观察数据库中的数据

```powershell
docker compose --env-file .env -f infra/compose.yaml exec postgres psql -U agentforge -d agentforge -c 'select id,email,role from app_user;'
docker compose --env-file .env -f infra/compose.yaml exec postgres psql -U agentforge -d agentforge -c 'select id,name,owner_id from project;'
docker compose --env-file .env -f infra/compose.yaml exec postgres psql -U agentforge -d agentforge -c 'select title,version from wiki_page;'
docker compose --env-file .env -f infra/compose.yaml exec postgres psql -U agentforge -d agentforge -c 'select title,status,priority,version from task_item;'
```

## 13. 停止服务

在 Java 和 Python 的两个运行窗口分别按 `Ctrl+C`，然后在仓库根目录执行：

```powershell
docker compose --env-file .env -f infra/compose.yaml down
```

该命令保留 PostgreSQL 命名卷，下次启动数据仍在。只有明确要删除所有本地演示数据时才使用 `docker compose --env-file .env -f infra/compose.yaml down -v`；这会不可恢复地删除本项目 Compose 卷，不是常规停止步骤。

## 14. 常见问题

- Java 构建提示 class 版本或 Enforcer 错误：`java -version` 没有指向 Java 21。
- 端口占用：确认本机 5432、8000、8080 没有其他服务，或调整 `.env` 中相应端口和 URL。
- 数据库连接失败：执行 `docker compose --env-file .env -f infra/compose.yaml ps`，确认 PostgreSQL 为 `healthy`。
- JWT 启动失败：确认 `.env` 中的 `AGENTFORGE_JWT_SECRET` 已替换，且 Base64 解码后至少 32 字节。
- Agent 返回 503：先检查 `http://localhost:8000/health`，再确认 Java 与 Python使用完全相同的 `AGENTFORGE_AGENT_INTERNAL_TOKEN`。
- Bearer 请求返回 401：token 可能已超过默认 30 分钟，重新登录获取。
- 请求返回错误时：记录响应头 `X-Request-Id`，到 Core API 日志中搜索相同值。
