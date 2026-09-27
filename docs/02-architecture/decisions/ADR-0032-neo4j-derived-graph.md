# ADR-0032：Java 管理 Neo4j 派生领域图

- 状态：Accepted
- 日期：2026-09-27

## 决策

Neo4j Community 存储可重建 GraphEntity、GraphRelation、GraphEvidence 节点，以唯一 id 约束保障幂等；FROM/TO/SUPPORTS 连接表达拓扑，业务关系类型保存在有限 type 字段。Schema v1 使用命名 IF NOT EXISTS 唯一约束，首次图请求幂等执行，不连接启动依赖。ProjectLock 唯一节点写入锁序列化项目更新/清理及 CAS。只有 Java application service 能授权、验证和写图，不向 Python 下发数据库凭据。

PostgreSQL 不写 graph 业务表，不使用跨库伪事务。Wiki/Task 查询复用公开应用服务，source version 和 evidence 原文比对后才写入；读取重新验证，无效证据不进入返回。来源当前快照与 Neo4j 提交之间允许竞争，禁止宣传原子一致；V3-05 处理事件生命周期与重建编排。

## 取舍

Reified relation/evidence 节点避免 Community 无关系属性唯一约束，允许独立 evidence CAS 和多来源保留；代价是额外连接。每项目锁以吞吐换取简单确定的并发语义，当前单项写入无批量和跨项目事务。有限一跳查询不构成 GraphRAG。可选 Neo4j profile 不改变已有业务依赖或健康检查；驱动错误一律转通用 503，图关闭仍可运行业务服务。
## Context / Alternatives / Consequences

现有 Wiki 链接图不能表达 Service/API/Issue 与业务任务关系。选择 Neo4j Java driver（Boot 管理版本 5.28.13），不引入 Spring Data Neo4j 对业务对象的第二套 ORM。另一方案是在 PostgreSQL 加关系表，但与已确认 Neo4j Node 的目标不符；原生关系直接携带全部 evidence 会使多来源和 CAS 更难独立约束。采用 Community 5.26 容器与命名 v1 约束；升级不依赖隐式 schema 自动更新。

查询必须参数化，使用 driver transaction timeout；会话独立且始终关闭。参考 [Neo4j driver transactions](https://neo4j.com/docs/java-manual/current/transactions/)。未来大吞吐需重新评估项目锁，V3-05 负责生命周期管道，V3-06 负责实体消歧，V3-07 才扩展检索。