# TLS 公网 IP 与域名兼容

- 日期：2026-09-06
- 状态：Implemented
- 阶段：V2-01 后生产部署修正
- 交付分支：`codex/tls-public-host-compat`

## 背景

生产环境已把 `PUBLIC_HOST` 从公网 IP 切换为域名。当前首次证书脚本固定使用 Certbot `--ip-address`，环境生成与校验也只接受 IPv4，临时自签证书固定写 `IP:` SAN；因此域名模式无法完成首次部署。Nginx 只声明一个 server_name，也没有覆盖根域名对应的 `www` 入口。

## 目标与范围

- 根据 `PUBLIC_HOST` 自动区分 IPv4、IPv6 与 DNS 域名。
- IP 使用 `--ip-address`；域名使用 `-d`，普通根域名同时加入动态 `www.` 名称，已带 `www.` 时不重复。
- 显式固定 Certbot cert-name 为 `PUBLIC_HOST`，继续使用 `/opt/agentforge/tls/letsencrypt/live/${PUBLIC_HOST}`。
- 保持 `/opt/agentforge/tls/{letsencrypt,acme,current}`、`tls-sync.sh`、`tls-renew.sh` 与 systemd timer 架构。
- Nginx 使用从 `PUBLIC_HOST` 派生的可选 `PUBLIC_WWW_HOST`，IP 和已带 `www` 的域名为空。
- 环境生成、环境校验和临时自签证书同步兼容 IP/域名。
- 不硬编码当前域名，不修改证书存储架构，不引入新的长期运行组件。

## 公共 seam 与场景

用户已在需求中确认以下公共 seam：

- `tls-issue.sh` 产生的最终 Certbot CLI 参数与退出码。
- `tls-sync.sh` 从稳定 live 目录同步两个证书文件，并通过 Compose reload gateway。
- `tls-renew.sh` 先执行 `certbot renew`，随后调用同步脚本。
- Nginx 模板经过 Compose 环境渲染后的两个 `server_name`。
- 生产环境校验与生成脚本对 IP/域名的接受或拒绝结果。

代表场景至少覆盖 IPv4 `47.76.95.86`、根域名 `example.com`、已带 `www` 的 `www.example.com`，并验证无 `www.www`、稳定 cert-name/live 路径、续期同步、Compose config 与 Nginx `-t`。

## 计划实现

- `scripts/deploy/common.sh`：集中主机类型判断和 `PUBLIC_WWW_HOST` 派生，供证书、Nginx 与校验复用。
- `scripts/deploy/tls-issue.sh`：按类型组装 Certbot 参数并指定稳定 cert-name。
- `scripts/deploy/init-tls.sh`：按 IP/DNS 生成临时 SAN。
- `scripts/deploy/tls-sync.sh`、`tls-renew.sh`：保持流程，使用默认 TLS 根目录并允许隔离测试目录。
- `scripts/deploy/generate-production-env.sh`、`validate-env.sh`：接受合法 IP 或域名。
- `infra/compose.prod.yaml`、`infra/nginx/production.conf.template`：向 gateway 传入派生的可选 www 主机并渲染 server_name。
- `.env.production.example` 与生产运维文档：说明两种模式和首次申请/自动续期命令。
- 新增公共脚本契约测试，通过隔离目录和替身命令观察外部行为，不测试内部函数。

## 风险、验证与回滚

风险等级 L2，影响域为 TLS / deployment。主要风险是证书申请成功但同步目录不一致、域名列表重复、IP 回归或 Nginx 环境未正确传递。执行 ShellCheck/parser、TLS 公共契约、Compose 渲染和 Nginx 配置校验；不运行未受影响的 Java、Python、Web 产品测试。实现与定向测试完成后执行 Gitleaks 和 Pi Diff Review。

回滚时恢复上述脚本、模板和文档即可；证书目录、现有证书、renewal 配置、systemd timer 与业务数据均不迁移或删除。

## 实际实现

- `common.sh` 新增 IPv4/IPv6/域名判断、域名格式拒绝、`PUBLIC_HOST_TYPE` 与可选 `PUBLIC_WWW_HOST` 派生；Compose helper 每次调用前加载并导出派生值。
- `tls-issue.sh` 显式使用 `--cert-name "${PUBLIC_HOST}"`。IP 保留 `--preferred-profile shortlived --ip-address`；域名改用一个或两个 `-d`。
- `tls-sync.sh` 继续从 `live/${PUBLIC_HOST}` 复制到 current 并 reload gateway；`AGENTFORGE_TLS_ROOT` 只提供隔离测试覆盖，生产默认目录保持 `/opt/agentforge/tls`。
- `tls-renew.sh` 使用 `certbot renew --cert-name "${PUBLIC_HOST}"`，避免域名迁移后旧 lineage 的失败阻断当前证书同步；renew 成功后仍调用 `tls-sync.sh`。
- `init-tls.sh` 为 IP 写 `IP:` SAN，为域名写一个或两个 `DNS:` SAN。
- 环境生成与校验接受 IPv4、IPv6 和合法 DNS 名称，拒绝包含协议或路径的值。
- IPv6 `PUBLIC_HOST` 在 Certbot 中保持裸地址，在 URL 中派生为带方括号的 host，确保生成的 JWT issuer 符合 URI 语法；结构校验拒绝重复 `::`、组数错误等非法 IPv6。
- gateway 接收运行时派生的 `PUBLIC_WWW_HOST`，Nginx 两个 server 块统一渲染 `${PUBLIC_HOST} ${PUBLIC_WWW_HOST}`。

