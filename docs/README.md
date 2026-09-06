# AgentForge 文档中心

这里是项目的长期记忆，也是开发工作的入口。代码回答“现在怎样运行”，文档回答“为什么这样设计、改过什么、下一步在哪里”。

## 阅读顺序

第一次进入项目时，按以下顺序阅读：

1. `01-product/product-overview.md`：理解产品解决的问题与 V1 边界。
2. `01-product/roadmap.md`：理解三阶段路线和当前 Day 7 / V1 验收目标。
3. `02-architecture/system-overview.md`：理解 Web、Java、Python、数据与基础设施的关系。
4. `02-architecture/repository-structure.md`：找到代码和文档的位置。
5. `03-features/README.md`：进入具体功能文档。
6. `07-changes/README.md`：查看最近为什么发生变化。

## 目录地图

### `00-governance/`：项目如何被维护

- `documentation-first-policy.md`：文档先行的强制规则、什么情况需要改哪些文档。
- `change-workflow.md`：从接收需求到交付检查点的标准流程。
- `definition-of-done.md`：一项变更何时才算真正完成。
- `public-repository-security.md`：公开仓库中的密钥边界、扫描与泄漏处置。

每个阶段由 AI 完成 Git 提交并推送到已确认的远程分支；提交与推送之间不得再改文件，远程核验后立即进入用户确认检查点。

### `01-product/`：做什么与何时做

- `product-overview.md`：用户问题、产品定位、核心原则和 V1 范围。
- `roadmap.md`：V1/V2/V3 路线以及 V1 的 7 天节奏。

### `02-architecture/`：系统为什么这样组成

- `system-overview.md`：系统上下文、组件职责、调用与信任边界。
- `frontend-architecture.md`：React 组件、状态、API 与 Markdown 安全边界。
- `repository-structure.md`：单仓库目录、命名和依赖方向。
- `backend-architecture.md`：Java 模块、分层职责和约束。
- `data-architecture.md`：PostgreSQL 数据模型、迁移与隔离原则。
- `decisions/`：架构决策记录（ADR），保存重要选择及其取舍。

### `03-features/`：每项用户能力如何工作

每个可见功能或关键平台能力必须有独立文档。功能文档描述目标、角色、流程、接口、数据、权限、异常、测试和已知限制。当前从 `user-and-project.md` 开始。

### `04-api/`：服务之间如何通信

- `core-api.md`：Java Core API 的 HTTP 契约、错误模型和版本规则。
- `agent-service.md`：Day 3–Day 5 Java/Python Chat、RAG 来源、Tool proposal 与确认契约。

### `05-development/`：开发者如何运行和验证

- `local-development.md`：本地依赖、启动顺序、环境变量和常见问题。
- `testing-strategy.md`：测试分层、命名、运行命令和质量门槛。
- `git-workflow.md`：分支、检查点和提交建议。

### `06-operations/`：系统如何部署与排错

- `local-stack.md`：本地 PostgreSQL/Redis 容器、健康检查和数据清理。
- `review-orchestration.md`：已停用 Pi 编排的历史设计、禁用状态和追溯说明。
- `pi-review-connection.md`：提交前一次性 Pi 审核、启动器配置、十秒预检和快速失败规则。
- `production-single-host.md`：V1.1 公网 Demo 的单机部署、更新、备份、回滚和排障。

### `07-changes/`：每次变化的证据链

- `README.md`：变更记录的规则和索引。
- 每次改动一份日期加主题命名的记录；小的同主题修正可以更新同一份记录。

### `08-reviews/`：代码审查中心

- `README.md`：Pi 只读审核与 Codex 测试证据的分工和追溯规则。
- 保存各阶段交付物的结构化代码审查报告（`YYYY-MM-DD-review-day-<X>.md`）。

### `templates/`：写文档时复用

- `change-record-template.md`：变更记录模板。
- `feature-template.md`：功能文档模板。
- `adr-template.md`：架构决策模板。
- `review-record-template.md`：代码审查报告模板。

## 文档状态约定

文档顶部可以使用以下状态：

- `Proposed`：已讨论但未实现。
- `Accepted`：方案已确认，正在或准备实现。
- `Implemented`：实现和验证已经完成。
- `Deprecated`：仍保留供追溯，但不应继续采用。

文档中的“当前状态”必须与仓库真实情况一致。规划内容应明确标为未来状态，不能写成已经完成。
