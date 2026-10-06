# R01–R18 合并 main

- 日期：2026-10-06
- 状态：Implemented
- 风险：L3 / Release Gate
- 目标分支：main
- 预检 main：ff1a0a52317c69dba3d51e61e12e56dbf46f2bca
- 来源分支：codex/main-review-fixes
- 预检来源：0f2007c552260fc5fcf6831361f17000c3507b4c

## 背景与目标

R01–R18 已在独立分支逐项核实、修复、测试、只读审核、提交并推送；累计审计未发现经复现确认的严重问题。用户现明确授权把这些提交合并到 main。

本次集成不改写既有提交，不增加产品行为，只把经过审计的来源历史纳入 main，并在隔离工作树上重新执行当前任务的 Release Gate、敏感扫描和 Pi Milestone Review。历史测试只作定位，不能代替本次合并候选的当前机器证据。

## 范围

- fetch origin 并锁定 origin/main 与 origin/codex/main-review-fixes 的精确 SHA。
- 在不触碰根工作树用户改动的隔离工作树中，将 main fast-forward 到来源分支。
- 运行 plan-change-gates、完整 V3 Release Regression、数据库角色、备份恢复与 ASR/Nginx 门禁。
- 清理本轮创建的容器、网络、卷、Junction 和构建生成物。
- 对最终 main 候选执行 diff check、Gitleaks 与 deepseek/deepseek-flash Milestone Review。
- 回填证据、创建独立集成证据提交，再次 fetch；仅当 origin/main 仍为预检 SHA 且是候选祖先时，以非 force 方式推送 HEAD:main，并用 git ls-remote 核验。

## 非目标

- 不 rebase、squash、重写或 force push R01–R18。
- 不修改 R01–R18 的生产实现、迁移、测试或历史审计结论。
- 不触碰根工作树已有 staged、unstaged、untracked 文件。
- 不部署生产、不移动稳定标签、不开始 R19。

## 回滚与停止条件

- 合并候选在推送前可直接丢弃隔离工作树；origin/main 不受影响。
- 若 origin/main 在最终 fetch 后偏离预检 SHA、无法 fast-forward、测试失败、敏感扫描命中或 Pi 出现经确认的严重阻塞项，停止推送并报告。
- main 推送后如需回滚，使用新的显式 revert 提交，不重写远端历史。

## 验证回填

### Git preflight 与候选

- 根工作树位于 `codex/v3-full-impact-audit`，保留其既有 modified/untracked 内容；全部集成操作在独立 `codex/r01-r18-main-integration` worktree 完成。
- fetch 后锁定 `origin/main=ff1a0a52317c69dba3d51e61e12e56dbf46f2bca`，来源修复历史为 `origin/codex/main-review-fixes=0f2007c552260fc5fcf6831361f17000c3507b4c`；文档先行计划提交后来源分支为 `54a28105075759c1c1627d7b2363b9833aad47eb`。
- `git merge --ff-only origin/codex/main-review-fixes` 成功；`origin/main` 是候选祖先，未 rebase、squash 或重写 R01–R18。

### Gate 规划

- 命令：`.\scripts\validation\plan-change-gates.ps1 -BaseRef origin/main -TargetRef WORKTREE -Milestone -ReleaseGate -Json`
- 结果：exit 0；风险 `L3`，Review `Milestone`；影响域 Agent Service、Core API、Deployment、Docs、Governance、Release、TLS、Web 与需人工确认的 validation scripts。
- 最终 change fingerprint：`954e47d5f18ce6185cebb90977c8d31d99220cc4bcc678268d2dbf00f5eeec67`。

### 当前机器 Release Regression

