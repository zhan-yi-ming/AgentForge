# ADR-0035：项目图清理的持久 generation

- 状态：Accepted
- 日期：2026-09-29
- 前置：ADR-0032、ADR-0033、ADR-0034

## Context

Neo4j 图节点由稳定 ID 可重建。V3-04 清图只删 Neo4j，而 V3-06 人工规范映射保留在 PostgreSQL；相同来源版本重建后，旧合并会复活。V3-05 来源待办可能与清理并发，重新填充已清空的投影。

## Decision

PostgreSQL 保存每项目单调递增的 graph generation 和临时 resetting 状态。清理先提交新 generation 并删除此前待办；旧 member 行在清理开始时删除；canonical 行保留供事件外键与审计使用，只在当前 generation 时有效。worker 在来源替换期间持项目状态共享锁；清理开始以排他更新等待在途替换完成。清理期间 worker 不处理该项目，新业务写入可登记新待办。重叠清理在五分钟有效租约内返回 503；超过五分钟的 resetting 视为中断，重试以新 generation 接管，旧请求只能解除自己 generation 的状态。Neo4j 清理完成或失败后解除暂停；清理失败返回依赖错误且旧合并依旧失效，重试可完成投影清理。人工确认在当前 generation 中重新建立映射，原有审计事件保留。

## Alternatives / Trade-offs / Consequences

直接删除规范映射会破坏审计外键与撤销历史；仅比对 source version 无法区分清图前后相同事实。跨库原子事务不可用，因此先持久失效旧映射，优先阻止错误合并复活。故障可能留下 resetting 状态；五分钟内拒绝重叠清理，超过五分钟后由重试清理幂等接管。当前租约没有心跳，单次 Neo4j 清理若超过五分钟，旧清理可能与接管后的同步重叠；图仍可重建，运维需观察清理时长并在接管后显式 rebuild。运行手册通过项目 generation、resetting 与 updated_at 查询观察该状态。项目串行化增加同项目图写入等待，但不扩大到其他项目。
