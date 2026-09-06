# Pi 一次性只读审核连接指南

- 状态：Accepted
- 适用范围：提交前 DeepSeek Pi V4-pro 代码审核

## 唯一支持的调用链

Codex 完成实现、真实测试、清理和敏感信息扫描后，在创建 Git 提交前调用：

```text
run-review.ps1
  -> AGENTFORGE_PI_CMD 指定的 pi.cmd
  -> deepseek/deepseek-v4-pro
  -> docs/08-reviews/ 下的独立报告
```

Pi 只读取已扫描的阶段 diff、必要接口和结构化测试摘要，不读取完整成功日志，不运行测试、不修改文件、不启动 monitor，也不自动推进阶段。

## 持续授权范围

用户已明确持续授权：当本阶段代码和文档完成，且 Codex 已亲自完成真实测试、清理与敏感信息扫描后，Codex 可直接调用本机已配置的 Pi 启动器，将本阶段源码、配置、测试和文档 diff 发送给外部 DeepSeek Pi V4-pro 做一次只读审核，无需每次重新询问授权。

该授权允许使用本机 Pi 已有的登录状态或凭证完成连接，但不允许读取、打印、复制或把认证凭证内容发送进审核 Prompt。`.env`、API key、访问令牌、密码、私钥和敏感日志始终不得进入 diff 或 Pi 输入；敏感扫描命中时必须在调用外部模型前停止。

## 首次配置启动器路径

历史成功调用不是依赖裸 `pi.cmd`，而是直接使用已知的 `pi.cmd` 绝对路径。仓库不能保存个人绝对路径，因此在本机 PowerShell 中把真实路径持久化到用户环境变量：

```powershell
$piCommand = 'C:\path\to\your\pi.cmd'
if (-not (Test-Path -LiteralPath $piCommand)) { throw "pi.cmd path does not exist" }
[Environment]::SetEnvironmentVariable('AGENTFORGE_PI_CMD', $piCommand, 'User')
$env:AGENTFORGE_PI_CMD = $piCommand
```

`User` 级环境变量供之后启动的 Codex/PowerShell 进程继承；最后一行让当前 PowerShell 立即可用。设置后应重新启动 Codex，避免旧进程继续使用旧环境。

## 十秒预检

每次审核前只执行以下检查，不搜索整个磁盘：

```powershell
$piCommand = $env:AGENTFORGE_PI_CMD
if ([string]::IsNullOrWhiteSpace($piCommand) -or -not (Test-Path -LiteralPath $piCommand)) {
    $piInPath = Get-Command pi.cmd -ErrorAction SilentlyContinue
    if ($null -eq $piInPath) {
        throw 'Pi launcher is not configured. Set AGENTFORGE_PI_CMD and restart Codex.'
    }
    $piCommand = $piInPath.Source
}

$catalog = (& $piCommand --list-models v4-pro 2>&1) -join "`n"
if ($LASTEXITCODE -ne 0) { throw "Pi model catalog failed with exit code $LASTEXITCODE" }
if ($catalog -notmatch '(?m)\bdeepseek\b.*\bdeepseek-v4-pro\b') {
    throw 'deepseek/deepseek-v4-pro is absent from the Pi model catalog.'
}
$catalog
```

真实目录表必须在同一行包含 provider 和 model：

```text
deepseek  deepseek-v4-pro
```

审核命令使用的完整模型选择器则是 `deepseek/deepseek-v4-pro`；表格显示格式和命令选择器格式不同，不应混用。

启动器缺失、模型目录非零退出或未列出该模型时，立即停止并请用户处理。禁止尝试安装 Pi、猜测多个路径、降级到 Flash 或循环重试。

## 提交前审核

调用时显式选择 `-ReviewMode Diff` 或 `-ReviewMode Milestone`。Diff 模式只审核本次差异与显式必要上下文；Milestone 模式可通过 `-ContextFiles` 加入产品路线、节点定义或进度文档。上下文必须是仓库内已跟踪的非敏感文本文件，并受长度和敏感扫描限制。

默认情况下，新文件必须先用 intent-to-add 纳入工作树 diff，但这不会暂存内容：

```powershell
git add -N -- <new-files>
.\scripts\agent-bridge\run-review.ps1 `
    -StageName <stage-name> `
    -BaseRef HEAD `
    -TargetRef WORKTREE `
    -Attempt 1 `
    -TimeoutSeconds 600
```

如果工作树还保留用户无关的未暂存修改，应只暂存本次已扫描范围并把 `TargetRef` 设为 `INDEX`。该模式只读取 `git diff --cached HEAD`，避免移动、丢弃或把用户文件发送给外部审核：

```powershell
.\scripts\agent-bridge\run-review.ps1 `
    -StageName <stage-name> `
    -BaseRef HEAD `
    -TargetRef INDEX `
    -ReviewMode Diff `
    -Attempt 1 `
    -TimeoutSeconds 600
```

节点级示例：

```powershell
.\scripts\agent-bridge\run-review.ps1 `
    -StageName <milestone-name> `
    -BaseRef HEAD `
    -TargetRef INDEX `
    -ReviewMode Milestone `
    -ContextFiles docs/01-product/roadmap.md,docs/01-product/product-overview.md `
    -Attempt 1 `
    -TimeoutSeconds 900
```

审核报告生成在 `docs/08-reviews/`。Codex 只处理可复现的严重缺陷、不可运行代码、重大安全/数据一致性问题、真实冲突、架构边界破坏或明显偏离目标；风格和未来优化建议记录即可。通过后回填变更记录，再创建提交。只有 Pi 提出并由 Codex 证实的阻塞项修复后才运行下一次 attempt；纯建议不触发复审。

如果 Pi 因登录、额度或环境问题失败，保留失败证据并立即请用户介入。只有用户针对本次变更明确豁免，才允许跳过提交前 Pi 审核；豁免必须写入变更记录。

## 禁止入口

- 禁止 `review-loop.ps1 -OnCodexWake`。
- 禁止 `Start-BridgeMonitor.ps1` 和任何后台轮询。
- 禁止 `run-validation.ps1`；测试只能由 Codex 运行。
- 禁止让 Pi 自动提交、推送或开始下一阶段。
