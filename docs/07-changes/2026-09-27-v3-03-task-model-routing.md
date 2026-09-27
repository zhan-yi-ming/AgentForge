# V3-03 确定性任务模型路由

- 状态：Implemented；Milestone Review Attempt 1 PASS。
- 日期：2026-09-27
- 分支：codex/v3-03-task-model-routing
- Base：6fce27142f2c7591bc6ac8678d810179ad090700

## 背景、目标与范围
回答与 Tool 意图原来共用一个模型。根据 FORMAT/REWRITE/PLAN/REVIEW/ANSWER 固定任务规则和部署候选选择模型，综合成本、延迟、能力和兼容性。仅修改 Python、部署配置与文档；不实现 V3-04 图领域，不改变 Java 业务权限。

## Preflight
已 fetch origin，本地与 V3-02 远端一致。保留用户已有 audit 文档、.worktrees/ 和产品规划 docx；未纳入本节点。

## 文档先行与设计
先建立本记录，更新 Model Routing、架构、ADR-0031，再修改实现。使用 primary/fallback 凭据槽、无密钥路由 JSON。首部规则分类仅影响生成，检索和历史不参与。候选覆盖全部任务，主备均校验能力。未配置路由保留 V3-02。

## 风险与验证计划
规划器 L2，配置 schema 与能力边界人工上调 L3；AgentService、Deployment、Docs，Milestone Review。已确认 build_responder/Responder 公共 seam，使用 TDD 逐切片。全量 Agent pytest、Compose、构建、diff check 与敏感扫描；不运行未修改的 Java/Web 全量测试。

## TDD 证据
首个 Responder 路由测试实际红灯：预期 cheap-model，实际 base-model（1 failed，退出 1）；实现后 1 passed（退出 0）。命令：仓库根目录 services/agent-service/.venv/Scripts/python.exe -B -m pytest services/agent-service/tests/test_model_routing.py -q -p no:cacheprovider。
首次编辑脚本因默认 GBK read_text 失败，未执行实现修改；改为显式 UTF-8 后完成。

PLAN 文本/JSON 意图区分与未选候选缺凭据分别实际红灯后修复绿灯。新增回退归属测试发现同模型名跨 provider 时 Trace 仍为主 provider（pytest 退出 1）；先记录再修复同步与流式 metadata。配置说明文件实际为 local-development.md，错误路径编辑失败后未改不存在文件。


## 首次全量验证与诊断
工作目录 services/agent-service，命令 `.\.venv\Scripts\python.exe -B -m pytest -q -p no:cacheprovider --basetemp .pytest-v303-20260927`：退出 1，162 passed / 4 failed / 0 skipped / 4 warnings。三项 PostgreSQL Testcontainers 因 Docker daemon 未运行失败；启动已安装 Docker Desktop 后 server 29.5.3 可用。另一项 timeout 断言在 SDK 测试之后复现（仓库根目录 `services/agent-service/.venv/Scripts/python.exe -B -m pytest services/agent-service/tests/test_model_gateway.py services/agent-service/tests/test_model_routing.py::test_routed_fallback_is_bounded_and_preserves_actual_usage -q -p no:cacheprovider --tb=short`，pytest 退出 1，23 passed / 1 failed）：LiteLLM 导入加载环境使缺省 timeout 不再是测试假定值。路由原样传参，测试 fixture 显式指定 timeout=10，避免隐式环境依赖。
Compose 两份 config --quiet 首次退出 0；首次 build 因 daemon 未运行退出 1，待重跑。目标重复跨槽测试先红灯、按 provider/model 去重后 23 passed（退出 0）。

新增观测替换语义切片实际红灯（task_type 丢失），路由采用每调用独立 metadata 合并 adapter，避免依赖后端隐式合并。SDK 顺序复现修复后 24 passed / 1 warning（退出 0）。

HTTP 路由测试首次 24 passed / 1 failed（退出 1）：TestClient context manager 触发真实 PostgreSQL lifespan，fixture override 不覆盖 lifespan。按既有 HTTP 测试方式只验证请求入口，使用内存 runtime 与普通 TestClient，避免连接开发 DB；现有 PostgreSQL integration 仍由全量回归实际运行。镜像构建重跑成功（退出 0），pip root 安装 warning 保留。


