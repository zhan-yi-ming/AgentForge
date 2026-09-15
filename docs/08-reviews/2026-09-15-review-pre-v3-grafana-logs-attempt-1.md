# Pi 代码审查报告：pre-v3-grafana-logs / Attempt 1

- 日期：2026-09-15
- 审查阶段：pre-v3-grafana-logs
- 审查对象：INDEX@a143e78（基线：af7afa3a2e743a3a8e4dce63845a7b62d20e28da）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- REVIEW_RESULT: NEEDS_FIX
- Pi 进程超时上限：600 秒
- Codex 处理状态：RESOLVED（Attempt 2 已通过）

---

REVIEW_RESULT: NEEDS_FIX

# AgentForge 独立只读审查报告

- 审查阶段: pre-v3-grafana-logs
- 审查模式: Diff Review（第 1/3 轮）
- 审查范围: `af7afa3a2e743a3a8e4dce63845a7b62d20e28da .. INDEX@a143e78`
- 审查模型: deepseek/deepseek-v4-pro
- 结论: 需修复后交付。发现 5 项“必须修改”，均为本次 diff 内可直接定位并具备证据的问题；主要集中在健康检查契约冲突、Grafana 搜索框 Query 注入、凭据校验强度、登录入口无速率限制，以及 smoke 中内嵌弱凭据。

---

## 概述与总体结论

本次变更引入 Grafana 12.3.11 + Loki 3.7.0 + Alloy v1.19.0 日志界面。整体结构清晰：ADS 决策记录、部署文档、Compose 只挂内网、Nginx 仅暴露 `/grafana/`、七名 gate 通过 `service_started` 而非业务依赖、Alloy 仅保留 DAC_OVERRIDE 并对 socket 只读挂载，这些方向均与 ADR-0023 一致。配置本身可以渲染，静态校验脚本覆盖了主要边界。

但存在 5 个必须修复的问题：`health-check.sh` 把“观测栈 8 服务全部 running + Grafana 未认证 401”硬性绑定为业务健康检查通过条件，直接与 ADR、“Grafana 停止不影响业务”的公开契约矛盾；Grafana 搜索框变量未做转义；Grafana 管理员密码校验仅长度无复杂度；`/grafana/` 入口无任何速率限制；smoke 脚本在仓库内硬编码固定弱口令。上述问题会让已文档化的“观测故障不阻塞业务”无法在自动健康检查中成立，并使浏览器搜索特定 `request_id` 时在特殊字符输入下工作不可靠。

---

## 详细发现清单

| ID | 严重级别 | 文件 | 行（新文件行号） | 核心问题 | 分类 |
|----|---------|------|----------------|---------|------|
| 1 | High | scripts/deploy/health-check.sh | EXPECTED_SERVICES=8、新增 Grafana 401 断言 | 观测栈被硬性纳入业务健康门禁，违反“观测故障不影响业务”契约 | 必须修改 |
| 2 | Medium | infra/observability/grafana/dashboards/agentforge-logs.json | 全文搜索 targets expr | `$search` 未转义直接注入 LogQL，反引号/换行/正则字符破坏查询 | 必须修改 |
| 3 | Medium | scripts/deploy/validate-env.sh | `[[ ${#GRAFANA_ADMIN_PASSWORD} -ge 16 ]]` | 管理员密码仅长度校验，无复杂度，接受全同弱口令，与文档“强密码”矛盾 | 必须修改 |
| 4 | Medium | infra/nginx/production.conf.template | `location ^~ /grafana/` | Grafana 登录入口无速率限制，暴力破解面不设防 | 必须修改 |
| 5 | Low | scripts/validation/grafana-logs-smoke.ps1 | `$adminPassword = "grafana-smoke-password-1234"` | smoke 强编码固定管理员口令，泄漏/误留驻风险 | 必须修改 |

---

## 逐个 Issue 展开

### Issue 1 — health-check.sh 把观测栈硬性纳入业务健康检查

- **Severity**: High
- **File & Line**: `scripts/deploy/health-check.sh`（`EXPECTED_SERVICES=8`；新增 Grafana `/api/search` 401 断言）
- **Evidence**:

```bash
EXPECTED_SERVICES=8
RUNNING="$(compose ps --status running -q | wc -l | tr -d ' ')"
[[ "${RUNNING}" == "${EXPECTED_SERVICES}" ]] || { ... exit 1; }
...
GRAFANA_STATUS="$(curl ... "https://${PUBLIC_HOST}/grafana/api/search" ... -w '%{http_code}')"
[[ "${GRAFANA_STATUS}" == "401" ]] || { ... exit 1; }
```

