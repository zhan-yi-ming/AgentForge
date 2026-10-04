# R13 手工图数据备份与恢复

- 日期：2026-10-04
- 状态：完成
- 风险：L3（生产灾备与跨存储引用一致性）
- 范围：生产备份/恢复脚本、Neo4j 图事实边界、运维恢复演练

## 问题核实

报告问题成立。自动抽取的图投影可以由 Wiki/Task 重建，但 V3-04 Graph API 允许用户指定 Service/API/Issue 的 externalId、显示名、关系类型和多条 evidence；这些选择没有完整 PostgreSQL 事实副本。现有 `backup.sh` 只执行 `pg_dump`，Neo4j 卷丢失会永久丢失手工图，并让 PostgreSQL 规范映射引用不存在的稳定图实体。

## 目标契约与公共 seam

公共 seam 为 root 运维命令 `scripts/deploy/backup.sh` 和新增的 `scripts/deploy/restore-backup.sh <backup-directory>`。备份在短维护窗口内停止 gateway、Core 和 Agent 写入；PostgreSQL custom dump 与（存在受管 Neo4j 容器时）Community 版离线 `neo4j-admin database dump` 写入同一临时目录，生成 SHA-256 清单后才原子改名发布。失败时不发布半成品，并恢复备份前正在运行的服务。

恢复先验证目录边界、必需文件和完整性清单，再停止写入服务；PostgreSQL 和 Neo4j 均恢复成功后才重新启动原先运行的服务。恢复失败保持应用写入面停机，禁止在一半恢复的新旧库组合上继续服务。在线复制 `/data` 不属于有效备份。

无受管 Neo4j 容器的部署仍生成 PostgreSQL-only 目录并在 manifest 明确 `neo4j=absent`；一旦曾启用图功能，运维必须保留 Neo4j 容器/卷并验证备份含 `neo4j.dump`。

## TDD 与验证计划

1. 先添加生产备份/恢复 CLI 合约测试，证明旧脚本缺少写入静默、Neo4j 离线 dump、清单与恢复入口。
2. 最小实现备份/恢复脚本并通过 Bash 语法和 CLI 合约测试。
3. 使用临时 Docker volume 演练 Neo4j 5.26 Community 的离线 dump/load：包含一个手工 Service、一条手工关系、两条 evidence，核验 stable ID、版本和连接。
4. 核验 PostgreSQL custom dump/restore 可保留规范映射；运行门禁、diff check、Gitleaks 与 Pi Milestone Review，并回填证据和环境限制。

## 验证回填

- TDD 红灯：新增备份/恢复 CLI 合约首先因缺少 `restore-backup.sh` 失败；补齐实现后 `scripts/validation/backup-restore-contract.ps1` 通过，并验证两个 Bash 脚本语法。
- 备份实现会记录原运行服务、静默 gateway/Core/Agent、在受管 Neo4j 存在时执行固定 Compose 服务版本的离线 dump、生成 manifest 与 SHA-256 清单并原子发布；失败时删除精确 `.partial` 目录并恢复原运行集合。恢复在任何数据修改前校验路径、格式与校验和，PostgreSQL-only 备份拒绝覆盖已有受管图，任一恢复阶段失败均保持应用写入面停机。
- 真实 Neo4j 5.26 Community 临时卷演练通过：dump/load 后手工 Service、关系和 API 目标的 stable ID/version 保持一致，两条 evidence 的最高版本为 3。首次演练仅因验证命令引号导致断言脚本失败，临时资源已清理；修正验证命令后同一数据场景通过。
- 真实 PostgreSQL custom dump/restore 临时容器演练通过：规范实体 `version=7`、成员 `version=4/status=CONFIRMED` 保持一致；所有临时容器与卷均清理。Pi Attempt 1 随后指出 `--no-privileges` 会在 `--clean` 重建对象后丢弃 V16/V17 ACL；新增禁止该选项的合约测试先红灯，恢复改为保留归档 ACL 后绿灯。追加的一次性 PostgreSQL 17 custom dump/restore 演练证明 Core 业务 DML/来源代际 SELECT 与 Agent 派生表 DML 均保留，Agent 仍不能读取业务表/来源代际；首次演练因 TCP 尚未 ready 失败并已清理容器，修正就绪探针后通过且再次清理。
- 生产 Compose config、备份/恢复合约、最终 diff check 均通过；运维脚本未在真实生产数据目录执行，这是有意的安全边界。
- Pi：Attempt 1 为 NEEDS_FIX，唯一 High 为 PostgreSQL ACL 恢复缺陷，已按上述红绿灯与真实权限往返修复；Attempt 2 为 PASS、无阻断项。备份 stop 中途失败恢复、非规范布尔配置、旧格式轮换与完整 root 脚本演练均为建议项，登记在审核报告但不在 PASS 后扩大本批。
