# Pi 代码审查报告：demo-login-hotfix / Attempt 1

- 日期：2026-09-07
- 审查阶段：demo-login-hotfix
- 审查对象：INDEX@dce5389（基线：dce53890778363301cc5f391b998622fdc08e73f）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：600 秒

---

REVIEW_RESULT: PASS

## 概述与总体结论

本次 diff 是 `demo-login-hotfix` 阶段（第 1/3 轮）的文档先行 + 部署脚本修复提交：更新三份功能/变更文档，并在 `scripts/deploy/seed-demo-v12.sh` 中新增固定 Demo 账号密码同步函数。仅依据给定 diff 与文件清单审查，未发现具备“明确证据”的必须修改问题。

种子脚本整体逻辑正确：

- 新增的 `synchronize_fixed_account_password` 在登录前以部署配置为准同步密码，修复了“旧密码过期 → 登录失败 → 重复注册 → 409”的原有缺陷；
- 使用 psql 参数化变量（`:'demo_password'`）避免 SQL 注入，密码含单引号也能安全处理；
- `AND role='USER'` 限制避免误改管理员账号，是合理的安全控制；
- 同步后再走 `authenticate_or_register`，已存在账号登录成功即不再注册，保持幂等。

结论：**可交付，不阻塞**。以下列出建议修改项，供 Codex 在后续轮次合入 Web 实现与测试时一并处理。

---

## 详细发现清单

### 必须修改

无。

### 建议修改

| ID | 严重级别 | 文件 | 行号（近似） | 核心问题 |
|----|---------|------|-------------|---------|
| S1 | 中 | scripts/deploy/seed-demo-v12.sh | 新增函数 34–42 / 调用点 95–96 | 直接写 `{bcrypt}` 哈希依赖应用 Encoder 格式一致，脚本未验证且失败模式具破坏性 |
| S2 | 低 | scripts/deploy/seed-demo-v12.sh | 新增函数 34–42 | 密码通过 `psql --set` 命令行参数传递，短暂出现在进程列表 |
| S3 | 低 | scripts/deploy/seed-demo-v12.sh | 新增函数 34–42 / 调用点 95–96 | 依赖 `pgcrypto` 扩展与 `POSTGRES_USER` 权限，未文档化；同步结果缺少显式失败检查 |
| S4 | 低 | docs/07-changes/2026-09-07-demo-login-hotfix.md | 30–32 | 回滚说明“仅重建 Web 即可恢复旧页面”假设本提交包含 Web 代码，但本 diff 只有文档与脚本 |
| S5 | 低 | docs/07-changes/2026-09-07-demo-login-hotfix.md | 24–28 | 变更记录声称的 Web Vitest、种子脚本契约测试在本 diff 中无证据 |

### 无需修改

| ID | 文件 | 说明 |
|----|------|------|
| N1 | scripts/deploy/seed-demo-v12.sh | psql `:'var'` 参数化引用安全，不存在 SQL 注入 |
| N2 | scripts/deploy/seed-demo-v12.sh | `AND role='USER'` 过滤是正确的安全控制 |
| N3 | scripts/deploy/seed-demo-v12.sh | 同步后再登录，已存在账号不重复注册，幂等性正确 |

---

## 逐个 Issue 展开

### S1 - BCrypt 哈希格式依赖应用 Encoder，未验证且失败模式具破坏性

- **Severity**：建议修改（中）
- **File & Line**：`scripts/deploy/seed-demo-v12.sh` 新增 `synchronize_fixed_account_password`（约 L34–42）
- **Evidence**：

```sql
UPDATE app_user
SET password_hash = '{bcrypt}' || crypt(:'demo_password', gen_salt('bf', 10)),
    updated_at = now()
WHERE email=lower(:'demo_email') AND role='USER';
```

- **Description**：该 SQL 假定应用侧使用 Spring `DelegatingPasswordEncoder` 且存储格式为 `{bcrypt}$2a$...`，并假定 pgcrypto 的 `$2a$` 输出与 Java `BCryptPasswordEncoder` 互认。若应用实际使用不带 `{bcrypt}` 前缀的原生 `BCryptPasswordEncoder`，或使用 `$2b$/$2y$` 版本，直接 UPDATE 会**覆盖掉原来可用的哈希**，随后 `authenticate_or_register` 登录失败并尝试注册（邮箱已存在返回 409），导致固定账号被“写坏”。本 diff 未提供应用 Encoder 实现的证据，因此列为建议而非必须修改。
- **Suggested Fix**：
  1. 在脚本中读取现有用户的一条 `password_hash`，校验前缀/版本与本次 SQL 产物一致后再执行同步；或
  2. 同步前对旧哈希做备份（如 `SELECT password_hash ...` 存入临时变量，失败时恢复）；或
  3. 理想情况下复用应用自身 BCrypt 逻辑（一次性维护 CLI/管理接口）而非 pgcrypto 重写。

### S2 - 密码经命令行参数传递，短暂暴露于进程列表

- **Severity**：建议修改（低）
- **File & Line**：`scripts/deploy/seed-demo-v12.sh`（约 L36–38）
- **Evidence**：

```sh
--set=demo_email="${email}" --set=demo_password="${password}"
```

