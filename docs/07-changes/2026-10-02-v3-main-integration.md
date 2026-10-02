# V3 合并至 main

- 状态：Completed
- 风险等级：L3（Release Gate、跨服务累计变化、主分支更新）
- 目标分支：`main` / `origin`
- 发布分支：`codex/v3-main-release`
- Main 基线：`61626ed17cc7b7d507053fc319543dc043b6034c`
- 已审计 V3 候选：`e385ff78d361085b7c101565ca0deca4cf71c081`

## 背景

V3-01 至 V3-09 和独立全量跨功能影响审计已经分别提交、推送并通过相称门禁，但 `origin/main` 仍停在 V3 前基线。用户明确要求把全部 V3 代码合并到 `main`，并在更新主分支前重新确认没有可复现 Bug、所有 V3 阶段均已处理完成。

Git preflight 已 fetch `origin`：`origin/main` 是已审计候选的直接祖先，领先/落后计数为 `0/12`，没有需要人工解决的双向历史或 merge conflict。当前用户工作区的 9 月记录修改、`.worktrees/` 与规划 DOCX 不在隔离 worktree 中，也不得进入发布提交。

## 目标与范围

- 以已审计提交 `e385ff7` 为发布候选，在隔离 worktree 中追加本次 main 集成证据。
- 对 `origin/main..发布候选` 运行当前机器完整 V3 Release Regression，覆盖 Java、Python、Web、V2 smoke/full-stack 与 V3 条件式跨进程契约。
- 完成最终 diff、文档、敏感信息、资源清理与 DeepSeek Pi V4.1 Flash Milestone Review。
- 先非 force 推送发布分支并核验，再重新 fetch `origin/main`；只有它仍为预检基线且是候选祖先时，才以非 force `HEAD:main` 完成 fast-forward 并核验远端 commit。

## 非目标

- 不新增或修改业务功能、API、Schema、依赖、配置策略或 V3 Node。
- 不进行生产部署，不变更生产数据或凭据。
- 不移动、重写或重新创建 `v3-stable`。
- 不使用旧报告、旧缓存或上一任务的 PASS 替代本次 main 候选验证。

## 受影响文档

- `README.md`：把 V3-09/tag 描述更新为已核验事实，并把当前变更指向本记录。
- `docs/01-product/v2-v3-node-roadmap.md`：记录 V3 已完成且正在执行独立 main 集成，不新增 V3-10。
- `docs/07-changes/README.md`：登记本记录。

## 验证与发布计划

1. 对 `origin/main..WORKTREE` 运行门禁规划器，固定为 L3 Release Gate / Milestone。
2. 执行 `.\scripts\validation\v3-release-regression.ps1`；任何阶段失败都停止 main 更新。
3. 核对条件式 Java 契约必须 13 tests / 0 failures / 0 errors / 0 skips，并检查容器、网络、卷和本轮镜像清理。
4. 对只包含 V3 累计实现与本发布文档的精确视图执行敏感扫描和未截断 Pi Milestone Review；历史报告不重复发送。
5. 回填测试、Pi 与 Close Gate，暂存区只保留本次文档，创建发布提交并推送发布分支。
6. 再次 fetch；若 `origin/main` 与预检基线不同或不能 fast-forward，立即停止。否则非 force 推送 `HEAD:main` 并核验远端 SHA。

## 当前状态

- V3-01 至 V3-09：Implemented。
- V3 Release Gate：PASS；`v3-stable` 已核验指向 V3-09 提交。
- V3 全量跨功能影响审计：未截断 Pi PASS、0 Must-Fix。
- 本次最终 Pi Attempt 2：PASS、0 Must-Fix；Node Close Gate=YES。提交和推送 SHA 由 Git 远端事实与最终汇报提供，不在提交内伪造自指值。

## 首轮候选验证（S-1 修复前）

