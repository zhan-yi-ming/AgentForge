---
description: 执行当前阶段的代码审查并输出标准报告
argument-hint: "[commit-sha 或 HEAD~1]"
---
请作为 AgentForge 的独立只读审查员（Reviewer），对 ${1:-HEAD~1..HEAD} 范围内的代码改动进行深度技术审查。

审查步骤：
1. 使用 git diff 检查改动内容：`git diff ${1:-HEAD~1..HEAD}`。
2. 重点审查：真实Bug、权限越权（Spring Security / JWT / Service层所有权校验）、REST契约一致性、乐观锁与并发控制、关键异常处理及测试覆盖。
3. 严格遵循 V1 边界，禁止建议引入 V2/V3 组件（如 Neo4j、Langfuse 全链路、MCP）。
4. 按照标准审查格式输出报告（包含 severity、file、line、evidence、suggested_fix）。
5. 保持严格只读，不要修改任何代码文件。
