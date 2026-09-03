# ADR-0003：Java 使用按业务能力分包的模块化单体

- 状态：Accepted
- 日期：2026-09-03

## 背景

Java 是确定性业务系统，需要承载用户、权限、项目、Wiki、Task 和审批。V1 时间短，但后续功能会增长。

## 备选方案

- 全局按 Controller / Service / Repository 分层：适合很小的示例，功能增长后同一业务散落全仓库。
- Day 1 直接拆微服务：部署和一致性成本过高，不符合 5–7 天 V1。
- 模块化单体：一个部署单元，按业务能力组织，在模块内部保留清晰分层。

## 决策

采用模块化单体。根包的直接子包表达业务模块；模块内部使用 `api`、`application`、`domain`、`infrastructure`。跨模块只依赖稳定入口，不读取对方持久化实现。

此结构借鉴 [Spring Modulith](https://github.com/spring-projects/spring-modulith) 的业务模块思想。Day 1 不引入其全部能力，避免为了形式增加学习和构建成本；达到多个强交互模块后再评估自动结构验证。

## 结果

用户仍能清楚学习 Controller、Service、Repository、Entity，同时相关业务文件相邻。未来可把成熟模块拆为独立服务，但当前没有分布式事务与运维负担。

## 取代关系

无。
