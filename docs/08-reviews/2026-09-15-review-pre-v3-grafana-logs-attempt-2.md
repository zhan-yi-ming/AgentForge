# Pi 代码审查报告：pre-v3-grafana-logs / Attempt 2

- 日期：2026-09-15
- 审查阶段：pre-v3-grafana-logs
- 审查对象：INDEX@a143e78（基线：af7afa3a2e743a3a8e4dce63845a7b62d20e28da）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：600 秒
- Codex 处理状态：RESOLVED（PASS；非阻断建议 S1 已采纳并完成相关回归）

---

REVIEW_RESULT: PASS

# AgentForge 独立只读审查报告

- 审查阶段: pre-v3-grafana-logs
- 审查模式: Diff Review（第 2/3 轮，Attempt 1 复审）
- 审查范围: `af7afa3a2e743a3a8e4dce63845a7b62d20e28da .. INDEX@a143e78`
- 审查模型: deepseek/deepseek-v4-pro
- 结论: 通过。Attempt 1 的 5 项“必须修改”均已按约定修复，且修复本身未引入新的阻断性问题。本轮无“必须修改”。

---

## 概述与总体结论

本轮为 Attempt 1 修复后的只读复审，逐一核验 5 项历史发现及其实施方式，结果为全部落实：

1. `health-check.sh` 已将业务服务（postgres/core-api/agent-service/web/gateway）与观测服务（loki/alloy/grafana）拆成两套计数与断言；业务五服务及 HTTPS/API 401 仍硬失败，观测异常只写 stderr warning 不再 `exit 1`。新增 `tls-public-host-contract.sh::run_observability_health_fail_open` 用假 docker/curl 真实验证了“业务通过 + 观测 warning”的 fail-open 契约。
2. dashboard 搜索变量已从反引号字面量 `|= \`$search\`` 改为 `|~ "${search:regex}"`，由 Grafana regex formatter 转义正则元字符，避免反引号/换行/正则字符破坏查询结构；`grafana-logs-config.ps1` 用精确字符串断言把该表达式锁死为静态契约。
3. `validate-env.sh` 将 Grafana 管理员密码校验提升为 ≥24 字符且同时含 ASCII 字母与数字；全同字符值因必不满足“字母+数字”双类要求而被隐式拒绝。生成器新增固定类别前缀 `Af9` + 48 位随机 hex（总长 51），满足规则；文档同步为可验证要求。
4. Nginx 仅对精确 `location = /grafana/login` 复用既有 `login_per_ip`（5r/m、burst 5）限流，未对 dashboard 静态资源与日志查询整体限流；静态契约与真实 `nginx -T` 解析均覆盖。
5. smoke 脚本改用 `System.Security.Cryptography.RandomNumberGenerator` 运行时生成一次性管理员密码，并通过 `curl --config -` 从 stdin 传递 Basic 认证，不再硬编码口令、不进入命令行参数；`grafana-logs-config.ps1` 同时断言“存在 RandomNumberGenerator、不存在旧弱口令、不存在 `--user`”。

修复方向与 ADR-0023、变更记录、运维文档公开承诺一致；本轮未发现敏感信息泄漏、权限绕过、业务/观测依赖反置或 API 契约冲突。仅保留 1 项非阻断性建议。

---

## 详细发现清单

### 必须修改

无。

### 建议修改

| ID | 严重级别 | 文件 | 行 | 核心问题 |
|----|---------|------|-----|---------|
| S1 | Low | infra/observability/grafana/dashboards/agentforge-logs.json | targets[0].expr（约 L28） | `${search:regex}` 仅转义正则元字符，不转义 LogQL 字符串定界符 `"` 与换行，输入含双引号时可闭合字面量、造成 400 或查询结构变化 |

### 无需修改（Attempt 1 五项修复核验结论）

| ID | 原发现 | 核验结论 |
|----|--------|---------|
| 1 | 观测栈被纳入业务健康门禁 | 已修复：业务/观测分离，观测仅 warning；契约测试覆盖 |
| 2 | 搜索框变量未转义破坏 LogQL | 已修复：改用 `${search:regex}` 并静态锁定表达式 |
| 3 | 管理员密码仅长度校验 | 已修复：≥24 + 字母 + 数字，全同值被隐式拒绝；生成器满足 |
| 4 | Grafana 入口无速率限制 | 已修复：`/grafana/login` 复用 `login_per_ip` 限流 |
| 5 | smoke 硬编码固定弱口令 | 已修复：运行时随机生成 + stdin config 传递认证 |

