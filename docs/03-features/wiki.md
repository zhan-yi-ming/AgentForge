# Wiki Page

- 状态：Implemented
- 所属阶段：V1 / Day 2
- 相关 ADR：ADR-0003、ADR-0004、ADR-0005

## 用户价值与场景

项目 owner 在项目内维护 Markdown 知识页，为 Day 4 RAG 提供可追溯的项目知识来源。ADMIN 可用于受控排查。

## 范围

- 在项目中创建、读取、列表、更新、删除 Wiki Page。
- 标题 1–200 字符，Markdown 内容最多 100,000 字符。
- 同一项目标题唯一；列表按更新时间降序。
- 使用整数 `version` 做乐观锁，避免静默覆盖并发编辑。

## 关键流程

所有请求先解析已认证 actor，再加载路径中的 Project 并校验 owner 或 ADMIN。读取具体页面时，同时用 `projectId + wikiPageId` 查询，避免把其他项目页面拼接到当前路径。更新校验请求 version 与当前值相同，成功后递增；删除同样要求 version。

## 接口与数据

接口见 `../04-api/core-api.md`，表结构见 `../02-architecture/data-architecture.md`。路径统一为 `/api/v1/projects/{projectId}/wiki-pages`；DTO 不暴露 JPA 对象。

## 权限、异常与排查

- 未认证 401；非 owner 且非 ADMIN 403。
- 项目或页面不存在 404；标题重复或 version 过期 409；字段无效 400。
- 发生 409 时重新读取页面再合并，不能盲目重试旧版本。
- 数据库错误先用 request ID 定位日志，再检查 V2 Flyway 迁移和唯一约束。

## 测试与验收

覆盖 CRUD、排序、标题重复、版本冲突、路径项目不匹配、跨用户拒绝与 ADMIN 成功。

## 已知限制

没有历史版本、附件、全文搜索、草稿、富文本或分页。Markdown 只作为文本保存，渲染与 XSS 清理属于 Day 6 Web 范围。
