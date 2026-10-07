# 审批历史重放修复合并 main

- 日期：2026-10-07
- 状态：Implemented
- 风险：L3；影响域 Agent Service / Core API 恢复契约 / Docs。
- main 基线：ab94cb8c7d2f2f43b5a652e21d1b1f8fb764452b。
- 已审核来源：b4352c9b3a4bfd370489c5f6d80ec97f3b995122，origin/codex/fix-action-replay-history。
- 合并候选分支：codex/merge-action-replay，复用本聊天干净工作树。

## 目标与范围

用户明确授权“开始审核合并”。main 仍是来源的直接祖先，来源本地与远端一致；候选保留修复提交全部历史，再补本记录和变更索引，以非 force fast-forward 更新 origin/main。不重写来源提交，不部署生产，不开启新 Node。

目标行为沿用 ADR-0043、agent-runtime 与 Agent Service API：只读重放严格匹配的旧轮 RESUMED 事实，不影响当前轮，Java 继续独占审批和业务写入。目标文档已随来源提交完成；本集成仅更新交付记录及索引，不修改实现、契约或依赖。

## 验证与审核计划

先按 origin/main..HEAD 规划 L3 门禁；重新执行当前候选 Python 全量 pytest、Java clean verify、Java/Python 真实跨进程契约。仅受影响范围回归，不把本次称为全发布 Gate。保存 warning、条件跳过与清理限制。

Codex 复核本次 34 行生产改动及测试；来源同一生产 diff 已获本聊天授权的 deepseek/deepseek-flash Milestone PASS，无阻塞修复或实现变化，因此遵循“仅真实阻塞修复后复审”，不重复向 Pi 发送相同代码。本次新增纯文档执行差异/链接/敏感扫描。

完成验证、清理、扫描并回填后创建合并证据提交，提交后不再改文件。再次 fetch 确认 origin/main 仍为预检 SHA，仅非 force 推送 HEAD:main；任何意外历史或推送失败立即停止。以 ls-remote 核验最终 SHA。

## 验证回填

### Preflight 与范围

- fetch 后 main 为 ab94cb8、来源为 b4352c9，与预检一致；`git merge-base --is-ancestor origin/main HEAD` exit 0，本地/远端来源无差异。隔离工作树开始时无 staged/unstaged/untracked 改动；根工作树用户文件未触碰。
- 根目录 `.\scripts\validation\plan-change-gates.ps1 -BaseRef origin/main -TargetRef HEAD -Milestone -Json`：exit 0，L3、AgentService/CoreApi/Docs、Milestone；fingerprint `6379a2720722754b602052ff9c83bbdd1ca7a58141c30c48bbbb74beef094c47`。
- Codex 重新核对生产 diff、测试、ADR-0043 和 Pi 报告，无新增阻塞项。`git diff b4352c9 -- services apps infra scripts --exit-code` exit 0；已审生产输入完全一致，未扩大实现。
- GitHub API 查询来源提交：Secret scan [37551604330](https://github.com/zhan-yi-ming/AgentForge/actions/runs/37551604330)、Core API CI [37551604346](https://github.com/zhan-yi-ming/AgentForge/actions/runs/37551604346) 均 completed/success。

### 本轮机器验证

- Python 工作目录 `services/agent-service`，命令：`python -m pytest -q -p no:cacheprovider --basetemp ../../.data/action-replay-merge/pytest`，使用原 venv 依赖、显式 PYTHONPATH 为当前候选 src，PYTHONDONTWRITEBYTECODE=1、NO_PROXY=127.0.0.1,localhost。exit 0，246 passed、0 failed/error/skipped、5 warnings，75.94 秒。warnings 为 AnyIO BlockingPortal、Starlette 422（3 例）和 Pydantic ReadOnly 兼容提示。
- Java 工作目录 `services/core-api`，命令：`.\mvnw.cmd -B clean verify`。本轮日志 BUILD SUCCESS，3 分 14 秒；Surefire XML 总计 242 tests、0 failures/errors、12 条件式 skipped。返回会话句柄在用户续作时已失效，按本轮完整日志与新建 XML 核验成功，不引用旧构建或历史报告。
- Java 保留 JDK/ByteBuddy 警告、无效 MCP 输入测试的预期校验日志；已知隔离测试容器退出后，缓存 Spring 上下文的调度器和连接池仍有 closed-connection/timeout 日志，未导致测试断言失败。本次不混入其生命周期修复。
- 工具沿用并核对：Python 3.14.3、OpenJDK 21.0.12.1、Maven 3.9.11、Docker 29.5.3、Git 2.23.0.windows.1、Gitleaks v8.30.1。

### 跨进程环境恢复

- 首次根目录命令 `.\scripts\validation\v3-release-regression.ps1 -Only java-python-contracts` exit 1：Docker Desktop 进程已停止，Linux engine named pipe 不存在，专用 PostgreSQL 尚未创建即失败；不是产品测试失败。该失败尝试不作为通过证据，清理连接也因引擎停止不可用。
- 只读确认本机 Docker Desktop 未运行后，以现有安装的隐藏窗口启动恢复，无安装或配置变化；`docker version --format '{{.Server.Version}}'` 返回 29.5.3。
- 在完全相同候选、同一显式 PYTHONPATH 和测试环境下重新执行 `.\scripts\validation\v3-release-regression.ps1 -Only java-python-contracts`：exit 0，runner PASS_PARTIAL（仅指定阶段）；当前 Surefire XML 核验 HTTP 9、Graph Resolution 3、Graph API 1，共 13 tests、0 failures/errors/skipped。保留 JDK/ByteBuddy 兼容警告。

### 收口与交付

- Runner 已移除专用 PostgreSQL 容器、网络和数据卷，并关闭 Python 子进程；按本轮 Compose project 标签复查无残留。Java `mvnw -B clean` exit 0；本轮临时日志和 pytest 目录已清理，临时 .venv Junction 已仅删除链接，原环境仍存在。
- 最终仅本记录和索引新增文档改动；实现保持来源提交一致。累计 diff 的 Gitleaks v8.30.1 扫描 51.62 KB 无泄漏、diff check exit 0，暂存范围确认为本记录与索引两份文档；随后按上述 main SHA 门禁非 force 推送并核验远端。远端结果以本提交及聊天交付记录为准，提交后不修改记录。
- 限制：本轮覆盖受影响 Java/Python 范围；不是全仓 Release Gate，不包含部署。Java 常规套件的 12 条件跳过已披露，其中本次相关 13 项另以真实跨进程模式通过；未单独启动不受本次影响的 Repository 跨进程测试。