## 当前机器通过证据
- 工作目录 services/agent-service：`.\.venv\Scripts\python.exe -B -m pytest -q -p no:cacheprovider --basetemp .pytest-v303-final-20260927`，退出 0，168 passed / 0 failed / 0 skipped / 4 warnings。Python 3.14.3、pytest 8.4.2。包含 PostgreSQL Testcontainers、真实 LiteLLM 本地 HTTP smoke、25 项新路由与 HTTP/NDJSON 测试。warnings 为 Starlette/AnyIO 弃用与 LiteLLM/Pydantic ReadOnly 提示。
- 仓库根目录：`services/agent-service/.venv/Scripts/python.exe -B -m pytest services/agent-service/tests/test_model_routing.py -q -p no:cacheprovider`，最终退出 0，25 passed / 1 warning。HTTP fixture 必需参数遗漏的中间运行退出 1，24 passed / 1 failed，修正测试 fixture 后通过。
- 仓库根目录：`services/agent-service/.venv/Scripts/python.exe -m pip check`，退出 0，无冲突。
- 仓库根目录：`docker compose --env-file .env.example -f infra/compose.yaml config --quiet` 与 `docker compose --env-file .env.production.example -f infra/compose.prod.yaml config --quiet`，退出 0；Docker 29.5.3、Compose 5.1.4。
- 未调用真实付费厂商，rank 是部署声明相对值，无实际省钱/延迟改善指标；未知成本与失败调用费用仍有 V3-02 限制。没有 Java/Web 或 HTTP schema 改动，不运行无关模块全量回归。

## 实现摘要
增加候选 schema、固定规则分类、每任务排序和最多一次主备回退。复用既有凭据槽，JSON 与 streaming 能力失败关闭；PLAN 自然回答保持文本，Tool 意图固定 JSON。按 provider/model 去重，避免自回退；跨 provider 同模型名正确更新 metadata，每调用独立 adapter 防止覆盖 task_type。没有 DB schema 变化。

- 最终源码镜像：仓库根目录 `docker compose --env-file .env.example -f infra/compose.yaml build agent-service`，退出 0，LiteLLM 1.102.1，pip root 安装 warning。没有重建或替换正在运行的用户服务。
- 仅本节点两个 basetemp 目录已验证绝对路径在仓库内并清理；Testcontainers 无残留，本地原有服务保持运行；旧 .pytest-p304 权限 warning 是既有环境，未删除。
- `git diff --cached --check` 退出 0；暂存仅本节点 18 文件，用户原有改动未暂存。Gitleaks v8.30.1 `docker run --rm -v "${PWD}:/repo:ro" -w /repo zricethezav/gitleaks:v8.30.1 git --staged --no-banner --redact=100` 退出 0，无泄漏。
- scoped planner `./scripts/validation/plan-change-gates.ps1 -BaseRef HEAD -TargetRef INDEX -Milestone -Json`：L2 / AgentService,Deployment,Docs / Milestone，fingerprint 82adf3e7e97949d9cb626d226e91ce1e187ce44c9e93bca1ce44c196455ecf4c；人工保持 L3。


## Pi 与收口
Pi Attempt 1 PASS：docs/08-reviews/2026-09-27-review-v3-03-task-model-routing-attempt-1.md。六项建议逐项研判：F-01 降级 runbook 冲突修正文档；F-02 引用与当前源码不符，不成立；F-03 fallback_provider=None 已严格禁用，无真实缺陷；F-04 核对实际 Web 前缀并补契约说明/一致测试输入；F-05 明确 metadata 只覆盖回答 generation，规划观测未实现；F-06 route 为初始决策，实际模型独立记录。没有真实阻断项，不触发第二次审核。README、路线与架构改为 Implemented，V3-04 保持待授权。

审核后仅修改文档、状态和现有 FORMAT 测试输入，源码/配置/依赖/base 未变。仓库根目录 `services/agent-service/.venv/Scripts/python.exe -B -m pytest services/agent-service/tests/test_model_routing.py -q -p no:cacheprovider` 重跑退出 0：25 passed / 0 failed / 0 skipped / 1 warning。其余本任务全量 168 passed 证据可复用；未重复无关验证。

Node Close Gate：Scope 已完成，Pi PASS，建议逐项回填；公开 Claim 不包含实际节省数据；无 V3-04 实现。暂存仅本节点，用户原有变更保留。最终 diff check 与敏感扫描再次执行；满足关闭 YES 后提交 feat(agent): add deterministic task model routing，非 force 推送 origin/codex/v3-03-task-model-routing 并核验，随后停止。
