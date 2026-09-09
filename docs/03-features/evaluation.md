# Evaluation Pipeline

- 状态：Implemented
- 所属阶段：V2-08
- 相关 ADR：ADR-0010

## 用户价值与使用场景

维护者可用固定数据集重复评估 AgentForge 的项目检索、回答忠实度和 Tool Intent，及时发现排序、提示或 Tool 参数解析回归。报告给出逐样本证据，而不是只展示一个无法追溯的总分。

## 范围与非目标

V2-08 包含小型版本化 dataset、统一离线 runner、JSON report 与六项指标：Recall@K、MRR、Hit Rate、Faithfulness、Tool Selection Accuracy、Task Success Rate。

它不替代 V2-09 Release Regression，不调用生产业务写接口，不验证权限/审批/恢复链路，也不把离线确定性 Faithfulness 代理指标描述成完整语义或事实正确性评测。

## 关键流程

1. runner 读取并校验版本化 dataset。
2. RAG subject 仅接收 query 与 corpus，执行当前 BM25/RRF 排序；gold source IDs 只进入评分阶段。
3. Answer evaluator 把固定候选 answer 切成 claims，并对可检索 context 计算词项支持率。
4. Tool subject 把用户输入交给生产 `plan_tool`；选择评分检查 Tool/no-tool，任务成功评分检查完整参数。
5. runner 汇总宏平均指标、逐样本结果、dataset SHA-256、方法与限制，原子写入 JSON report。

## 接口

- CLI：在 `services/agent-service` 执行 `python -m agentforge_agent.evaluation.runner --dataset <path> --output <path>`。
- Python 公共 seam：dataset loader、`EvaluationRunner.run()` 与 JSON report writer。
- production subject seam：现有 `bm25_rank`、`reciprocal_rank_fusion` 和 `plan_tool`。

CLI 非 HTTP 公共 API；V2-08 不修改 Java/Python 服务契约。

## 数据

dataset 顶层包含 `schemaVersion`、`name`、`corpus`、`ragCases`、`answerCases` 与 `toolCases`。每条记录 ID 唯一；RAG gold 必须引用 corpus 中存在的 source；Tool gold 使用与 `ToolProposal` 相同的字段或显式 `null` 表示 no-tool。

report 包含 dataset 身份/hash、subject 版本、生成时间、指标定义、聚合值和逐样本得分。报告不包含密钥、Prompt、内部 token 或生产数据。

## 权限与安全

固定语料只能使用人工构造的非敏感内容。runner 不接收 JWT、服务间 token 或生产数据库连接，不写业务表。gold 与被测 subject 的输入对象分离，防止答案泄漏。

## 失败与排查

dataset schema、重复 ID、悬空 gold、非法 K 或 Tool 参数不合法时 runner 非零退出且不保留半写报告。指标变化时先查看逐样本 ranking、claims 和规范化 proposal，再判断是实现回归还是 dataset/gold 需要显式版本升级。

## 测试与验收

- 已确认 seam：runner 输入/报告、RAG 公共排序、Agent Tool planner 输出、report schema/重复执行。
- 用独立手算样例验证 Recall@K、MRR、Hit Rate 和宏平均。
- 覆盖 Wiki、Task、多 gold、无命中、Create/Update Tool、完整参数错误与 no-tool。
- 同一代码和 dataset 重跑时，除生成时间外的报告内容一致。
- 实际运行固定 dataset 并提交真实报告后，文档状态才能改为 Implemented。

## 已知限制与后续计划

离线 Faithfulness 使用词项支持率，不能识别否定、同义改写或复杂推理；固定小数据集用于回归信号，不代表线上质量或统计显著性。V2-09 将把 runner 纳入 Release Regression，后续可在不改变报告诚实边界的前提下增加人工/模型裁判和更大数据集。
