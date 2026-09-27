# Windows 通过本机代理恢复 GitHub SSH 推送

- **状态**：Implemented
- **适用环境**：Windows、Git for Windows、本机 HTTP 代理
- **最后验证**：2026-09-28

## 何时使用

当仓库使用 GitHub SSH remote，并出现下列组合症状时使用本手册：

| 现象 | 说明 |
| --- | --- |
| `ssh -T -o ConnectTimeout=15 git@github.com` 超时 | 本机 SSH 没有成功直连 GitHub |
| Docker Desktop 已配置代理且能拉镜像，Git 仍超时 | Docker 代理只服务 Docker 自己，不会自动传给 Windows OpenSSH |
| HTTPS push 要求输入 GitHub Username，非交互环境随后失败 | HTTPS 已到达 GitHub，但本机没有可用的 HTTPS 凭据 |
| 直接带 `ProxyCommand` 的 `ssh -T` 成功，Git push 却提示 `exec is not recognized` | Git 调用了另一套 SSH，或让 PowerShell 错误解释了代理命令 |

本文记录的是 2026-09-28 在本仓库实际恢复并完成远端 SHA 核验的方法。示例代理为 `127.0.0.1:12000`；如果本机代理端口不同，必须替换成真实值。

## 原因

本机同时存在多个独立网络路径：

1. Docker Desktop 使用自己的代理设置。
2. PowerShell 中的 Windows OpenSSH 默认直接访问 GitHub。
3. Git for Windows 可能调用它自带的 SSH，而不是 PowerShell 中测试成功的 Windows OpenSSH。
4. `connect.exe` 可以把 SSH 流量封装到本机 HTTP 代理中。

因此，只在 Docker Desktop 中点击 Apply 不能修复 GitHub SSH 推送。Git push 还需要显式指定 Windows OpenSSH 和 `connect.exe`。

## 安全边界

- 保留原有 `origin`，不把 Token、密码或用户名写进 URL。
- 不使用 `--force`，不执行 rebase、reset 或历史覆盖。
- 不使用 `StrictHostKeyChecking=no` 绕过主机身份校验。
- 默认只对当前命令设置 `core.sshCommand`，不修改全局 Git 配置。
- 不输出私钥内容；文档、日志和提交中不得出现 Token、密码或生产配置。

## 恢复步骤

以下命令在仓库根目录的 PowerShell 中执行。

### 1. 记录现场并确认代理端口

```powershell
git status --short
git branch --show-current
git remote -v

Test-NetConnection 127.0.0.1 -Port 12000
```

如果 `TcpTestSucceeded` 不是 `True`，先确认代理应用已经运行，并找到它提供的 HTTP 代理端口。不要假设所有机器都使用 `12000`。

### 2. 确认所需程序

```powershell
(Get-Command ssh).Source
Test-Path 'C:\Windows\System32\OpenSSH\ssh.exe'
Test-Path 'C:\Program Files\Git\mingw64\bin\connect.exe'
```

本机验证成功时使用的是：

- `C:\Windows\System32\OpenSSH\ssh.exe`
- `C:\Program Files\Git\mingw64\bin\connect.exe`

若任一路径不存在，先查明实际安装位置。不要让 AI 自动下载未知代理工具或替换 SSH 客户端。

### 3. 只读验证 SSH 代理隧道

```powershell
$proxyTunnel='"C:/Program Files/Git/mingw64/bin/connect.exe" -H 127.0.0.1:12000 %h %p'

ssh -T `
  -o "ProxyCommand=$proxyTunnel" `
  -o BatchMode=yes `
  -o ConnectTimeout=20 `
  -o StrictHostKeyChecking=yes `
  git@github.com
```

成功信号类似：

```text
Hi <github-user>! You've successfully authenticated, but GitHub does not provide shell access.
```

GitHub 不提供 SSH shell，所以 `ssh -T` 在显示已认证后通常仍返回退出码 `1`。这里判断成功的依据是认证提示，不能只看退出码。

如果输出 `Permission denied (publickey)`，代理路径已经可达，但 SSH key 没有被 GitHub 接受；检查 `ssh-add -l` 和 GitHub 账户中的公钥配置，不要生成或覆盖现有私钥。

### 4. 强制 Git 使用已验证的 Windows OpenSSH

```powershell
$branch=(git branch --show-current).Trim()
if ([string]::IsNullOrWhiteSpace($branch)) {
    throw '当前处于 detached HEAD；请先确认目标分支。'
}

