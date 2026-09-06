# Pi 代码审查报告：tls-public-host-compatibility / Attempt 1

- 日期：2026-09-07
- 审查阶段：tls-public-host-compatibility
- 审查对象：INDEX@431d705（基线：431d70545b6d9a00fad5ce722a159ff42ab624e4）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- REVIEW_RESULT: NEEDS_FIX
- Pi 进程超时上限：600 秒

---

REVIEW_RESULT: NEEDS_FIX

# AgentForge 代码审查报告

## 概述与总体结论

- **审查阶段**：tls-public-host-compatibility
- **审查模式**：Diff
- **审查轮次**：1 / 3
- **审查目标**：`INDEX@431d705`（`431d70545b6d9a00fad5ce722a159ff42ab624e4 .. INDEX@431d705`）
- **总体结论**：**需修复后交付（NEEDS_FIX）**。整体实现与既定目标（IP/根域名/已带 www 域名的 TLS 首次申请、同步、续期与 Nginx server_name 兼容）方向一致，未发现权限绕过、越权、并发/幂等或敏感信息问题。但本次变更明确新增 IPv6 支持，而 `AGENTFORGE_JWT_ISSUER` 对 IPv6 生成非法 URL，属于新增支持路径上的真实契约缺陷，必须修复后交付。其余为验证缺口与弱校验，仅作建议，不阻塞。

关键结论摘要：
- 核心主机类型判断与 `PUBLIC_WWW_HOST` 派生逻辑正确（IPv4/根域名/已带 www 域名三层均覆盖）。
- 证书 cert-name 稳定化（`PUBLIC_HOST`）、live 目录稳定化的设计合理。
- 测试覆盖了 IPv4/IPv6/根域名/已带 www/非法主机拒绝与 Nginx 渲染，真实性良好。
- 唯一达到“必须修改”的问题：IPv6 模式下 JWT issuer 未加 `[]`，生成非法 URI。

---

## 详细发现清单

### 必须修改

| ID | 严重级别 | 文件 | 行号 | 核心问题 |
|---|---|---|---|---|
| FORGE-01 | 高（IPv6 部署） | `scripts/deploy/generate-production-env.sh` | ~L32 | IPv6 `PUBLIC_HOST` 直接拼入 issuer，生成 `https://2001:db8::10/core-api` 非法 URL |

### 建议修改

| ID | 严重级别 | 文件 | 行号 | 核心问题 |
|---|---|---|---|---|
| FORGE-02 | 中 | `scripts/deploy/common.sh` | is_ipv6_address（新增，~L27） | IPv6 校验仅为结构匹配，接受非法 IPv6（如 `1::2::3`、`::`、`1:2:3`），validate-env/generate 会放行 |
| FORGE-03 | 中 | `scripts/deploy/common.sh` `compose()`；`scripts/deploy/deploy.sh`（未在本次 diff） | compose()（~L29） | `PUBLIC_WWW_HOST` 仅在 `compose()` helper 内导出；`deploy.sh` 未在本批改动中，若其直连 `docker compose up` 则 www 派生值不会进入容器；契约测试绕过 deploy 入口 |
| FORGE-04 | 低 | `scripts/deploy/tls-issue.sh`、`tls-renew.sh` | — | IPv6 `--ip-address` + 含冒号 cert-name/live 目录仅由 fake certbot 覆盖，真实 Certbot/LE IPv6 行为未验证 |
| FORGE-05 | 低 | `scripts/deploy/tls-renew.sh` | ~L7 | 既有生产 IP lineage 的 Certbot cert-name 若与本脚本 `--cert-name "${PUBLIC_HOST}"` 不一致，旧环境首次续期可能失败 |

### 无需修改（已确认无问题）

| ID | 文件 | 说明 |
|---|---|---|
| — | `infra/nginx/production.conf.template` | `PUBLIC_WWW_HOST` 为空时渲染为 `server_name <host> ;`（尾随空格），nginx 接受；`compose.prod.yaml` 的 `${PUBLIC_WWW_HOST:-}` 保证变量始终存在，envsubst 会替换为空串而非残留 `${...}` |
| — | `scripts/deploy/common.sh` `is_ipv4_address` | `((10#${octet} <= 255))` 正确处理了前导零八进制，范围校验正确 |
| — | `tls-sync.sh` / `init-tls.sh` | 证书文件权限（fullchain 0644、privkey 0600）与 SAN 派生（IP: / DNS:）正确，无权限劣化 |

---

## 逐个 Issue 展开

### FORGE-01（必须修改）

- **Severity**：高（影响本次新增的 IPv6 部署路径）
- **File & Line**：`scripts/deploy/generate-production-env.sh` ~L32
- **Evidence**：

```bash
AGENTFORGE_JWT_ISSUER=https://${PUBLIC_HOST}/core-api
```