---

## 逐个 Issue 展开

### Issue S1 — `${search:regex}` 未转义 LogQL 字符串定界符（建议修改，不阻塞）

- **Severity**: Low
- **File & Line**: `infra/observability/grafana/dashboards/agentforge-logs.json`（`targets[0].expr`，约 L28）
- **Evidence**:

```json
"expr": "{stack=\"agentforge\", service=~\"$service\"} |~ \"${search:regex}\"",
```

- **Description**: Grafana 的 `regex` formatter 等价于 Go `regexp.QuoteMeta`，只转义正则元字符（`. + * ? ( ) [ ] { } | \ ^ $`），不转义双引号 `"` 与换行。当前表达式用双引号包住插值结果，若用户在 search 输入含 `"` 的值，`|~ "..."` 字面量会被提前闭合，导致 Loki 返回 400 或查询结构被改变。这与 Round 1 修复目标“避免特殊字符破坏查询结构”在双引号这一边界上仍有残留。需要说明：文档化主用例（UUID 形态的 `request_id`、普通关键词）不含 `"`，且该操作是管理员查询自身日志、无跨用户权限影响，因此不构成本轮阻断。
- **Suggested Fix**（建议，不阻塞）: 文档先行确认边界后二选一：(a) 改用能转义 `\`、`"`、换行的格式（如 LiteQL 专用转义或 `${search:loki}`，需先验证该 formatter 在 Grafana 12.3.11 存在且语义为文本字面量）；(b) 在 dashboard 外预处理时把 `"` 转义为 `\"` 并去除换行。可选地在 `grafana-logs-smoke.ps1` 增加一条含 `"` 或反斜杠的 search 真实查询断言，把该边界纳入回归。

---

## 主开发 (Codex) 评估回填区

| ID | Codex 评估 | 处理方式 | 对应 commit / 说明 |
|----|-----------|---------|-------------------|
| S1 | 采纳；虽然不阻断 request_id 主路径，但可以用更简单的纯文本语义完整解决。 | 改用 `${search:json}` 生成安全的 LogQL 字符串字面量，并恢复 `|=` 纯文本包含；引号、反斜杠和换行由 JSON formatter 转义，正则元字符不再具有特殊语义。纯建议不触发第三轮 Pi，重跑配置与真实 smoke。 | dashboard JSON、`grafana-logs-config.ps1`、运维文档 |

（本表由 Codex 回填，Pi 不代填。）

---

## 其他说明

- `health-check.sh` 中 `set -Eeuo pipefail` 在观测分支的兼容性已核对：`compose ps` 空结果为 0 计入 `wc -l`；`elif ! curl ...` 由 `!` 取反、`GRAFANA_STATUS="$(curl ... || true)"` 由 `|| true` 兜底，均不会使 set -e 提前退出，不会把观测失败上抛为业务失败。
- 生成器密码 `Af9 + openssl rand -hex 24`（51 字符）满足新校验规则；`tls-public-host-contract.sh` 以 `Af9`+48 个 `0` 的假 openssl 输出走通生成断言，且重复字符值 `aaaaaaaaaaaaaaaaaaaaaaaa` 被 `[0-9]` 规则拒绝，与“不能全同字符”文档一致。
- `tls-public-host-nginx.ps1` 为 nginx 渲染补充 `--add-host grafana:127.0.0.1`，保证 `proxy_pass http://grafana:3000` 在 `nginx -T` 解析期可解析；`map $http_upgrade $connection_upgrade` 仅新增一处、无重复定义，已由真实解析验证。
- 未发现密钥泄漏：所有新增占位符均为 `REPLACE_*`；Alloy 只读 socket + `cap_drop:[ALL]` + 恢复 `DAC_OVERRIDE` + `no-new-privileges` 的取舍已在 ADR-0023 明确接受，本轮未发现额外越权路径。
- 契约一致性：ADR-0023、运维文档、变更记录中关于“8 服务 / 仅 gateway 发布 80-443 / 观测 fail-open / 登录精确限流 / 密码最小规则 / search 转义”的表述与实现均可对账，无需修改。

本轮完成最终结论：**通过**。
