# ADR-0036：项目绑定的只读 Git Context

- 状态：Accepted
- 日期：2026-10-02
- 前置：[ADR-0010](ADR-0010-day-4-rag-boundary-and-ranking.md)、[ADR-0035](ADR-0035-evidence-bounded-graphrag.md)

## Context

研发问题需要仓库文档和近期提交，但 Python 直接扫描任意工作目录会把未提交文件、凭据或其他项目资料暴露给模型。把完整代码纳入索引会扩大成本、过期与安全面。

## Decision

Java 继续先授权项目和 Chat；Python 只按部署方配置的 project UUID 到绝对仓库根目录映射读取当前 HEAD 的 Git 对象。每次读取先把 HEAD 解析为不可变完整 commit ID，并让 tree、blob、log、来源 revision 与稳定 UUID 全部从该 commit 派生；HEAD 在调用中途移动不能形成混合来源。默认无映射。白名单、secret 检测、Git 输出/文件/候选/Context 硬上限和只读容器挂载限制输入范围。仓库候选使用独立 `REPOSITORY` 引用类型，不作为业务写入或 Tool 目标。Git 故障降级为现有检索，且不改变 Java 对授权、审批与写入的最终决定。

## Alternatives and trade-offs

由 Java 持有仓库配置可集中业务授权，但会把 Git 检索和概率性融合移入业务服务；在 Python 接收用户路径则无法建立可信项目绑定。静态部署映射减少运行时复杂度，却需要部署方维护项目与仓库目录对应关系。每次调用固定当时 HEAD commit，避免跨命令版本漂移，但不会追随调用中途的新提交；下一次查询才观察新 HEAD，且每次仍有固定 Git I/O。有界白名单可能漏掉有用文档。已提交内容也可能含秘密，因此扫描与白名单是降低风险的控制，不保证所有敏感信息被识别。