- 门禁规划：在隔离 worktree 执行 `.\scripts\validation\plan-change-gates.ps1 -BaseRef origin/main -TargetRef WORKTREE -Milestone -ReleaseGate -Json`，exit 0；判定 L3 / Milestone / Release Gate，要求 full-repo regression、Java/Python/Web、跨进程、数据库、Compose/Nginx/TLS、文档、diff 与敏感扫描；fingerprint=`1b27677f5f9ba49bc5aca842dac71bcb24db398cbe02be6abb41ba1ad6e2eb3b`。
- 隔离 worktree 不包含 Git 忽略的 Python `.venv` 与 Web `node_modules`。在 S-1 修复前，运行时目录 `services/`、`apps/`、`infra/`、`scripts/` 相对已审计提交 `e385ff7` 为 0 diff，当时发布候选只增加文档；因此在原工作区同一 `e385ff7` 上重新执行 `.\scripts\validation\v3-release-regression.ps1`，不是复用上一任务日志。该证据在生产代码修复后已失效，最终证据见下文。
- 完整 runner exit 0，4/4 stages PASS。环境为 Eclipse Temurin 21.0.12.1、Python 3.14.3、pytest 8.4.2、Node v24.14.0、npm 11.9.0、Docker client/server 29.5.3。
- Java `clean verify`：201 tests / 0 failures / 0 errors / 11 条件式 skips，BUILD SUCCESS；随后显式执行 8 Chat/Resume + 3 Resolution advisor + 1 GraphRAG + 1 Repository 契约，合计 13 / 0 failures / 0 errors / 0 skips。
- Python：199 passed / 0 failed / 5 warnings；warning 为 AnyIO alias、Starlette 422、Pydantic TypedDict 和 pytest cache ACL。Web：6 files / 73 tests passed；TypeScript/Vite production build 295 modules，exit 0；保留 npm unknown user config `home` warning。
- 跨功能 smoke：RAG Wiki/Task 各 1、删除后 Task chunks 0、跨项目 rows 0；Tool/HITL 重复确认后仅 1 个 Task、update version 1、reject=`REJECTED`、cross-user=403；Restart/Resume 为 `PENDING → EXECUTED` 且 replay 仍 `EXECUTED`、matching task 1、checkpoint 3；Evaluation hitRate/recall@K/MRR=0.75、faithfulness=0.833333、task success/tool selection=1.0；完整栈 Web 200、Core/Agent `UP`、RAG source 1、confirm executed、reject rejected、final tasks 2。
- 清理：runner 创建的容器、网络与卷均已清除；本轮 `d7d6c6fa` 完整栈的 core-api、agent-service、web 三个镜像已按精确名称删除，复核无 `d7d6c6fa`/`8a92529c` 资源残留。

## Pi 输入边界

- `origin/main..INDEX` 的完整统一 20 行上下文 diff 超过默认 180,000 字符。临时 index 把旧节点 `docs/07-changes/` 与 `docs/08-reviews/` 恢复到 main 基线，只重新加入本次发布记录、变更索引和 V3 全量影响审计摘要；全部 V3 生产源码、配置、迁移、测试、runner、架构/功能/API/开发/运维文档保持完整。
- 精确输入为 118 files、674,030 字符，历史记录违规 0；私钥、OpenAI/GitHub key、Bearer、AWS key、JWT 六类规则均 0 命中，`git diff --cached --check origin/main` 通过。
- 为避免静默截断，仅在本次单次调用期间把工作树中的 `run-review.ps1` 上限临时提高到 750,000；脚本不加入临时 index，不进入 Pi 输入或提交，审核后立即恢复为 180,000。

## Pi 首轮结论与定向复现

- DeepSeek Pi V4.1 Flash Attempt 1：PASS，0 Must-Fix。报告为 `docs/08-reviews/2026-10-02-review-v3-main-integration-attempt-1.md`。
- Pi 提出一项中风险建议：启用 LLM 的实体消歧路径可能直接调用 responder 工厂，从而绕过 FastAPI dependency override 与统一生命周期。其余建议涉及 Neo4j 错误归一、runner 计数文案、本地 Nginx 限流和文件结尾，均不构成本次可复现阻塞项。
- 在合入 main 前，针对公共 seam `POST /internal/v1/graph/resolution/suggest` 增加一条启用 LLM 的 HTTP 回归测试：只替换公开 responder 依赖，必须实际消费该 responder 持有的模型并返回候选白名单内建议。先记录红灯，再做最小依赖装配修复；若生产代码变化，前述全量证据失效并重跑完整 Release Regression 与 Pi 复审。

