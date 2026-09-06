# Pi 代码审查报告：tls-public-host-compatibility / Attempt 2

- 日期：2026-09-07
- 审查阶段：tls-public-host-compatibility
- 审查对象：INDEX@431d705（基线：431d70545b6d9a00fad5ce722a159ff42ab624e4）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：600 秒

---

REVIEW_RESULT: PASS

# AgentForge 代码审查报告

## 概述与总体结论

- **审查阶段**：tls-public-host-compatibility
- **审查模式**：Diff
- **审查轮次**：2 / 3
- **审查目标**：`INDEX@431d705`（`431d70545b6d9a00fad5ce722a159ff42ab624e4 .. INDEX@431d705`）
- **总体结论**：**通过（PASS）**。上一轮唯一“必须修改”项 FORGE-01（IPv6 JWT issuer 缺少方括号）已在 `generate-production-env.sh` 中正确修复，FORGE-02（IPv6 结构校验过弱）已用分段/压缩段/组数校验正确修复，两者均有对应契约红灯/绿灯测试覆盖。本轮未发现新的可阻塞缺陷、权限绕过、越权、并发/幂等或数据一致性问题；剩余为低严重度的测试覆盖缺口与前置校验加固建议，不阻塞交付。

关键结论摘要：
- `is_ipv6_address` 重写后对 `1::2::3`、`::`、`1:2:3`、`::1:2::3` 等非法输入均可拒绝，对 `2001:db8::10`、`::1`、`fe80::`、`1:2:3:4:5:6:7::` 等合法输入均接受，逻辑正确。
- `generate-production-env.sh` 派生 `ISSUER_HOST`，IPv6 时写 `https://[2001:db8::10]/core-api`，并已有合同测试断言，FORGE-01 确认修复。
- 主机类型派生、`PUBLIC_WWW_HOST`（根域名自动加 www、已带 www 不重复、IP 为空）、cert-name/live 目录稳定化、临时自签 SAN（IP:/DNS:）、renew 定向 cert-name 的代码实现与文档一致。
- FORGE-03（compose helper 与 deploy.sh 耦合）主开发已核实不采纳，本 diff 不含 `deploy.sh`，无反向证据，不再重复提出。
- 遗留两项低严重度建议：IPv6 的 Nginx `server_name` 渲染与测试覆盖缺口、`validate-env.sh` 未校验手填 issuer 的 URL 格式。

---

## 详细发现清单

### 必须修改

无。

### 建议修改

| ID | 严重级别 | 文件 | 行号 | 核心问题 |
|---|---|---|---|---|
| FORGE-06 | 低–中 | `infra/nginx/production.conf.template`、`scripts/validation/tls-public-host-nginx.ps1` | 模板 L6/L16；测试脚本整体 | IPv6 模式 Nginx `server_name` 渲染为裸 IPv6（缺方括号），且 Nginx 契约测试只覆盖 IPv4/根域名/已带 www，未覆盖 IPv6 场景 |
| FORGE-07 | 低 | `scripts/deploy/validate-env.sh` | ~L19（`is_public_host` 调用之后） | 前置校验不校验 `AGENTFORGE_JWT_ISSUER` 的 URL 格式；手工维护的 `.env` 若 IPv6 漏写方括号，会以非法 issuer 通过校验 |

### 无需修改（已确认无问题）

| ID | 文件 | 说明 |
|---|---|---|
| FORGE-01 修复 | `scripts/deploy/generate-production-env.sh` | `ISSUER_HOST` 派生正确，IPv6 输出 `https://[2001:db8::10]/core-api`，契约测试断言覆盖，修复有效 |
| FORGE-02 修复 | `scripts/deploy/common.sh` `is_ipv6_address` | 结构校验（group/压缩段/字符集/长度）正确拒绝重复 `::`、组数不足、非法字符，接受全部合法形态 |
| — | `scripts/deploy/common.sh` `is_ipv4_address` | `10#` 处理前导零八进制，范围校验 `<=255` 正确 |
| — | `infra/nginx/production.conf.template` | `PUBLIC_WWW_HOST` 为空时 `server_name example.com ;` 末尾空白被 nginx 接受；`compose.prod.yaml` 的 `${PUBLIC_WWW_HOST:-}` 保证变量始终被定义 |
| — | `scripts/deploy/tls-issue.sh` / `init-tls.sh` | IP 用 `--ip-address --preferred-profile shortlived`、域名用 `-d`（根域名自动附带一个 www），SAN 用 `IP:`/`DNS:` 派生正确，无权限劣化（privkey 0600、fullchain 0644） |
| FORGE-03/04/05 | 文档/主开发现 | FORGE-03 主开发已核实 `deploy.sh` 走 `compose()` 而不采纳；FORGE-04/05 已记录为部署限制与迁移核对提示，本轮不重复 |

---

## 逐个 Issue 展开

### FORGE-06（建议修改）