- 环境：Git 2.23.0.windows.1；Docker Desktop client/server 29.5.3；OpenJDK 21.0.12.1；Maven 3.9.11；Python 3.14.3；Node 24.14.0；npm 11.9.0。
- 命令：在候选根目录设置候选 `services/agent-service/src` 为 `PYTHONPATH`、设置 `NO_PROXY=127.0.0.1,localhost`，运行 `.\scripts\validation\v3-release-regression.ps1`。
- 最终结果：exit 0，`V3 Release Regression PASS: 4 stage(s) completed`；其内 V2 Release Regression 11 阶段通过。
- Core API：clean verify 242 tests，0 failures，0 errors，12 个条件式 skip；Flyway V1–V18 在新 PostgreSQL 上应用成功。被条件控制的跨进程范围随后由 V3 runner 显式执行：Java↔Python 13/13、Repository citation 1/1，均 0 skip。
- Agent Service：233 passed，5 warnings；warning 为 anyio BlockingPortal、Starlette 422 常量和 Pydantic TypedDict 兼容提示，无失败或跳过。
- Web：6 files / 86 tests 通过；production build 295 modules 通过。npm 另报告未知 user config `home`，不影响命令退出。
- 跨服务：RAG、Tool/HITL、restart/resume、完整 Compose acceptance、Graph/Entity Resolution、Repository JSON/SSE citation 全部通过；Evaluation 为 faithfulness 0.833333、Hit Rate/MRR/Recall 0.75、Task Success/Tool Selection 1.0。
- 前三次 runner 未作为成功证据：首次因隔离 worktree 缺少测试 basetemp 父目录导致 Python 218 passed 后 15 setup errors；第二次补齐该目录后 Java 242 与 Python 233 通过，但缺少本轮安装的 `node_modules` 导致 `vitest` 不可用；第三次 Java/Python/Web 均通过，但未显式指向候选 `PYTHONPATH`，Junction venv 解析到原工作树旧 editable 源码，Tool/HITL 返回 409。补齐候选 `PYTHONPATH` 后第四次完整重跑通过，证明第三次为环境来源污染而非产品缺陷。

### 专项门禁

- `.\scripts\validation\database-role-boundary.ps1`：exit 0；Core business DML、Agent RAG snapshot/chunk/checkpoint 权限通过，Agent business writes 与 public DDL 被拒绝。
- `.\scripts\validation\backup-restore-contract.ps1`：exit 0，Backup/restore CLI contract PASS。
- `.\scripts\validation\asr-nginx-rate-limit.ps1` 首次真实红灯：Nginx `-t` 成功后 Alpine 报 `set: illegal option -`。最小复现和逐项假设排除确认 PowerShell here-string 的 CRLF 被原样传入 `sh -c`；先更新测试策略，再只在执行边界规范化 LF。原始 60 秒公共 CLI 门禁在修复承载 worktree 和最终候选各完整重跑一次，均 exit 0：双 ASR 会话与普通 API 持续可用、普通 API burst 被拒绝、ASR 随后仍可用。

### 依赖审计、清理与待办

- `npm audit --json` 返回既有 4 项：1 moderate、1 high、2 critical；依赖路径均为 `vitest/jsdom/vite` 开发测试链，`npm explain` 标记为 dev，且候选相对 `origin/main` 未改 `package.json`/lockfile。修复建议要求 Vitest semver-major 5.0.3，故不在本次 fast-forward 集成中扩张范围；作为非生产、非本批引入的后续依赖维护风险记录。
- 已精确删除本轮创建的 `.data`、`node_modules`、Web build/tsbuildinfo、三个专用全栈镜像；专用容器、网络、卷不存在。工作树 Junction 已删除，原仓库 venv 仍存在。
- Gitleaks v8.30.1 已扫描 `origin/main..WORKTREE` 完整累计 diff 约 962.82 KB，exit 0，`no leaks found`。

### Pi Milestone Review

- 十秒预检确认精确模型 `deepseek/deepseek-flash` 可用。通过临时 Git index 排除重复历史变更记录/旧报告，只发送已扫描的 133 个实质文件、当前集成记录、累计审计摘要与系统概览；Pi 只读，不运行测试、不修改文件。
- 本地上下文跟踪校验首次在发送前失败，补入累计审计摘要后仍以 Attempt 1 正式执行；正式结果 `PASS`，报告为 `docs/08-reviews/2026-10-06-review-r01-r18-main-integration-attempt-1.md`。
- 无必须修改项。S-01/S-02 是 fail-closed 会话幂等边缘建议，未复现数据破坏；S-03 为风格；S-04 为额外测试覆盖；S-05 为非安全 UI 文案。五项均记录为后续建议，不符合本批只修严重可复现问题的阈值，因此不修改实现、不触发 Attempt 2。N-01–N-07 与代码和当前机器门禁一致。

### 提交与远端

- 完整暂存候选执行 `git diff --cached --check`：exit 0；Gitleaks v8.30.1 扫描约 981.07 KB：exit 0，`no leaks found`。最终仅包含测试策略、集成记录、Pi 报告和 ASR 验证脚本的最小跨平台修复。
- 证据提交创建后不再修改本记录。非 force 推送与 `git ls-remote` 必须以该不可变提交 SHA 为准；精确远端核验结果在最终交付汇报中给出，若 `origin/main` 偏离预检 SHA 则停止。
