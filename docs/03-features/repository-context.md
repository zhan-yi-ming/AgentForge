# Git Repository Context

- 状态：Implemented（V3-08；Milestone Review PASS）
- 相关：[Context Management](context-management.md)、[GraphRAG](graphrag.md)、[ADR-0036](../02-architecture/decisions/ADR-0036-project-scoped-repository-context.md)

## 场景与请求流程

已获 Java 项目访问权限的成员在 Chat 中询问项目入口、API 文档、配置或近期提交。Java 先完成用户、项目与配额检查，再调用 Python Agent Service。部署方可按项目 UUID 配置一个本机仓库根目录；未配置时现有 Wiki/Task/Graph 路径不变。Python 只从当前项目对应仓库的 HEAD 已提交 Git tree 构造有界资料，与现有检索候选合并，经 Prompt Composer 的总预算进入模型。

## 资料范围与引用

只选择根级 README、`docs/04-api/` 中的 Markdown、根级 `pom.xml`/`pyproject.toml`/`package.json` 等关键配置、有限目录名与最近提交的 SHA/subject。目录、文件和 commit summary 都按项目、HEAD SHA 与逻辑路径生成稳定 UUID。Chat `sources` 增加 `REPOSITORY` 类型，字段仍为 `sourceType,sourceId,title,excerpt`；仅在最终回答明确使用 `【来源N】` 时回传。标题含仓库相对路径或短提交号，不能让模型伪造文件来源。HEAD 改变后重新读取，旧引用身份不复用。

## 安全边界与失败

映射仅由部署配置提供，浏览器与模型无权指定路径或 revision。Git 子进程使用参数数组且仅读命令，拒绝 symlink、submodule、二进制、敏感名称与疑似 secret 内容。单文件最多读取已提交 blob 的前 16 KiB，较长文件只使用此前缀；即使单文件无法读取，也不影响其他仓库候选。Git tree 最多读取前 256 KiB、检查前 1000 条完整记录；大仓库可能漏掉排序靠后的白名单文件，但不因截断而丢弃已找到的 README。扫描命中的整份候选不进入模型；引用不提供仓库文件下载接口。已提交仓库文本仍是不可信资料，只能作为事实参考，不能改变系统指令或触发 Tool。仓库根目录不存在、Git 不可用或读取超时，仅放弃仓库候选，保留原有项目检索；项目授权错误仍由 Java 阻断。仓库与 Wiki/Task/Graph 都受候选数和统一 Context 预算约束。

## 验证与限制

真实临时 Git 仓库验证 HEAD 快照、白名单、工作树排除、项目隔离、secret/路径过滤、预算和故障；Python Retrieval 与 Chat 同步/流式、Java Chat 契约及 Web DOM 检查引用链。该功能不理解整个代码库，不运行仓库脚本，不保证每次关系/配置回答都召回正确文件；commit subject 与目录仅提供浅层线索。未配置仓库时完全不提供 Repository citation。