$gitSshCommand='C:/Windows/System32/OpenSSH/ssh.exe -o "ProxyCommand=C:/PROGRA~1/Git/mingw64/bin/connect.exe -H 127.0.0.1:12000 %h %p" -o BatchMode=yes -o ConnectTimeout=20 -o StrictHostKeyChecking=yes'

git -c "core.sshCommand=$gitSshCommand" push origin "HEAD:refs/heads/$branch"
```

这里使用 `C:/PROGRA~1` 避免 `Program Files` 中的空格被 Git、SSH 和 PowerShell 多层拆分。这个设置只对当前 Git 命令有效，不会污染仓库配置或其他项目。

### 5. 核验远端提交

推送完成并不等于任务结束，必须确认远端分支与本地 `HEAD` 完全一致：

```powershell
$local=(git rev-parse HEAD).Trim()
$remoteLine=(git -c "core.sshCommand=$gitSshCommand" ls-remote --heads origin "refs/heads/$branch").Trim()
$remote=($remoteLine -split '\s+')[0]

if ([string]::IsNullOrWhiteSpace($remote)) {
    throw '远端分支不存在或 ls-remote 未返回 SHA。'
}
if ($local -ne $remote) {
    throw "远端 SHA 与本地 HEAD 不一致：local=$local remote=$remote"
}

"Verified remote commit: $remote"
```

## 常见失败

### `exec is not recognized`

这通常发生在直接 SSH 测试成功后：Git 使用了不同的 SSH 程序，或者 `ProxyCommand` 被 PowerShell 解释。本手册第 4 步通过 `core.sshCommand` 明确指定 Windows OpenSSH，并使用无空格的 `PROGRA~1` 路径。

### 代理端口拒绝连接

运行：

```powershell
Test-NetConnection 127.0.0.1 -Port 12000
```

确认代理应用仍在运行、端口没有变化，并确认该端口支持 HTTP 代理。Docker 能拉镜像不能代替这项检查。

### 主机密钥校验失败

停止推送，核对 GitHub 官方公布的 SSH host key fingerprint 和本机 `known_hosts`。不要通过关闭 `StrictHostKeyChecking` 继续。

### HTTPS 要求 Username 或凭据

这是另一条认证路径。旧版 Git Credential Manager 可能不支持当前登录流程。不要在 URL 中拼 Token，也不要把凭据交给 AI。若 SSH key 已可用，优先继续使用本文的 SSH 代理隧道。

### 远端历史与预期不同

停止，不要 force push。先执行带相同 `core.sshCommand` 的 `git fetch origin`，检查远端分支和本地提交关系，再决定后续操作。

## 给其他 AI 的执行约束

遇到本仓库 GitHub 推送超时，可直接要求 AI 按以下顺序处理：

1. 先保存 `git status --short`、当前分支和 remote，不覆盖用户改动。
2. 检查本机 HTTP 代理端口、Windows OpenSSH 和 Git for Windows 的 `connect.exe`。
3. 用 `ssh -T` 加 `ProxyCommand` 做只读认证探测。
4. 用一次性的 `git -c core.sshCommand=...` 执行普通 push。
5. 用同一 SSH 命令执行 `git ls-remote`，核对本地和远端 SHA。
6. 禁止 force push、关闭 host key 校验、改写 origin、写入凭据或自动安装代理工具。

## 本次真实验证

- 直接 SSH：连接 `github.com:22` 超时。
- HTTPS：能够到达 GitHub，但旧版 Credential Manager 未提供可用的非交互凭据。
- 代理 SSH：GitHub 返回已成功认证提示。
- 普通 Git SSH 代理尝试：因 `exec` 被 PowerShell 错误解释而失败。
- 指定 Windows OpenSSH 的 `core.sshCommand`：非强制 push 成功。
- `git ls-remote`：远端分支 SHA 与本地 `HEAD` 一致。
