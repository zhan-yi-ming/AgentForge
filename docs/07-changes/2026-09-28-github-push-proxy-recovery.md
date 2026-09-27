# GitHub 推送代理恢复手册

- **日期**：2026-09-28
- **状态**：Implemented
- **风险等级**：L0（仅文档）

## 背景

Windows 本机已经启用代理且 Docker Desktop 可以通过代理下载镜像，但 GitHub SSH 仍可能直接连接超时；切换 HTTPS 后又可能因为本机 Git Credential Manager 过旧或没有有效凭据而无法非交互推送。此前 V3-04 的实际恢复路径是让 Windows OpenSSH 通过 Git for Windows 自带的 `connect.exe` 进入本机 HTTP 代理，并用一次性的 `core.sshCommand` 完成非强制推送。

这类故障会在不同 AI 会话中重复发生，需要把经过本机验证的判断方式、命令和安全边界保存为仓库运维文档。

## 变更范围

1. 新增 `docs/06-operations/github-push-proxy-troubleshooting.md`，记录症状判断、代理隧道验证、非强制推送和远端 SHA 核验。
2. 在根 `README.md` 与 `docs/README.md` 增加显眼入口。
3. 在 `docs/07-changes/README.md` 登记本次变更。

## 非目标

- 不修改 Git remote、全局 Git 配置、SSH key、代理软件或 Docker Desktop 配置。
- 不安装新工具，不创建或记录 GitHub Token、密码、私钥。
- 不修改产品代码、V3 Node 范围或运行时行为。

## Preflight

- 已通过相同的 Windows OpenSSH + HTTP 代理隧道执行 `git fetch origin`，退出码为 `0`。
- 当前分支：`codex/v3-04-graph-domain-model`；基线提交：`716edb825593f947ec1844c0712955a05b3ddb2d`。
- 保留并排除用户已有的 unstaged、untracked 内容，不纳入本次文档提交。

## 计划验证

- 运行文档范围 Change Gate 规划。
- 以只读 `ssh -T` 验证代理隧道；GitHub 返回已认证提示时，其退出码 `1` 按 SSH 探测语义记录。
- 以同一 `core.sshCommand` 执行 `git ls-remote`，核对远端分支 SHA。
- 运行 Markdown 相对链接检查、`git diff --check`、暂存范围检查和敏感信息扫描。
- L0 文档变更按项目规则不调用 Pi。

## 验证回填

- 门禁规划：`plan-change-gates.ps1` 退出码 `0`，结果为 `L0 / Docs`，要求 `diff-check`、`docs-consistency`、`gitleaks-final`，Review 为 `None`。
- SSH 代理探测：Windows OpenSSH 通过 `connect.exe -H 127.0.0.1:12000` 收到 GitHub `successfully authenticated`，预期 shell 退出码为 `1`。
- 远端只读核验：相同 `core.sshCommand` 下的 `git ls-remote` 退出码 `0`，远端分支与基线本地 `HEAD` 均为 `716edb825593f947ec1844c0712955a05b3ddb2d`。
- 文档检查：新增文件、根 README、文档中心和变更索引入口均存在；`git diff --check` 退出码 `0`。
- 敏感扫描：本机 PATH 未安装独立 `gitleaks`；改用项目既有 `zricethezav/gitleaks:v8.30.1` 扫描暂存差异约 `10.44 KB`，退出码 `0`，`no leaks found`。
- 本次为 L0 文档变更，按规则不调用 Pi；未运行 Java、Python、Web 或 Compose 测试。
