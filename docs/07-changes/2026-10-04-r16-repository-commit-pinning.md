# R16 Repository Context 固定提交快照

- 日期：2026-10-04
- 状态：Implemented
- 风险：L2（只读 Git 证据 provenance 与跨进程契约）
- 范围：Agent Service Repository Context、Repository citation 版本身份、真实 Git/容器契约

## 问题核实

报告问题成立。当前实现先用 `rev-parse HEAD` 保存 revision，却继续以可变字符串 `HEAD` 执行 `ls-tree` 和 `log`；若仓库在这些命令之间快进、回滚或切换分支，文件/提交摘要可能来自新提交，而 `source_id` 与 `revision` 仍声明旧提交。Blob 通过 `ls-tree` 返回的 OID 读取，所以不会混入工作树，但整批来源的版本声明可能错误。

核实最低容器验收时还发现同一影响链上的可运行性缺陷：从当前 `services/agent-service/Dockerfile` 构建的一次性镜像执行 `git --version`，OCI 返回 `exec: "git": executable file not found in $PATH`。因此生产只读 overlay 即使配置正确，Repository Context 也会始终按“Git 不可用”降级为空；本记录将运行时 Git 依赖纳入 R16 最小修复。

## 目标契约与公共 seam

公共 seam 是 `RepositoryContextProvider.retrieve(project_id, query)`。每次调用只解析一次完整 commit ID（当前 SHA-1 仓库为 40 位，SHA-256 仓库可为 64 位），随后 `ls-tree`、blob OID 与 `log` 都从该不可变 commit 派生；整批文件、目录、提交摘要、`source_id` 和 `revision` 必须对应同一提交。HEAD 在调用中途移动不影响本次结果，只影响下次调用。

Agent 生产镜像必须显式安装 Git CLI；Compose 只允许把经批准的单个仓库根挂载为 read-only。既有白名单、secret 过滤、只读 Git 参数数组、输出/候选/Context 上限、staged/untracked 排除与故障降级保持不变。不新增浏览器或模型可控 revision，也不改变 Java 权限/审批/写入边界。

## TDD 与验证计划

1. 在真实临时 Git 仓库建立 old/new 两个提交，测试 seam 在 `rev-parse HEAD` 后移动 HEAD；先证明旧实现返回旧 revision 却读取新 tree/log 的红灯。
2. 最小实现把 `ls-tree` 与 `log` 的 revision 参数替换为已解析 commit，并断言文件内容、commit summary、UUID/revision 整批一致；保留 staged/untracked、白名单、secret 与大树上限回归。
3. 从最终 Dockerfile 重新构建一次性 Agent 镜像，证明 `git --version` 可运行、已提交对象可读且 bind mount 写入被拒绝；同时验证 Compose overlay 解析为唯一 read-only mount。
4. 运行 Agent 全量 pytest 与真实 Repository cross-process 门禁；按规划结果执行 diff check、完整 staged Gitleaks 和 Pi 审核。

## 验证回填

### TDD 与问题证据

- 真实临时 Git 仓库建立 old/new 两个 commit，以参数化用例分别在 `rev-parse HEAD` 后执行快进与回滚。旧实现两例均实际红灯：`revision` 仍是初始 SHA，但 README 内容来自移动后的 HEAD（`2 failed`），与报告描述一致。
- 最小实现只把 `ls-tree` 和 `log` 的 ref 从可变 `HEAD` 换成已解析的完整 revision；blob 继续使用固定 tree 返回的 OID。Repository Context 全文件复跑 `10 passed`，同时证明 staged/untracked 排除、白名单、敏感路径/内容、大文件/大 tree 上限与引用链未回归。
- 首次按最低容器验收从原 Dockerfile 构建一次性镜像，`docker run ... git --version` 在 OCI 启动阶段实际失败：`git` 不在 PATH。补入显式 Git 运行时依赖后重建成功。

