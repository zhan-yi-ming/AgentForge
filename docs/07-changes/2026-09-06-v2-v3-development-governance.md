# V2/V3 节点开发与 GitHub 维护治理

- 状态：Implemented
- 日期：2026-09-06
- 风险等级：L0（纯文档治理，不改变运行行为）
- 范围：V2/V3 节点路线、开发 Gate、GitHub 公开维护规则、文档入口

## 背景

V1 已完成并通过验收，项目即将进入 V2。现有 `docs/01-product/roadmap.md` 只描述 V2/V3 的阶段级方向，尚未固化每个 Node 的范围、验收标准、审核重点和公开展示边界。若后续仅依靠会话记忆，容易跨节点开发、提前宣称计划能力或在节点结束后自动推进。

用户要求把长期规则写入项目正式文档，并明确这些规则不是 `AGENTS.md` 内容。后续每次 V2/V3 开发都必须重新读取对应文档。

## 目标与范围

- 新增 V2/V3 节点开发协议，固化项目定位、V1 冻结、Node Boundary、Start/Close Gate、Codex/Pi 分工、测试分级、提交及 GitHub Evidence 规则。
- 新增 V2/V3 节点路线图，作为开发顺序与 Node Scope 的唯一来源，完整记录 V2-01 至 V2-09、V3-01 至 V3-09。
- 更新文档中心、阶段路线和标准变更流程，使上述两份文档成为每次 V2/V3 开发的强制阅读入口。
- 保持未来能力为 Planned，不把 V2/V3 路线写成已实现能力。

## 非目标

- 不修改 `AGENTS.md`、根 `README.md`、业务代码、配置、数据库或基础设施。
- 不建立 `.agentforge/` 私有路线文件，不修改 `.gitignore` 或 `.git/info/exclude`。
- 不实现 V2-01 或任何 V2/V3 功能。
- 不创建提交、不推送、不创建版本标签。

## 计划修改的文档

- `docs/00-governance/v2-v3-node-development-protocol.md`
- `docs/01-product/v2-v3-node-roadmap.md`
- `docs/README.md`
- `docs/00-governance/change-workflow.md`
- `docs/01-product/roadmap.md`
- `docs/07-changes/README.md`

## 验证方式

1. 检查两份新文档包含固定开发顺序、所有 Node、Start/Close Gate、节点边界和 GitHub 真实性规则。
2. 检查 `docs/README.md`、总路线和变更流程均链接到真实存在的新文档。
3. 运行 `git diff --check`，并检查 Markdown 相对路径。
4. 扫描本次 diff 中常见密钥、Token、密码和私钥模式。
5. 运行 `git status --short`，确认未修改业务实现、根 `README.md` 或 `AGENTS.md`。

## 回滚思路

删除两份新增文档，并恢复文档中心、总路线、变更流程与变更索引中的对应入口即可。该回滚不涉及运行时状态、数据或迁移。

## 实施与验证回填

- 新增 `docs/00-governance/v2-v3-node-development-protocol.md`，固化强制阅读入口、项目定位、V1 冻结、Node Boundary、Node Start/Close Gate、固定开发流程、Codex/Pi 分工、风险匹配验证、GitHub 真实性与 Evidence、提交和停止规则。
- 新增 `docs/01-product/v2-v3-node-roadmap.md`，完整记录 V2-01 至 V2-09、V3-01 至 V3-09 的固定顺序、Scope、验收、Review 与公开展示边界；当前状态明确为 V1 completed、V2 尚未开始、Next Node 为 V2-01。
- 更新 `docs/README.md`、`docs/00-governance/change-workflow.md`、`docs/01-product/roadmap.md` 和 `docs/07-changes/README.md`，把两份新文档接入每次 V2/V3 开发的强制入口。
- 未修改业务代码、配置、数据库、根 `README.md` 或 `AGENTS.md`，未开始 V2-01，未创建 `.agentforge/`，未提交或推送。

验证证据（2026-09-06，PowerShell）：

- 对本次 7 个文档路径执行 `git diff --check -- <paths>`，退出码 0。
- 自动核对 V2-01 至 V2-09、V3-01 至 V3-09：缺失节点 0；Node Start Gate 与 Node Close Gate 均存在。
- 核对 4 个核心入口/目标路径：缺失路径 0。
- 扫描本次 diff 的 API key、secret、token、password、私钥、`ghp_`、`sk-` 等模式：命中 0。
- `git status --short` 显示本次只新增/修改上述治理与路线文档；工作树中预先存在的 `docs/07-changes/2026-09-05-disable-pi-and-day1-day4-audit.md` 修改和 `AgentForge_产品规划与三阶段迭代路线.docx` 未跟踪文件保持不动。
- `git diff --name-only -- AGENTS.md README.md` 无输出，确认本次未改根 `AGENTS.md` 与根 `README.md`。
- 低风险累计计数：1/5（上一次 Milestone Review 后首次 L0/L1）；本次不是功能开发节点结束、重要合并或下一节点启动，未触发 Pi Milestone Review。纯文档 L0 变更未运行不相关的 Java/Python/Web 测试。
