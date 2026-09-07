# 居中 Demo 体验生产发布

- 状态：In Progress
- 日期：2026-09-06
- 目标环境：阿里云中国香港单机生产 Demo
- 发布范围：`feature/centered-chat-onboarding` 相对当前生产 `main@0ea380a`

## 背景

V1.2 已在生产运行，后续完成的居中工作区、首次新手引导和公开 Demo 账号契约已在隔离分支 `feature/centered-chat-onboarding` 验证并通过 Pi Diff Review，但尚未合并到 `dev` / `main` 或部署。用户已明确授权把这些近期改动发布到生产，同时不得扩大到无关生产变更。

## 范围与影响

- 将已验证的居中 Demo 提交及本发布记录先同步到 `dev`，再按 `feature/* -> dev -> main -> production` 合并到 `main`。
- 为本次生产版本创建可读、不可移动的 annotated tag。
- 运行 Web 全量测试、production build、公开 Demo 契约检查、生产配置检查、Shell 语法检查和敏感信息扫描。
- 在生产运行既有 `update.sh`：记录上一 release、先备份 PostgreSQL、快进 `main`、顺序重建服务并执行 HTTPS / 未认证边界健康检查。
- 把服务器私有 `.env` 中的两个固定 Demo 配置同步到已公开、无敏感数据的受限 USER 账号，并运行 `seed-demo.sh` 创建或复用 workspace；不输出密码、token 或模型 key。
- 部署后核验服务器 commit、五个容器健康状态、公开登录页、固定 Demo 登录、项目隔离/角色、真实流式回答、注册关闭、非网关端口和 TLS timer。

## 非目标

- 不修改数据库 Schema，不删除或迁移已有用户数据，不扩大权限、端口或安全组。
- 不修改模型提供方或真实服务密钥，不输出或提交生产 `.env`、凭据文件、访问 token 和敏感日志。
- 不包含工作树中用户已有的历史文档修改或未跟踪 DOCX，也不开始 V2-01 实现。

## 风险评级与验证计划

本次发布的软件改动已评级 L2：影响 React UI、浏览器本地状态和生产 Demo seed/校验脚本，但不改变 Java/Python API、Schema 或权限模型。生产发布动作本身涉及真实外部状态，因此按 L3 操作严谨度执行发布前全量相关门禁、备份、健康检查和核心业务回归；复用该功能提交已完成且仍适用于同一 diff 的 Pi PASS，不重复把完全相同的业务 diff 发送给 Pi。

计划记录每条真实命令的退出码、工具版本、测试通过/失败/跳过数量、备份与清理证据。Java/Python 源码与契约没有变化，发布前不重复其全量测试；生产真实登录、项目读取和流式 Agent smoke 覆盖运行链路。

## 回滚思路

`update.sh` 在更新前保存 commit 并创建 PostgreSQL 备份。若构建、启动或核心 smoke 失败，使用既有 `rollback.sh` 回到保存的 `main@0ea380a`，随后重新运行健康检查；命名 volume 与备份保留。公开 Demo USER 属普通、无敏感数据账号，本次不自动删除旧账号。由于没有 Schema 迁移，应用级回滚不需要数据库降级。

## 实施与验证回填

待发布完成后回填实际 dev/main/tag、备份、部署、生产 smoke、敏感扫描、清理和限制。