同一提交中 `common.sh` 新增：

```bash
is_ipv6_address() {
    local candidate="$1"
    [[ "${candidate}" == *:* && "${candidate}" =~ ^[0-9A-Fa-f:.]+$ ]]
}
```

`generate-production-env.sh` 由仅接受 IPv4 改为 `is_public_host "${PUBLIC_HOST}"`，因此 `2001:db8::10` 现在可被接受并被原样写入 issuer。

- **Description**：RFC 3986 规定 URI 中的 IPv6 字面量必须用方括号包围。对于 `PUBLIC_HOST=2001:db8::10`，生成的 `AGENTFORGE_JWT_ISSUER=https://2001:db8::10/core-api` 不是合法 URI——Java `URI.create(...)` 会解析失败，任何基于 issuer 构建/解析 URL 的组件（OIDC、前台入口推导、按 issuer 校验场景）都会出错。即便 token 双方都把这个字符串当不透明值比较，该 issuer 仍违反 URI 契约，且与 `.env.production.example` / 文档中 `https://203.0.113.10/core-api` 这类合法 URL 语义不一致。IPv6 是本次变更显式声明的支持类型（“自动区分 IPv4、IPv6 与 DNS 域名”），因此必须修正。
- **Suggested Fix**：

```bash
# 在写 .env 前派生 URL 安全主机
if is_ipv6_address "${PUBLIC_HOST}"; then
    ISSUER_HOST="[${PUBLIC_HOST}]"
else
    ISSUER_HOST="${PUBLIC_HOST}"
fi
# ...
AGENTFORGE_JWT_ISSUER=https://${ISSUER_HOST}/core-api
```

同时建议：`validate-env.sh` 可增加 issuer 的 URL 可达性/格式检查；`.env.production.example` 与 `docs/06-operations` 补充 IPv6 模式下 issuer 须写成 `https://[IPv6]/core-api` 的说明，避免手工维护的 `.env` 同样踩坑。

---

### FORGE-02（建议修改）

- **Severity**：中
- **File & Line**：`scripts/deploy/common.sh` `is_ipv6_address`（新增，~L27）

- **Evidence**：

```bash
is_ipv6_address() {
    local candidate="$1"
    [[ "${candidate}" == *:* && "${candidate}" =~ ^[0-9A-Fa-f:.]+$ ]]
}
```

- **Description**：该实现只检查“含冒号 + 字符集合”，没有验证 IPv6 的组数、`::` 出现次数等结构。`1::2::3`、`::`、`1:2:3`（组数不足）、`2001:db8::10::1` 等非法值都会被 `is_public_host` 接受并写入 `.env`，`validate-env.sh` 与 `generate-production-env.sh` 均会放行，失败推迟到 `tls-issue.sh` 调用 Certbot 时才暴露。这与变更目标“部署前置检查拒绝……非法公网主机”有落差。由于合法 IPv6 输入不受影响、非法输入属于操作者误录，故定为建议而非必须。
- **Suggested Fix**：引入权威校验（任选其一）：

```bash
is_ipv6_address() {
    local candidate="$1"
    [[ "${candidate}" == *:* ]] || return 1
    python3 -c 'import ipaddress,sys; ipaddress.IPv6Address(sys.argv[1])' "${candidate}" 2>/dev/null
}
```

或在文档中明确“非法 IPv6 将在 `tls-issue.sh` 的 Certbot 阶段失败”的边界。

---

### FORGE-03（建议修改）

- **Severity**：中
- **File & Line**：`scripts/deploy/common.sh` `compose()`（~L29）；`scripts/deploy/deploy.sh`（未在本批 diff 中）

- **Evidence**：

```bash
compose() {
    load_public_config
    docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" "$@"
}
```

`compose.prod.yaml`：

```yaml
environment:
  PUBLIC_HOST: ${PUBLIC_HOST:?set PUBLIC_HOST}
  PUBLIC_WWW_HOST: ${PUBLIC_WWW_HOST:-}
```

- **Description**：`PUBLIC_WWW_HOST` 只通过 `load_public_config` 派生并 `export`，而它只被 `compose()` helper 调用。Compose 插值表达式 `${PUBLIC_WWW_HOST:-}` 只能保证变量存在（否则渲染空串），不能自己派生值。因此如果 `deploy.sh`（未在本批变更中）在 `docker compose up -d` 时没有走 `compose()` helper，则 gateway 容器首次创建时就拿不到 www，Nginx 模板渲染为单个 server_name，且后续 `tls-sync.sh` 的 `compose exec nginx -s reload` 只重载已渲染文件、**不会重新执行 envsubst**，www server_name 缺口会一直存在。本次契约测试 `tls-public-host-nginx.ps1` 直接以 PowerShell 手动设置 `PUBLIC_WWW_HOST`，绕过了 `deploy.sh` 这条真实入口，无法证明该耦合正确。
- **Suggested Fix**：确认 `deploy.sh` 所有 `up`/`create` 调用都经由 `compose()` helper；或在其 `docker compose up -d` 前显式 `load_public_config`。并建议新增一条契约断言，覆盖 `deploy.sh`（或等价入口）渲染后 gateway 环境包含 `PUBLIC_WWW_HOST=www.example.com`。

