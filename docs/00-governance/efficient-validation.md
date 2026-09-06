# 增量验证与上下文控制

- 状态：Accepted
- 适用范围：所有代码、配置、脚本、文档、V2/V3 Node 与发布变更

## 不可削弱的质量边界

优化的对象是重复工作，不是证据标准。所有变更仍必须遵守文档先行、Scope 边界、公共 seam TDD、真实机器验证、敏感信息扫描、风险匹配的 Pi 只读审核、可读提交和远端核验。历史报告、旧构建缓存与模型判断不能证明当前变更通过。

## 两个独立维度

每次验证同时判断：

1. **风险等级**：失败后果有多严重，决定 Review 强度与必须覆盖的失败类型。
2. **影响范围**：哪些模块、接口和运行链路可能被改变，决定实际运行哪些测试。

L3 表示失败后果高，不自动表示 Java、Python、Web 全部受影响。只有 Release Gate、影响范围无法收敛、共享构建/依赖变化或真实跨全仓修改才执行全仓回归。

| 风险 | 典型变化 | 最低 Review |
| --- | --- | --- |
| L0 | 普通文档、注释、纯文案、无行为样式 | 默认无 Pi |
| L1 | 单模块、接口不变、边界明确的局部行为 | 默认无 Pi |
| L2 | 外部调用、部署配置、普通 API/Tool/Workflow、多模块 | Pi Diff Review |
| L3 | Schema、权限/安全、Agent 状态机、公共契约、核心共享基础 | Pi Milestone Review |

| 影响域 | 最低机器门禁 |
| --- | --- |
| Docs only | 文档一致性、diff check、敏感扫描 |
| Web | Web 相关测试；生产入口/构建配置变化时再 build |
| Core API | Java 相关测试；持久化或完整模块变化时 `clean verify` |
| Agent Service | Python 相关测试；Agent Runtime/依赖变化时模块全量 |
| TLS / deployment | Shell parser、TLS contract、Compose 与 Nginx 配置生成 |
| Java ↔ Python | 两侧相关测试与契约/跨进程 smoke |
| Release | Java、Python、Web、Compose、核心 E2E、安全扫描全套 |

## 强制升级条件

以下任一条件不得降级：

- 路径或调用关系无法分类：至少 L2，并要求人工确认影响域。
- 数据库迁移、认证授权、配额限流、密钥处理、审批/幂等、公共 API Contract：L3。
- 同时修改 Core API 与 Agent Service，或公共字段跨服务传播：加入跨进程/契约 smoke。
- 修改共享构建、根级依赖或会影响所有模块的基础脚本：升级 L3 并执行全仓回归。已知路径至少包括根级 Maven/Gradle/Node 依赖与锁文件、根级 Compose、`Makefile` 和 `.github/workflows/`；不得落入 Unknown 人工判断桶。
- 测试出现非环境性失败、Pi 提出有证据的跨域问题或实现范围扩散：重新规划并扩大门禁。
- V2-09、V3-09、生产 Release Gate：固定完整回归，不使用裁剪。

节点结束仍触发 Milestone Review，用于检查 Scope 与方向；它本身不再自动触发无关模块的全量测试。连续 5 次 L0/L1 触发一次里程碑审计，但机器测试只覆盖累计变更实际触达的模块；累计影响无法可靠还原时才升级为全仓。

## 任务上下文包

任务开始先做 Git preflight：fetch 目标远端、确认或创建分支、记录 base commit、列出用户已有 staged/unstaged/untracked 文件。之后只读取：

- `docs/README.md` 与当前变更记录；
- 当前 Node 在路线图中的条目（Node 任务）；
- 受影响功能/API/架构/ADR；
- 直接调用方、公共 seam 与相关测试；
- 上一个直接相关节点的实现和验证结论。

以下情况才扩大阅读：系统边界变化、文档冲突、路径无法分类、测试失败指向其他模块或 Review 给出可验证的跨域证据。Start/Close Gate 重新核对上述上下文包和 diff，不机械重读所有全文。

## 同一任务内证据复用

允许复用本次任务中已经通过的测试结果，前提是：

- 测试对应的源码、配置、依赖和公共契约在之后没有变化；
- base commit 与执行环境没有发生会影响结果的变化；
- 没有新失败或 Review finding 指向该范围；
- 变更记录保存命令、版本、退出码、数量与本次 change fingerprint。

任一相关输入变化后，重跑对应测试。跨任务、历史报告、旧缓存和不同工作树的结果不能复用。Review 修复只重跑受修改影响的测试和被 finding 指出的回归；只有影响扩散时才重复其他已通过套件。

## 输出与证据控制

成功测试默认只进入对话和变更记录的结构化摘要：命令、工具版本、退出码、passed/failed/skipped、关键 smoke 结果和清理状态。完整日志可暂存到被忽略的临时目录，只有失败时读取相关区段，交付前清理。不得为了压缩输出隐藏失败、warning、skip 或清理异常。

## 可执行规划器

使用：

```powershell
.\scripts\validation\plan-change-gates.ps1 -BaseRef origin/dev -TargetRef WORKTREE
.\scripts\validation\plan-change-gates.ps1 -BaseRef HEAD -TargetRef INDEX -Json
```

测试或规划阶段可用 `-Paths` 显式传入仓库相对路径；绝对路径会被拒绝。规划器输出 `riskLevel`、`areas`、`gates`、`reviewMode`、`reasons` 和 `changeFingerprint`。fingerprint 包含 source 模式，只用于相同 source 与路径集合的同任务输入比较，不能跨 `WORKTREE` / `INDEX` / ref 模式比较。规划器是最低门禁建议器，不替代调用关系判断；Codex 必须说明任何向上升级或人工补充。

## 效率度量

后续三个任务在变更记录中追加：总耗时、规划读取文件数、执行测试命令数、重复测试次数、Pi 次数和是否返工。普通非发布 L1/L2 以减少 40%–70% 的时间和上下文消耗为观察目标；在三个样本完成前不得宣称已经达到。