- **Description**：密码作为 `psql --set=demo_password=...` 的 argv 传入，执行期间可用 `ps`/`/proc` 观察到。虽然该 Demo 密码在业务上属半公开（微信号），且单管理员服务器风险较低，但既然文档已将其视为“服务器固定凭据、不得进入浏览器”，仍建议避免进入进程列表。
- **Suggested Fix**：改为经文件描述符/临时文件（mode 600）注入并即时清理；或在共享主机场景下文档化该风险为可接受；不建议用未加引号的 heredoc 直接内插 shell 变量（会引入注入面扩大）。

### S3 - pgcrypto 扩展与权限依赖未文档化，同步结果无显式失败检查

- **Severity**：建议修改（低）
- **File & Line**：`scripts/deploy/seed-demo-v12.sh`（约 L34–42 与 L95–96）
- **Evidence**：

```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;
```

```sh
synchronize_fixed_account_password "${AGENTFORGE_DEMO_FIXED_EMAIL}" \
    "${AGENTFORGE_DEMO_FIXED_PASSWORD}"
```

- **Description**：`CREATE EXTENSION pgcrypto` 需要 contrib 包与超级用户/CREATE 权限（官方 postgres 镜像的 `POSTGRES_USER` 默认满足，自托管 Compose 场景通常可用）。若宿主环境使用受限用户或裁剪镜像，此步会失败；同时该调用无 `|| { ...; exit 1; }` 显式处理，如果脚本顶部未启用 `set -e`，失败可能被静默吞掉（首跑场景尤其隐蔽）。
- **Suggested Fix**：
  1. 在变更文档或脚本注释中声明 pgcrypto 与权限前提；或
  2. 给调用加显式失败处理：
     ```sh
     synchronize_fixed_account_password ... || { echo "Fixed account password sync failed." >&2; exit 1; }
     ```
  3. 对 `crypt()` 结果做非空校验（避免环境异常时写入空哈希）。

### S4 - 回滚说明与当前 diff 范围不一致

- **Severity**：建议修改（低）
- **File & Line**：`docs/07-changes/2026-09-07-demo-login-hotfix.md`（约 L30–32）
- **Evidence**：

> 回滚本次提交并仅重建 Web 即可恢复旧页面；固定账号密码如需回滚，应在服务器配置恢复旧值后再次执行固定账号同步。

- **Description**：本提交（INDEX@dce5389）实际只包含文档与 seed 脚本，不含 Web 代码；“回滚本次提交并仅重建 Web”不会改变页面。该回滚说明描述的是完整 hotfix 提交（含后续 Web 变更），与当前阶段 diff 范围不一致。
- **Suggested Fix**：改写为“回滚完整 hotfix 提交（含 Web 变更）并重建 Web…”；或明确标注“本阶段仅文档+脚本，Web 变更将在后续轮次进入，回滚说明针对最终合并提交”。

### S5 - 测试证据缺失

- **Severity**：建议修改（低）
- **File & Line**：`docs/07-changes/2026-09-07-demo-login-hotfix.md`（约 L24–28）
- **Evidence**：

> - Web 定向 Vitest：凭据明文不存在、提示存在、错误账号或密码均显示统一文案。
> - Demo seed 定向脚本契约：已有固定账号可安全同步密码，随机账号仍按原流程注册。

- **Description**：变更记录声明了 Web 定向 Vitest 与 seed 脚本契约验证，但本 diff 中无任何测试文件或 Codex 测试记录。按交接约定，Pi 不代替 Codex 执行测试，仅作为审查证据留档；此处无证据无法验证关键分支（同步密码/错误凭据统一文案）已被自动化覆盖。
- **Suggested Fix**：在进入 `Implemented` 状态前，附上 Codex 的 Web Vitest 结果与 seed 脚本在测试库上的契约验证记录（尤其是 S1 所涉及的哈希格式互认验证）。

---

## 主开发 (Codex) 评估回填区

| 问题 ID | Codex 处理意见（接受/拒绝/说明） | 状态 | 备注 |
|---------|-------------------------------|------|------|
| S1 | 接受验证建议，不改实现 | 已处理 | `SecurityConfiguration` 明确使用 `PasswordEncoderFactories.createDelegatingPasswordEncoder()`；生产 seed 后以真实登录 200 验证 pgcrypto `$2a$` 与 `{bcrypt}` 兼容，若失败则停止发布并从部署前备份回滚。 |
| S2 | 接受并记录限制 | 已处理 | 当前是单管理员服务器的受限 Demo 凭据；不把该方式用于真实用户或管理员密码。 |
| S3 | 接受文档建议 | 已处理 | 功能文档已写明 Compose PostgreSQL、`pgcrypto` 与初始化用户权限前提；脚本已有 `set -Eeuo pipefail`，`psql --set=ON_ERROR_STOP=1` 会使同步失败立即终止。 |
| S4 | 无需修改实现 | 已处理 | 最终热修提交包含 Web 代码；回滚文字已明确为“完整热修提交”。 |
| S5 | 接受并补齐证据 | 已处理 | Codex 已运行 Web 14/14 定向 Vitest、生产构建、Demo 契约与 Bash 语法检查，真实命令和退出码已回填变更记录。 |