- **Description**: `health-check.sh` 是部署/更新统一验收入口；本轮把服务总数从 5 拉高到 8，并要求 Grafana 未认证返回 401 才健康。`alloy` 未单独探测但也被计入 `RUNNING`。因此一旦 Loki/Alloy/Grafana 任一停止、健康检查计数不足或 Grafana 返回非 401，`health-check.sh` 退出 1，自动发布/回滚流程被阻断。这与 ADR-0023“观测栈停止时业务继续运行”、变更记录“采集和存储故障不阻塞业务容器”直接冲突。`compose ps --status running -q` 计的是当前 Compose 项目下所有容器，未限定业务服务，观测容器被无条件纳入“业务健康”。
- **Suggested Fix**: 将业务健康与观测健康分离：`EXPECTED_SERVICES` 保持 5，业务服务必须运行；Grafana 与 Loki/Alloy 改为独立通告路径，观测异常只警告不返回 1（例如 `set +e`/`|| echo warning`，不触发 `exit 1`）。同时用项目过滤 `compose ps --status running -q postgres core-api agent-service web gateway` 与观测服务拆开计数。

---

### Issue 2 — Grafana 搜索框变量未转义，反引号等输入可破坏 LogQL

- **Severity**: Medium
- **File & Line**: `infra/observability/grafana/dashboards/agentforge-logs.json`（面板 `targets[0].expr`，字符串模板 `$search`）
- **Evidence**:

```json
"expr": "{stack=\"agentforge\", service=~\"$service\"} |= `$search`"
```

- **Description**: `search` 文本框变量在查询时被 Grafana 插值后直接放入反引号字面量。若用户在浏览器输入含反引号（`` ` ``）、换行、`\`、`}` 或 LogQL 保留字符的值，反引号字面量可能被提前闭合，注入多余 Query 片段或直接报错 400，导致“复制完整 `request_id` 后按搜索框查日志”这一主交互路径在特殊字符下不可用。需求文档明确要求“search 输入普通关键词或 `request_id` 值；这是日志行包含过滤”，未考虑特殊字符。当前 `.json` 中 `editable: false`，管理员也无法在 UI 内修改表达式规避。
- **Suggested Fix**: 对 `$search` 进行转义后再嵌入字面量，例如 `${search:lucene}` 或先 `regex.replaceAllVariables` 处理并转义反引号、反斜杠和换行；或改用 regex 字面量 `|~ "${search:regex}"`。至少应通过预处理避免反引号直接闭合。

---

### Issue 3 — Grafana 管理员密码仅长度校验，允许全同弱口令

- **Severity**: Medium
- **File & Line**: `scripts/deploy/validate-env.sh`（`[[ "${#GRAFANA_ADMIN_PASSWORD}" -ge 16 ]]`）
- **Evidence**:

```bash
[[ "${#GRAFANA_ADMIN_PASSWORD}" -ge 16 ]] || {
    echo "GRAFANA_ADMIN_PASSWORD must contain at least 16 characters." >&2
    exit 1
}
```

- **Description**: 生成器用 `openssl rand -hex 24` 满足要求，但既有服务器升级时手册追加的值只做长度检查，`aaaaaaaaaaaaaaaa`、`grafana-password-1` 等明显弱口令都会通过 `validate-env.sh`。而 `docs/06-operations/production-single-host.md` 要求在 `.env` 中给“强 `GRAFANA_ADMIN_PASSWORD`”（至少 16 字符的随机值），校验与文档承诺强度严重不一致。Grafana 管理员是公网 `/grafana/` 登录的唯一入口，口令强度缺失使其等同于裸登录。
- **Suggested Fix**: 在 `validate-env.sh` 增加复杂度要求：长度 ≥16 且至少包含数字、小写、大写、符号中的 2 类，且拒绝全同字符串；同时同步文档“强密码”定义。生成器生成随机值已满足，仅扩大既有环境手工值的校验即可。

---

### Issue 4 — Grafana 登录入口缺少速率限制

- **Severity**: Medium
- **File & Line**: `infra/nginx/production.conf.template`（`location = /grafana` / `location ^~ /grafana/`）
- **Evidence**:

```nginx
location = /grafana {
    return 301 /grafana/;
}

