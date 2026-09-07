# Demo 登录热修

## 状态

Implemented

## 背景

生产环境使用配置中的固定 Demo 账号登录返回 401。登录页还直接展示并可一键填入账号密码，不符合当前面试体验要求。现有 `seed-demo-v12.sh` 在固定账号已存在但密码变化时，会在登录失败后尝试重复注册，无法把已有账号同步为当前配置密码。

## 范围与目标

- 登录页不显示、也不内置固定邮箱和密码，只提示“账号是简历上的邮箱，密码是微信号”。
- 登录失败时统一展示“请联系我 向我索要体验账号”，不暴露账号是否存在或后台错误细节。
- 固定 Demo 账号重复初始化时，以部署环境配置为准同步密码，并继续复用原账号和 workspace。
- 随机备用账号、生产注册关闭、项目隔离、配额、TLS 与目录结构保持不变。

## 影响与风险

改动影响 Web 登录页和生产 Demo 初始化脚本。固定账号密码同步属于受部署脚本控制的定向维护行为，只允许处理 `AGENTFORGE_DEMO_FIXED_EMAIL` 指定的普通 USER；不增加公开重置接口，也不改变登录 API 契约。

## 验证结果

- `npm test -- --run tests/app.test.tsx`：退出码 0，1 个测试文件、14 个测试通过，0 失败、0 跳过；实现前同一命令得到 3 个预期失败。
- `npm run build`：退出码 0，TypeScript 与 Vite 生产构建通过，283 个模块完成转换。
- `pwsh -NoProfile -File scripts/validation/v2-prep-demo-experience.ps1`：退出码 0；实现前新增 seed 契约按预期失败。
- `bash -n scripts/deploy/seed-demo-v12.sh`：退出码 0。
- 发送 Pi 前定向扫描暂存差异，未发现固定账号明文、私钥或高风险凭据模式；本机未安装 `gitleaks`，已如实记录，Pi 入口的内置敏感扫描亦未阻止审核。
- Pi `deepseek/deepseek-v4-pro` Diff Review Attempt 1：PASS；建议项评估记录见对应审核报告。
- 生产发布前数据库备份：`/opt/agentforge/backups/agentforge-20260907T072112Z.dump.gz`。
- 生产部署提交：`52b2facfb513d084640b8384fea655073c12fd85`；服务器到 GitHub 443 超时后，使用本机创建并验证的单提交 Git bundle 经 SSH 上传，服务器执行 `git fetch` 与 `git merge --ff-only`，未改写历史。
- 生产验证：正确凭据登录返回 200、错误凭据返回 401；Web 容器产物不含固定邮箱或密码明文，新登录提示存在；gateway、web、core-api、agent-service、postgres 均恢复运行，最终 gateway 为 healthy。
- 工具版本：Node.js `v24.14.0`、npm `11.9.0`、PowerShell `7.6.5`、Git `2.23.0.windows.1`、生产 Docker Compose `5.5.1`。

按用户指令，本次不运行 Java、Python、Web 或 E2E 全量测试。

## 回滚

回滚完整热修提交并仅重建 Web 即可恢复旧页面；固定账号密码如需回滚，应在服务器配置恢复旧值后再次执行固定账号同步。数据库账号与 workspace 不删除。

## 已知限制

- 固定密码会在 `psql --set` 进程参数中短暂可见；当前为单管理员服务器上的受限 Demo 凭据，风险可接受，不适用于真实用户或管理员密码。
- 本次按用户要求采用定向验证，没有运行 Java、Python、Web 或 E2E 全量测试。