### 当前机器绿灯

- 门禁规划：清理测试产物后，`.\scripts\validation\plan-change-gates.ps1 -BaseRef HEAD -TargetRef WORKTREE -Json` 退出 0；`L2`、`AgentService/Docs`、Diff Review，要求 Python、docs、diff 与 Gitleaks；fingerprint `8c55fe90f7c7f154d98074768d2f82c7c82b4334d776d3320c4796749e07fa5b`。
- Agent Service 全量：工作树 `PYTHONPATH`、`NO_PROXY=127.0.0.1,localhost` 与专用 basetemp 下 `226 passed, 4 warnings`、退出 0；warning 仍为 Starlette anyio alias、HTTP 422 常量两项和 Pydantic ReadOnly 提示，无 skip/失败。
- Repository 跨进程：确认工作树目标不存在后临时创建指向主工作树 venv 的 Junction，执行 `.\scripts\validation\v3-release-regression.ps1 -Only repository-context-contract -Json`，状态 `PASS_PARTIAL`、严格 `1/1`；finally 核对 LinkType 后只删除 Junction，源 venv 完好。
- 容器运行时：最终 Dockerfile 构建一次性 `agentforge-r16-validation:local` 镜像成功；容器内输出 `git version 2.47.3`，通过只读 bind mount 读取 40 位 HEAD，并且写探针得到只读拒绝。首次直接挂载当前附加 worktree 时，Linux 容器无法解析 `.git` 文件中的 Windows 主仓库绝对路径；改用契约要求的标准 checkout（`.git` 目录）后通过，这一环境限制没有伪装为产品失败。
- Compose overlay：用非敏感虚构项目 UUID 渲染基础 Compose + `infra/compose.repository-context.yaml`，退出 0；解析结果为唯一 `/app/repositories/project` mount、`read_only=true`，绑定 JSON 仅一个项目且 path 指向该挂载点。
- 清理：三个 R16 pytest basetemp 已在根路径和名称白名单检查后删除；临时 Junction、容器均不存在；一次性镜像已删除。容器 smoke 使用 `--rm`，未创建网络或卷；Compose 仅执行 config。
- 未运行 Core 全量 clean verify、Web 或数据库套件：R16 不改 Java/Web/数据库源码或公共 HTTP schema；严格 Repository cross-process 1/1 已覆盖 Java JSON/SSE 客户端与真实 Python/Git 引用链，范围与 L2 规划一致。

### Pi 与提交前收口

Pi Attempt 1 Diff Review 返回 `PASS`，报告为 `docs/08-reviews/2026-10-04-review-main-review-r16-attempt-1.md`，无必须修改项。S-1 关于 dubious ownership 的担忧不成立：`RepositoryContextProvider._git` 每次都在参数数组中显式传入 `-c safe.directory=<精确 root>`，不是依赖容器全局配置。S-2 是目录/staged 组合覆盖补强，现有独立测试已覆盖这些契约，不为纯建议扩大本批。S-3 的状态在收口时统一为 Implemented，并把“40 位”修正为兼容 SHA-1/SHA-256 的完整 commit ID 表述。S-4 不采纳 apt 精确版本 pin：基础镜像仓库只保证当前可用安全版本，固定发行版补丁号会使未来安全更新或镜像重建失败；本次保留构建时实际 Git 版本证据。

最终 index 包含 Pi 报告与上述处置后，`git diff --cached --check` 退出 0；完整 staged diff 使用 `zricethezav/gitleaks:v8.30.1 detect --pipe --no-banner --redact --exit-code 1` 扫描，退出 0、`no leaks found`。最终扫描后不再修改 R16 内容。

## 风险与回滚

固定 commit 参数若传递遗漏，会继续产生混合证据；测试须同时观察内容、log、revision 和 UUID。回滚本提交恢复旧行为，不涉及数据库、公共 HTTP schema 或业务数据迁移。
