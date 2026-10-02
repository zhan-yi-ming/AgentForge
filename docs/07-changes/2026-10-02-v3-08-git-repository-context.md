# V3-08 Git Repository Context

- 日期：2026-10-02
- 状态：Implemented（机器验证与 Milestone Review PASS；Node Close Gate 待输出）
- Base：`a3d5fe2b6354c4bbd53ae7ce79a2d13790d71a67`；分支：`codex/v3-08-git-repository-context`

## 背景与目标

现有 Agent 能检索 Wiki、Task 与有证据的图关系，却没有项目仓库的入口文档、API 文档、关键配置、目录与近期提交摘要。本节点可选地为部署方显式配置的项目增加只读仓库上下文，使研发问题能引用当前提交中的有限证据。

## Scope 与非目标

由服务端配置 `projectId → repository root`，默认关闭。仅消费当前 HEAD 的已提交 Git tree 与有界 commit summary；仅读 README、指定 API 文档和关键配置白名单。拒绝敏感文件、二进制、符号链接和超出预算的内容；仓库正文经 secret 扫描后作为不可信 Context。仓库候选与 Wiki/Task/Graph 融合，来源身份可由同步与流式 Chat 传到 Web。仓库不可用时保留既有检索。

不克隆远端、不接受用户指定路径/ref、不执行仓库代码或 Hook、不读取 working tree/staged/untracked、不建立完整代码索引、不写仓库、不增加持久化表或跨节点 Release 功能。

## 文档与实现顺序

本记录先行；随后新增 Repository Context 功能文档和 ADR，更新 Context Management、架构、API、开发/部署及路线状态，再对已确认公共 seam 执行 TDD：真实临时 Git 仓库读取边界、`RetrievalService.retrieve`、Agent HTTP 同步/流式、Core Chat 传递及 Web DOM。用户原有的 2026-09-05 记录、`.worktrees/` 与规划 DOCX 不纳入。

## 预判风险与门禁

预判 L3，影响 Agent Service、Core API 公共来源契约、Web、部署和文档。预计执行 Python pytest、Java clean verify、Web Vitest/build、跨服务 Chat smoke、Compose config、文档一致性、diff check、敏感扫描和 Pi Milestone Review。实现后按实际路径重跑规划器并回填真实机器证据。

## 实现与真实验证

- 实现静态部署项目映射，默认无仓库；Git 参数数组只读当前 HEAD 对象，白名单限制根 README、根关键配置、`docs/04-api/` Markdown，tree 最多取 256 KiB 前缀并仅解析前 1000 条完整记录，逐文件最多 16 KiB 前缀，最多 20 文件、20 目录、8 条 commit 和 40 候选；敏感路径/正文、符号链接、二进制和不匹配的仓库根拒绝。Git 不可用降级为空候选。
- Repository 候选以独立 `REPOSITORY` 身份参与 BM25/RRF 与统一 Context 预算；Chat 同步、流式来源经 Java 原结构传递；Web 现有通用来源显示并由 DOM 测试覆盖。可选 Compose 覆盖文件使用只读 bind mount；无迁移和新公开请求字段。
- 门禁规划器以本 Node 24 个路径运行：L3，AgentService/CoreApi/Web/Deployment/Docs；要求 compose-config、cross-process-smoke、diff-check、docs-consistency、gitleaks-final、java-clean-verify、python-test、web-core-contract、web-test；Milestone Review。
- Python：新建 basetemp、禁用旧 pytest cache，`NO_PROXY=127.0.0.1,localhost` 后最终全量 `pytest -q`：199 passed，4 warnings。首次未设置 loopback proxy bypass 时 197 passed/1 failed；隔离复现同一既有 LiteLLM 本机 HTTP 用例失败，加 `NO_PROXY` 后单项与全量均通过。Pi 建议修订后，真实 3000 文件仓库与无效配置均先红后绿，Provider 8/8；全量重跑 199/199。4 条 warning 分别为 Starlette/AnyIO 弃用、HTTP 422 枚举弃用（2 次）、LiteLLM 依赖 TypedDict 提示；原有受限 `.pytest_cache` 未用作证据。
- Java：`mvnw.cmd clean verify -q` 退出 0；fresh Surefire 汇总 201 tests、0 failures、0 errors、10 skipped（条件式/环境性测试）。显式启用 `RepositorySourceCrossProcessTest`，由 Java 客户端调用实际 Python Chat JSON/SSE，1/1 passed。跨进程测试首次断言成功后因 Windows Git object 只读属性导致清理失败；修正测试清理后复跑通过，并清理该测试自己留下的临时仓库。
- Web：最终 `npm test -- --run` 为 6 files/73 tests passed；`npm run build` 退出 0（生产 Web 输入未在后续修订中变化，复用本次构建证据）。npm 报告旧 `home` 用户配置未来弃用的 warning，不影响构建。
- Compose：使用虚构项目 UUID 与本机仓库路径执行 `docker compose ... config --quiet` 退出 0；最终解析结果确认唯一 `/app/repositories/project` mount 为 `read_only: true` 且映射仅含 `projectId,path`。
- TDD：Provider、白名单/HEAD、敏感父目录、大文档前缀与降级、Retrieval 公共 seam 均先观察红灯再做最小实现；Agent HTTP、Core JSON/SSE 与 Web DOM、真实跨进程 smoke 形成引用链验证。无 V3-09 实现。

## 收口证据与限制

最终只暂存本 Node 的 25 个路径（含 Pi 报告）；`git diff --cached --check` 退出 0，14 份受影响文档的相对 Markdown 链接检查通过。暂存新增行的私钥、云/API Token、密码和敏感路径本地模式扫描 0 命中；本机 `gitleaks` 不可用，不能声称执行了该工具。Pi 输入仅为经过扫描的本 Node staged diff、必要路线/协议与结构化测试摘要。经绝对路径校验后清理 `.data` 内 20 个本 Node 临时 pytest 目录，余 0；用户已有 2026-09-05 记录、`.worktrees/` 和 DOCX 保持原样、不暂存。

仓库上下文只覆盖 HEAD 白名单的有界前缀，大仓库排序靠后的文档可能漏召回，secret 模式无法保证识别所有敏感资料。每次读取多次串行 Git 命令，无整体检索 deadline；慢盘可能让请求超过上游超时，部署方应只绑定已安全审查且性能可接受的仓库。历史来源通用 JSON 存储未作 REPOSITORY 专项回读验收。V3-09 Release Gate 需评估这些非功能/端到端边界，本 Node 不宣称完成 V3-09。

## Pi Review 后的目标文档修订

Pi `deepseek/deepseek-flash` 对本 Node 暂存 diff 执行一次只读 Milestone Review，Attempt 1 PASS、无阻断项；[报告与逐项判断](../08-reviews/2026-10-02-review-v3-08-git-repository-context-attempt-1.md)。Codex 核对后修复大型 tree 整体空结果和无效 `displayName` 配置，收窄历史回答专项声明，回填 V3-07 已 PASS 的路线状态，强化 Web DOM 断言及 `_git` stdout 检查。整体 Git deadline、更多异常分支测试作为后续建议；受影响 Python/Web/跨进程/Compose 已重跑，Java clean verify 和 Web build 的相关输入未变、复用本次通过证据。因 Pi 原判 PASS 且没有阻断项修复，不进行第二轮审核。