---

### FORGE-04（建议修改）

- **Severity**：低
- **File & Line**：`scripts/deploy/tls-issue.sh`、`tls-renew.sh`
- **Evidence**：契约测试用 fake `docker` 替身模拟 Certbot：

```bash
cat >"${fake_bin}/docker" <<'EOF'
#!/usr/bin/env bash
...
if [[ " $* " == *" certbot/certbot:latest certonly "* ]]; then
    ...  # 伪造 live 目录与证书内容
fi
EOF
```

- **Description**：真实 Certbot 从未执行。IPv6 路径的 `--ip-address 2001:db8::10`、`--cert-name 2001:db8::10`（live 目录名含冒号）是否被 Certbot/Let's Encrypt short-lived 特性接受，本提交未验证。变更文档本身已诚实声明“未执行真实公网 ACME 申请”，故此仅为建议，不阻塞。
- **Suggested Fix**：在部署前做一次真实 IPv6（或至少 IPv4）`--ip-address` 冒烟，或将该风险显式记录在 `docs/07-changes` 的“未验证项”中，便于部署者首次申请前知晓。

---

### FORGE-05（建议修改）

- **Severity**：低
- **File & Line**：`scripts/deploy/tls-renew.sh` ~L7

- **Evidence**：

```bash
docker run --rm \
    -v "${TLS_ROOT}/letsencrypt:/etc/letsencrypt" \
    -v "${TLS_ROOT}/acme:/var/www/certbot" \
    certbot/certbot:latest renew --non-interactive --quiet --cert-name "${PUBLIC_HOST}"
```

- **Description**：变更前旧版 `tls-issue.sh` 未指定 `--cert-name`，既有生产 IP 证书 lineage 名称由 Certbot 自动命名。若该自动名与本脚本期望的 `${PUBLIC_HOST}` 不一致，`certbot renew --cert-name ...` 无法匹配既有 renewal 配置，旧环境在代码更新后首次续期可能失败。多数情况下 Certbot 对单 IP 证书默认命名恰为该 IP（与预期一致），但无证据确认，属于迁移兼容性风险。
- **Suggested Fix**：部署前在服务器执行 `docker compose exec ... certbot certificates`（或 `certbot certificates`）核对既有 lineage 名，如不一致则先用新 `tls-issue.sh` 重新签发一次；建议在 `docs/06-operations` 的 TLS 迁移段落补充该一步核对。

---

## 主开发 (Codex) 评估回填区

> 由 Codex 在修复/确认后回填，Pi 本轮不填写。

| Issue ID | 主开发处理结论（确认 / 已修复 / 不采纳及理由） | 相应代码修改说明 | 状态 |
|---|---|---|---|
| FORGE-01 | 确认并已修复；IPv6 字面量用于 URL authority 时必须带方括号。 | `generate-production-env.sh` 派生 `ISSUER_HOST`；IPv6 写为 `https://[IPv6]/core-api`，并增加生成契约。 | 已完成 |
| FORGE-02 | 确认并已修复；部署前应拒绝结构非法的 IPv6。 | `common.sh` 校验 hextet、组数和唯一压缩段；契约确认 `1::2::3` 被入口拒绝。 | 已完成 |
| FORGE-03 | 不采纳；该结论的前提不成立。 | 已核对 `deploy.sh`，其 `config/up/build/pull/ps` 全部调用 `compose()`，而 helper 会先加载并导出 `PUBLIC_WWW_HOST`。 | 已核实，无代码修改 |
| FORGE-04 | 接受为部署限制，不阻塞本次本地交付。 | 变更记录保留“未执行真实公网 ACME/IPv6 申请”的明确限制；首次服务器申请时验证。 | 已记录 |
| FORGE-05 | 接受迁移核对建议。 | 运维文档增加升级前通过 `certbot certificates` 核对既有 lineage，名称不一致时重新首次签发。 | 已记录 |

---

### 附注

- 本轮仅给出 5 项发现（1 项必须修改、4 项建议修改），均基于 diff 内可证实证据；未提供证据范围的风格、未来优化类内容未列入。
- 未执行任何命令、未修改任何文件与 Git 状态，符合只读审查职责。
- 依据固定模型要求，本轮以 `deepseek/deepseek-v4-pro` 完成审查。