- **Severity**：低–中（IPv6 支持路径的 Nginx 渲染与测试覆盖）
- **File & Line**：`infra/nginx/production.conf.template` L6、L16；`scripts/validation/tls-public-host-nginx.ps1`（全程）
- **Evidence**：

```nginx
server_name ${PUBLIC_HOST} ${PUBLIC_WWW_HOST};
```

`tls-public-host-nginx.ps1` 仅覆盖三种场景：

```powershell
Test-SingleServerNameScenario -PublicHost "47.76.95.86" ...
Test-SingleServerNameScenario -PublicHost "www.example.com" ...
# 另有根域名主场景 example.com
# 无任何 IPv6 场景
```

- **Description**：本次变更把 IPv6 正式纳入支持范围（`common.sh`、`tls-issue.sh`、`generate-production-env.sh`、契约测试均覆盖 IPv6），但 Nginx 层对 IPv6 渲染为裸字面量 `server_name 2001:db8::10`。浏览器/客户端访问 `https://[2001:db8::10]/` 时发送的 Host 头是带方括号的 `[2001:db8::10]`，与裸 IPv6 `server_name` 字符串不相等。当前单机拓扑下 80/443 各只有一个 server 块，因此该块天然成为默认 server，请求仍会被正确路由，实际影响被掩盖；但若未来同端口增设其他 server 块或依赖 `server_name`/Host 做路由与 HSTS 逻辑，裸 IPv6 将无法匹配。同时契约测试声称覆盖 IP 模式，却只测了 IPv4，属于覆盖缺口。
- **Suggested Fix**：在 `load_public_config` 或模板渲染层对 IPv6 派生带方括号的 Nginx 主机名（例如 `NGINX_PUBLIC_HOST="[${PUBLIC_HOST}]"`，仅 IPv6 时加括号），模板使用该值；并为 `tls-public-host-nginx.ps1` 增加一个 IPv6 场景（如 `2001:db8::10`），断言 `server_name [2001:db8::10];` 通过 `nginx -T`。

---

### FORGE-07（建议修改）

- **Severity**：低（手填 `.env` 路径的 issuer 前置校验缺口）
- **File & Line**：`scripts/deploy/validate-env.sh`（`is_public_host` 校验处之后）
- **Evidence**：

`validate-env.sh` 的必填检查与格式校验为：

```bash
: "${PUBLIC_HOST:?PUBLIC_HOST is required}"
# ... 其余必填项，但 AGENTFORGE_JWT_ISSUER 不在必填与格式校验之列
is_public_host "${PUBLIC_HOST}" || { ... exit 1; }
```

- **Description**：生成器路径已修复（FORGE-01），但运维文档明确允许“手工填写 `AGENTFORGE_JWT_ISSUER` URL”，且要求 IPv6 时写成 `https://[IPv6]/core-api`。`validate-env.sh` 既不校验该变量存在性，也不校验其 URL 语法：手填 `AGENTFORGE_JWT_ISSUER=https://2001:db8::10/core-api`（漏方括号）会通过前置检查，直到应用按 issuer 解析时才表现为非法 URI。属于操作者误录场景，故为建议而非必须。
- **Suggested Fix**：

```bash
issuer_host="${AGENTFORGE_JWT_ISSUER#https://}"
issuer_host="${issuer_host%%/core-api}"
# 域名/IPv4 为普通 host，IPv6 必须带方括号
[[ "${AGENTFORGE_JWT_ISSUER}" =~ ^https://(\[[0-9A-Fa-f:]+\]|[A-Za-z0-9.-]+)/core-api$ ]] || {
    echo "AGENTFORGE_JWT_ISSUER must be a valid https URL (IPv6 needs brackets)." >&2
    exit 1
}
```

---

## 主开发 (Codex) 评估回填区

> 由 Codex 在确认/修复后回填，Pi 本轮不填写。

| Issue ID | 主开发处理结论（确认 / 已修复 / 不采纳及理由） | 相应代码修改说明 | 状态 |
|---|---|---|---|
| FORGE-06 | 记录为后续多虚拟主机场景加固，不扩大本次最小变更。当前每端口仅一个 server 块，仍是默认路由；用户要求的 Nginx 验收覆盖 IPv4、根域名和已带 www。 | 无代码修改；若未来增加同端口虚拟主机，再派生带方括号的 Nginx IPv6 名称并增加匹配测试。 | 已评估，非阻塞 |
| FORGE-07 | 记录为手工环境文件加固建议。本次生成器已保证 IPv6 issuer 正确，运维文档也明确手填格式。 | 无代码修改；避免把 TLS 兼容任务扩大为完整环境变量一致性校验。 | 已评估，非阻塞 |

**附注**
- 本轮共 2 项建议、0 项必须，均基于 diff 内可证实证据；未提供证据范围的未来优化与风格类内容未列入。
- 未执行任何命令、未修改任何文件与 Git 状态，符合只读审查职责。
- 依据固定模型要求，本轮以 `deepseek/deepseek-v4-pro` 完成审查。