## S-1 复现、修复与最终回归

- 红灯：新增 HTTP 回归测试只覆盖 `get_responder`，接口返回 200，但 `recommendedCandidateId=null`，证明直接函数调用绕过 FastAPI override；候选 JSON、白名单和模型解析由既有测试排除。
- 最小修复：把 responder 工厂原样提取到 `responder_dependency.py`，`api.py` 继续重导出同一函数对象，`resolution_model(responder=Depends(get_responder))` 消费统一注入实例；未改变评分、鉴权、候选白名单、模型错误降级或 Java 写入边界。
- 绿灯：新增测试 1 passed；实体消歧文件 6 passed；按 runner 的 loopback `NO_PROXY` 环境执行完整 Agent Service 为 200 passed / 4 warnings。一次未设置 `NO_PROXY` 的手工运行出现 LiteLLM 本地端点失败，原工作区同测同样失败；确认 runner 会显式设置 localhost 豁免后全量通过，不作为产品缺陷隐藏。
- 最终 `v3-release-regression.ps1` 在隔离候选上从头执行，exit 0、4/4 stages PASS：Java clean verify 201 tests / 0 failures / 0 errors / 11 条件式 skips；Python 200 passed；Web 6 files / 73 tests，production build 295 modules；V2 Release Regression 11 stages PASS。
- 显式条件契约：Chat/Resume 8、Resolution 3、GraphRAG 1、Repository 1，合计 13 tests / 0 failures / 0 errors / 0 skips。完整栈 Web 200、Core/Agent `UP`、RAG source 1、confirm executed、reject rejected、final tasks 2；RAG、HITL、Restart/Resume 与 Evaluation 指标保持基线。
- 隔离环境首轮 runner 因 Git 忽略的 `.data/` 父目录不存在，Python setup 报 13 个 `FileNotFoundError`；创建运行时父目录后从头重跑，不复用前半段 PASS。最终 run ID `def183be`、`6c22d2ca` 的容器/网络/卷均为 0，三张精确 stack 镜像已删除；临时 dependency junction 与 pytest 目录已清理。
- 生产代码已变化，Attempt 1 只作为发现来源；最终候选必须以累计 diff、Attempt 1 为 prior report 执行一次 Pi Attempt 2，结果与 Close Gate 待回填。

## Pi Attempt 2 与 Close Gate

- 临时 review index 从最终候选构造：119 files、681,655 字符，完整保留 V3 生产源码、配置、迁移、测试与目标文档；旧 Node 变更记录/旧 review 文件 0 个。宽松 `sk-` 规则唯一命中 `risk-policy` 文件名上下文，经带 token 左边界规则复扫，私钥、OpenAI/GitHub key、Bearer、AWS key、JWT 均 0。
- Pi Attempt 2 使用 `origin/main..INDEX`、Milestone 模式、Attempt 1 prior report 和 DeepSeek V4.1 Flash；未截断，结果 PASS、0 Must-Fix。S-1 修复被确认保持函数对象恒等、FastAPI override、缓存语义、路由契约与无循环导入。
- Pi 新建议 R-1 为 Low：非法 `disabled + fallback` 配置会在依赖解析时 503。Codex 不接受修改，因为模型路由契约和 `test_enabled_fallback_requires_enabled_primary` 明确要求该误配 fail-closed；正常 disabled 规则路径不受影响。
- 文档/路线：V3-01～V3-09 均为 Implemented，V3 全量影响审计 PASS；本次是独立 main 集成，不创建 V3-10，不提前实现后续 Node。
- 最终 diff：仅包含 V3 累计提交、本次 responder 依赖修复、公开 HTTP 回归测试、目标文档和两份本次 Pi 报告；review 上限临时改动已按 blob 哈希恢复，用户原工作区改动未进入候选。
- 最终验证：门禁 L3 / Milestone / Release Gate；完整 runner 4/4 PASS；显式条件契约 13/0/0/0；diff check、敏感扫描、Docker/临时资源清理通过；Pi PASS、0 Must-Fix。
- **Node Close Gate：YES**。允许创建发布提交、非 force 推送发布分支，并在重新 fetch 确认 `origin/main` 仍为预检基线且为候选祖先后，以 fast-forward 非 force 更新 main。
