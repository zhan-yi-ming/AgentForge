# V2-01 与 TLS 域名兼容生产发布

- 日期：2026-09-07
- 状态：Proposed
- 目标环境：阿里云中国香港单机生产 Demo
- 集成分支：`codex/v2-01-production-release`
- 计划发布标签：`v2.0.0-alpha.1`

## 背景

生产 `main` 已包含 V1.2.1 居中 Demo，后续完成的 V2-01 Langfuse 基础 Trace、验证流程优化和 TLS 公网 IP / 域名兼容分别位于连续提交链上。用户已明确授权把所有已完成代码合并到 `main` 并部署正式环境；V2-02 尚未授权，不得随本次发布启动。

## 范围

- 以最新 `origin/main` 为发布基线，合并 `codex/tls-public-host-compat`；该提交链已包含 V2-01 与工作流优化。
- 完整回归 Java、Python、Web、生产 Compose/TLS 契约与核心跨服务 smoke，并执行 Release Gate 的 Pi Milestone Review。
- 将通过验证的同一提交链快进到 `dev`，随后把发布集成提交推送到 `main`，创建不可移动的预发布标签 `v2.0.0-alpha.1`。
- 通过生产 `update.sh` 先备份数据库、快进服务器 `main`、重建五个服务并检查容器与公开边界。
- 核对生产私有环境中的 `PUBLIC_HOST`、域名 DNS 与 TLS 现状，但不读取、输出或提交任何密码、Token、API key、私钥或完整 `.env`。
- 域名正式证书首次申请作为部署后的明确操作：若 DNS 与公网 80 已就绪且无需用户提供信息，可在生产直接完成；否则给出唯一需要用户执行的命令，不伪造 HTTPS 成功。

## 非目标

- 不实现 V2-02 或任何后续 Node。
- 不修改 Schema、生产业务数据、模型供应商或现有密钥。
- 不把用户工作树中的未提交文档修改和 DOCX 带入发布。
- 不 force push，不重写已发布历史，不删除现有证书或备份。

## 风险与验证计划

这是 Release Gate，按 L3 操作严谨度执行。主要风险是两条已完成历史合并后的回归、生产环境变量仍指向旧 IP、旧证书与域名不匹配、构建失败或数据库备份不可用。

验证包括：Git 拓扑与 diff、Java `clean verify`、Python `pytest --cache-clear`、Web 全量测试与 production build、生产 Compose 配置、TLS 公共主机与 Nginx 契约、核心跨服务 smoke、Gitleaks、Pi V4-pro Milestone Review；生产侧记录备份、服务器 commit、容器健康、HTTP/HTTPS 边界、TLS timer 和首次证书状态。无法在本地替代的公网 DNS/ACME 结果必须在生产真实执行或明确列为用户必需步骤。

## 回滚

生产 `update.sh` 在更新前写入 previous-release 并创建 PostgreSQL 备份。部署或核心检查失败时，使用既有 `rollback.sh` 回到上一提交并再次运行健康检查；没有 Schema 迁移，证书、命名卷和数据库备份均保留。GitHub `main` 不回写历史，必要时以新的 revert/修复提交处理。

## 实施与证据

待合并、验证、审核和生产发布后回填。