location ^~ /grafana/ {
    proxy_pass http://grafana:3000;
    ...
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection $connection_upgrade;
}
```

- **Description**: 应用登录相关路径有 `login_per_ip:10m rate=5r/m` 限流，而 Grafana 登录同样暴露在公网，但 `/grafana/` location 不含任何 `limit_req`。未登录时 `/grafana/api/search` 返回 401，密码正确性可通过响应差异探测；对低带宽自托管暴露的暴力破解无任何 URL 防护。健康检查证据也显示该路径存在未认证请求，确实可被外部触及。
- **Suggested Fix**: 给 `location ^~ /grafana/` 增加独立 `limit_req zone=grafana_per_ip:10m rate=5r/m burst=5 nodelay`（或在登录 login location 内单独限制）；在 `http` 级新增 `limit_req_zone $binary_remote_addr zone=grafana_login:10m rate=5r/m;`，与现有 API 限流同风格。

---

### Issue 5 — smoke 脚本硬编码固定 Grafana 管理员弱口令

- **Severity**: Low
- **File & Line**: `scripts/validation/grafana-logs-smoke.ps1`（`$adminPassword = "grafana-smoke-password-1234"`）
- **Evidence**:

```powershell
$adminUser = "agentforge-smoke"
$adminPassword = "grafana-smoke-password-1234"
```

- **Description**: smoke 使用固定、弱的口令，且用户名与口令是常见组合。该脚本仓库内公开，如果 CI 或操作者在非隔离环境提升为真实环境变量，Grafana 管理员凭据等于已知；即使仅在测试项目，也会让管理员密码固定模式进入 Git，易被误复制到生产 `.env`。另外该明文出现在命令参数中，其他用户可经进程列表看到。
- **Suggested Fix**: 不在仓库写入固定口令；在脚本内运行时用 `-join ((48..57)+(97..122) | Get-Random -Count 24 | % {[char]$_})` 等生成一次性随机口令，并确保只在脚本进程作用域内使用，不写回仓库/日志。同时不要以 `--user user:pass` 放在命令行参数，可改为 Grafana API 通过 Basic 头写入临时变量。

---

## 主开发 (Codex) 评估回填区

| ID | Codex 评估 | 处理方式 | 对应 commit / 说明 |
|----|-----------|---------|-------------------|
| 1 | 采纳；业务容器虽无观测依赖，但统一发布健康脚本确会被观测故障阻断。 | 五个业务服务与 HTTPS/API 认证保持硬失败；三个观测服务及 Grafana 检查改为 warning，并新增真实 CLI fail-open 契约。 | `scripts/deploy/health-check.sh`、`scripts/validation/tls-public-host-contract.sh` |
| 2 | 采纳；反引号文本插值可破坏 LogQL 字面量。 | 改用 `|~ "${search:regex}"`，由 Grafana regex formatter 转义文本变量；静态配置契约锁定表达式。 | dashboard JSON、`grafana-logs-config.ps1` |
| 3 | 采纳；“随机”不能由脚本证明，但原 16 字符长度规则确实过弱。 | 改为至少 24 字符且同时含 ASCII 字母与数字；生成器增加固定类别前缀并保留 24-byte 随机 hex；Linux 契约验证重复字符值被拒绝。 | `validate-env.sh`、`generate-production-env.sh`、运维文档与 TLS contract |
| 4 | 采纳；对整个 Grafana location 限流会破坏资源/查询，因此只限制精确登录入口。 | `/grafana/login` 复用 `login_per_ip` 的 5r/m、burst 5；真实 Nginx 解析与静态契约通过。 | Nginx template、运维/安全文档 |
| 5 | 采纳。 | smoke 用密码学随机源生成一次性密码，并通过 curl stdin config 传递管理员认证，不再硬编码或放进 curl 命令行参数。 | `grafana-logs-smoke.ps1`、静态契约与真实 smoke |

---

## 其他说明

- 未发现敏感密钥泄漏：`.env.production.example` 中 `REPLACE_WITH_*` 均为占位符，真实运行环境校验 `grep -q 'REPLACE_'` 拒绝部署；Demo 固定凭据为既有公开数据。
- Alloy 只读 socket + `cap_drop: [ALL]` + 恢复 `DAC_OVERRIDE` + `no-new-privileges` 的隔离取舍已在 ADR-0023 中明确接受，本次未发现额外越权路径；发现仅限上述安全强度与速率限制点。
- `health-check.sh` 中 Grafana 401 探测本身合法，问题在于被放入与业务健康同等的退出码断言；其余 shell/Compose 语法、Nginx 渲染与容积无冲突。

需修复后交付。下一轮将重点核验上述 5 项修复及其引入的新问题。
