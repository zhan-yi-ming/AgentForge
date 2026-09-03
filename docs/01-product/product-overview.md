# 产品概览

- 状态：Accepted
- 当前阶段：V1
- 产品来源：`AgentForge_产品规划与三阶段迭代路线.docx` V0.1 的仓库内维护版本

## 一句话定位

AgentForge 是面向研发团队的“项目知识 + 任务协作 + AI Agent”平台。它把 Project、Wiki、Task、历史上下文和 AI Agent 放在同一工作空间，让 AI 能查资料、理解项目上下文并提出业务操作；真正的修改由确定性后端做权限校验，并在必要时要求人工确认。

## 要解决的问题

- 项目资料散落在文档、Wiki、任务系统和聊天中，开发时难以找到可信上下文。
- 普通 AI 对话缺少项目约束、历史方案和已有任务，容易答非所问。
- 模型直接写业务数据风险高，修改和删除需要权限、确认、幂等与审计。
- 长对话不能无限堆叠消息，需要摘要、检索、状态和 Token 预算管理。

## V1 用户能力

- 创建和查看项目、Wiki 与 Task。
- 在当前项目中与 Agent 连续对话，并从 Wiki / Task 检索上下文。
- 让 Agent 查询资料、创建任务或提出任务修改。
- 对修改类操作先预览并确认，再由 Java 服务执行。
- 把自然语言整理为 Markdown 或结构化内容，保留原文并在确认后写回。

## 核心工程原则

### AI 决策，确定性系统执行

Python Agent 负责 LLM、检索、上下文和工具调用意图；Java Core API 负责用户、权限、项目、Wiki、Task、审批和数据库写入。模型可以提出“想做什么”，但不能自行决定“是否允许”。

### 先能用，再可靠，最后复杂

V1 跑通 Web → Java → Python Agent → RAG / Tool → Java 写回。V2 加强状态、权限、审计、恢复、Trace 和评测。V3 再加入 GraphRAG、MCP 与多模型路由。

### 文档是项目长期记忆

所有改变先进入文档，再进入实现。产品行为看功能文档，系统结构看架构和 ADR，历史变化看变更记录。

## 当前交付边界：Day 2

Day 1 已完成整体仓库骨架、Spring Boot + PostgreSQL 基础和 User / Project 最小链路。Day 2 正在实现登录鉴权、基础 RBAC 与 Wiki / Task CRUD；Python Agent、RAG、Tool Calling 和 React 页面仍属于后续天次，不能因为预留目录而被误认为已实现。