## 当前验证证据

- TDD 真实红灯依次覆盖：硬编码 TLS 目录、根域名缺少 www、已带 www 重复、renew 卷路径、域名自签 SAN、域名环境校验、域名环境生成、Compose 缺少 www、renew 未定向 cert-name、TLS 入口未拒绝 URL；每项完成最小实现后转绿。
- 一次性 Bash 5.2.37 root 容器执行 `tls-public-host-contract.sh`，退出码 0；覆盖 IPv4、IPv6、根域名、已带 www、cert-name/live 同步、renew/sync/reload、临时 SAN、环境生成/校验与非法主机拒绝。
- `tls-public-host-nginx.ps1` 使用真实 Compose 渲染与 `nginx:1.29-alpine nginx -T`，IPv4、根域名、已带 www 三种场景通过，退出码 0；临时证书与容器均已清理。
- Bash `-n` 检查 8 个受影响脚本返回退出码 0；PowerShell parser 返回 0 errors。
- 现有 `v1-1-production-config.ps1` 返回退出码 0：仍只有 gateway 发布 80/443，5 个服务保持 10 MiB × 3 日志上限，部署脚本未使用 Compose v5 不支持的 build 参数。
- ShellCheck 0.11.0 首次正确入口扫描只报告动态/cross-source 建模误报；明确排除 `SC1090/SC1091/SC2034` 后退出码 0。此前一次命令误把 `-x` 当容器入口、退出码 1且未扫描文件，不作为代码失败。
- Pi 修复后的最终重跑中，ShellCheck 前两次分别因遗漏容器 entrypoint、使用错误容器内相对路径而退出 1，均未扫描文件；改为显式 `--entrypoint shellcheck` 和 `/mnt/...` 路径后实际扫描退出码 0。Windows PowerShell 5.1 曾把 OpenSSL 正常写入 stderr 的进度当成 terminating error，测试退出 1；同一脚本改由 PowerShell 7 执行后退出码 0。
- Gitleaks 8.30.1 在 Pi Attempt 2 前扫描 `HEAD..INDEX` 暂存补丁约 58.93 KB，耗时 79.2 ms；包含最终审核报告后再次扫描约 69.46 KB，耗时 88.7 ms；两次均退出码 0、无泄漏，临时补丁目录均已清理。
- Pi DeepSeek V4-pro Diff Review Attempt 1 返回 `NEEDS_FIX`，确认 IPv6 issuer URL 缺陷；Attempt 2 返回 `PASS`，0 项必须修改。
- 未运行 Java、Python、Web 或产品 E2E：本次没有触及业务源码、依赖、Schema、API 或运行时服务调用。
- 未执行真实公网 ACME 申请和服务器 reload：本机没有生产 `.env`、公网 80 challenge 或服务器 Docker 上下文；由部署者在代码推送后按运维文档执行。

### Pi Attempt 1 处置

- FORGE-01 确认并修复：IPv6 issuer 必须使用方括号；先增加环境生成公共红灯测试。
- FORGE-02 接受：既然部署前置文档承诺合法 IPv6，就补结构校验与非法地址负向测试，不把失败延迟到 Certbot。
- FORGE-03 不采纳：已读取的 `deploy.sh` 全部通过 `compose()` helper 执行 `config/up/build/pull/ps`，helper 会加载并导出 `PUBLIC_WWW_HOST`；现有描述建立在错误前提上。
- FORGE-04 记录限制：本地 fake Certbot 只能验证参数、目录与同步契约，真实公网 ACME 必须在目标服务器完成，已有“未执行项”明确披露。
- FORGE-05 接受文档迁移提示：旧脚本默认 lineage 通常是 IP，但服务器首次更新前应以 `certbot certificates` 核对；不为未证实的异常名称增加自动猜测或回退。

### Pi Attempt 2 处置

- FORGE-06 暂不扩展：当前 80/443 各只有一个 server 块，IPv6 请求即使不按名称匹配也落到该默认 server；本次用户验收的 Nginx 场景为 IPv4、根域名和已带 www。若未来同端口增加虚拟主机，再单独派生带方括号的 Nginx IPv6 名称并补匹配测试。
- FORGE-07 暂不扩展：生成器已经生成正确的 IPv6 issuer，手工 `.env` 的 issuer 与 `PUBLIC_HOST` 一致性校验属于环境契约加固，不扩大本次 TLS 最小变更；运维文档已明确方括号要求。
- Attempt 2 为 `PASS`，无阻塞项；纯建议不触发第三次 Pi 调用。

## 效率样本

这是优化流程后的第一个真实任务。分支从 2026-09-06 23:42 到最终审核通过约 7 小时 56 分自然时间，其中包含用户中断和外部审核等待，不能等同于 Codex 主动执行时间。规划阶段约读取 20 个治理、运维、脚本和模板文件；按有效验证结果计为 12 组 TDD 场景与 7 类最终门禁，无无关产品测试，完整 TLS 合约仅在集成和 Pi 修复后必要重跑。Pi 实际模型调用 2 次；另有 1 次上下文数组在本地参数解析阶段失败，未调用模型。返工仅来自 Attempt 1 发现的 IPv6 URL 与结构校验两个同域问题。在三个样本完成前不宣称达到目标比例。
