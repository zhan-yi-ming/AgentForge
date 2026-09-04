# Pi 代码审查报告：review-visibility-and-liveness / Attempt 1

- 日期：2026-09-04
- 审查阶段：review-visibility-and-liveness
- 审查对象：de08b54965a829bbc5bcb2625b5e630ecbc41c64（基线：49b893898e5a699073b41f17539d6401adc91117）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- REVIEW_RESULT: NEEDS_FIX
- Pi 进程超时上限：300 秒

---

<system-warning>The user provided a file at `C:\Users\86134\AppData\Local\Temp\tmpscv2xt.tmp.md` containing review instructions. My job is to perform a read-only code review of the specified commit and output a Markdown review report. Let me analyze the diff carefully.</system-warning>

**REVIEW_RESULT: NEEDS_FIX**

# AgentForge 独立代码审查报告

- **审查阶段**: review-visibility-and-liveness
- **审查轮次**: 1 / 3
- **审查目标 commit**: de08b54965a829bbc5bcb2625b5e630ecbc41c64
- **审查员模型**: DeepSeek V4-pro（依托独立 Reviewer 角色）
- **审查方式**: 只读，依据 Git Diff + 文件清单
- **改动规模**: 7 文件，+13 / −5

---

## 1. 概述与总体结论

**结论：需修复后交付（NEEDS_FIX）**

本次 commit 的核心意图是：让所有 `scripts/agent-bridge/*.ps1` 以 **UTF-8 BOM** 保存，从而同时兼容 Windows PowerShell 5（`powershell.exe`）与 PowerShell 7（`pwsh.exe`）对中文脚本的解码。文档部分新增了“Windows PowerShell 兼容性修补”章节，说明验证方式与背景。

从设计与意图上看，这是一个**低风险、聚焦单一目的的提交**，不涉及业务逻辑变更，不触碰 Spring Security / JWT / DTO / Controller 等 V1 后端组件，也未引入任何被禁止的 V2/V3 组件（无 Neo4j/GraphRAG、无 Langfuse、无 LiteLLM、无 MCP）。**符合 V1 边界要求。**

但是，审查发现一个**高严重级别、会影响仓库可移植性**的问题：

> 将含 BOM 的 UTF-8 文件提交进 Git，在某些工具链（例如严格 YAML/JSON 解析、部分 Unix 环境、跨平台 CI、以及需要把 `.ps1` 作为字节流读取的处理器）中会读取到 U+FEFF 字符。U+FEFF 位于文件最开头，虽然 PowerShell 通常能跳过它，但它会污染其它下游处理（例如本文当次 diff 中显示的首行乱码 `锘縖CmdletBinding()]`、`锘?#`）。尤其当这些脚本被其他系统复制、合并、生成哈希或直接作为文档示例展示时，会出现不可预期的结果。

此外需要纠正文档中的一个**技术性表述错误**：`.NET Framework` 4.x（Windows PowerShell 5 所使用的运行时）在 `File.ReadAllText` 等默认路径中通常也能处理 BOM，但文档并未解释为什么必须采用 BOM 而不是直接加 `-Encoding` 参数或检测编码，这使“不要用编辑器保存为无 BOM 的 UTF-8”的强制要求缺少可执行性。同时，文档声称“已使用 `powershell.exe -NoProfile -File` 成功运行”，但**未将验证结果固化进任何自动化测试**，属于测试缺口。

以下是详细发现清单。

---

## 2. 详细发现清单

| ID | 严重级别 | 文件 | 行号 | 核心问题 |
|----|---------|------|------|---------|
| R1 | High | 全部 5 个 `.ps1` 文件 | 每文件第 1 行 | 引入 BOM 是依赖“文件字节级特征”的脆弱方案；Git diff 已显示首行出现 `锘?` 乱码，说明污染已实际发生；未提供无 BOM 的替代方案 |
| R2 | Medium | `docs/06-operations/review-orchestration.md` | 新增 1 行 | 声称“所有 bridge 脚本以 UTF-8 BOM 保存”即“可直接运行”，缩小了事实：BOM 仅解决 Windows PowerShell 5 的编码解码问题，未解决脚本本身在无 profile、set-strictmode 等环境下的边界问题 |
| R3 | Medium | `docs/07-changes/2026-09-04-review-visibility-and-liveness.md` | 新增段落 | 验证方法“同时使用 `powershell.exe -File` 和 `pwsh.exe -File` 解析与运行只读状态命令”未包含任何自动化回归测试，将来回归时无法被 CI 捕获 |
| R4 | Low | `scripts/agent-bridge/Show-ReviewStatus.ps1` | 第 1 行 | 与其它 `.ps1` 不同，该文件被 BOM 污染后 `[CmdletBinding()]` 前出现 `锘縖`，若下游有基于首行签名的校验会误判 |
| R5 | Low | `docs/06-operations/review-orchestration.md` | 新增 1 行 | “不要用编辑器把它们保存为无 BOM 的 UTF-8”对开发者缺乏可操作性；未给出如何检测 BOM、如何在 PowerShell 中批量转换、如何配置编辑器保持 BOM 的操作步骤 |

> 说明：本轮改动极小（+13/−5），不存在空指针、并发控制、权限越权、API 契约等后端类问题，因此未产生相关编号的发现，避免“没有明确证据时凑数”。

---

## 3. 逐个 Issue 展开

### R1 — BOM 方案污染文件内容且缺乏替代方案

- **Severity**: High
- **File & Line**:
  - `scripts/agent-bridge/Show-ReviewStatus.ps1:1`
  - `scripts/agent-bridge/Start-BridgeMonitor.ps1:1`
  - `scripts/agent-bridge/bridge-monitor.ps1:1`
  - `scripts/agent-bridge/review-loop.ps1:1`
  - `scripts/agent-bridge/run-review.ps1:1`
- **Evidence**（来自 commit diff，原文保留乱码以证明字节污染）:

  ```diff
  -[CmdletBinding()]
  +锘縖CmdletBinding()]
  ```

  ```diff
  -<#
  +锘?#
  ```

- **Description**:
  `锘?` 是 UTF-8 BOM（EF BB BF）被按非 BOM 方式解码后得到的典型乱码。Diff 中可见，`[CmdletBinding()]` 的 `[` 前被插入了三字节标记，`<#` 的 `<` 前也出现了 `锘?`。这证明：
  1. **BOM 已经实际改变了文件内容**（不是纯元数据）；
  2. 任何不主动识别 BOM 的工具（如某些 Unix 工具、`grep` 风格扫描器、严格的行首匹配）都会出错；
  3. 依赖“所有开发者/编辑器都默认保留 BOM”是一个不可控的人工约束。

  Git 仓库通常建议**源代码文件使用无 BOM 的 UTF-8**，把应用层是否能正确处理交给运行时编码检测或显式参数，而不是把 BOM 写进源码。此处上下文是脚本需要被 `powershell.exe` 与 `pwsh.exe` 分别执行，处理中文内码的坏码问题。更稳健的做法是让两个运行时**都显式指定 UTF-8**，而非通过 BOM 隐式处理。

- **Suggested Fix**:
  1. **保留无 BOM UTF-8 源码，运行时显式声明编码**：
     在 `Start-BridgeMonitor.ps1` 中使用 `powershell.exe` 时，避免其对中文脚本解码失败，可在启动时加入 `-EncodedCommand` 或通过 `powershell.exe -Command` 内嵌 `[Console]::InputEncoding/OutputEncoding`；或如果必须使用 `powershell.exe -File`，则在每个 `.ps1` 文件的开头动态检测是否 BOM——但更简单的做法是用 `pwsh.exe` 作为唯一运行时（Windows PowerShell 5 仅为兼容性回退），并将其编码处理集中到 wrapper。
  2. 若团队坚持 BOM 方案，应：
     - 将 `.editorconfig` 或 `.gitattributes` 加入控制，并在文档中提供“检测 BOM”的命令，例如：
       ```powershell
       $bytes = [System.IO.File]::ReadAllBytes($path)
       ($bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF)
       ```
     - 在提交前添加验证脚本，确保所有 `.ps1` 均含 BOM（或均不含），避免混用。

---

### R2 — 文档表述缩小了兼容性问题的范围

- **Severity**: Medium
- **File & Line**: `docs/06-operations/review-orchestration.md`（新增行，对应 diff 中的 `+` 行）
- **Evidence**:

  ```diff
  +所有 bridge 脚本以 UTF-8 BOM 保存，因此可以 Windows PowerShell 5 或 PowerShell 7 直接运行；不要用编辑器把它们保存为无 BOM 的 UTF-8。
  ```

- **Description**:
  该句将“以 UTF-8 BOM 保存”包装为“因此可以直接运行”的充分条件。实际上，脚本能否正常运行还依赖：
  - 执行策略（`Set-ExecutionPolicy`）；
  - 是否存在 profile 干扰（`-NoProfile` 是否被 wrapper 强制使用）；
  - PowerShell 版本中 `ConvertFrom-Json`、`Get-Process` 等 cmdlet 的行为差异；
  - 当前工作目录与 `$PSScriptRoot` 的相对路径解析。
  BOM 只解决了中文乱码的**解码**问题，并未证明“直接运行”整体成立。表述不准确可能误导排错路径。

- **Suggested Fix**:
  改为更精确的表述，例如：

  ```text
  所有 bridge 脚本以 UTF-8 BOM 保存，以避免 Windows PowerShell 5 在无 BOM UTF-8 下把中文内容解码损坏；已分别用 `powershell.exe -NoProfile -File` 和 `pwsh.exe -File` 验证可运行只读状态命令。除此之外，运行仍需满足执行策略、无 profile 干扰等常规条件。不要用编辑器把它们改存为无 BOM 的 UTF-8。
  ```

---

### R3 — 缺少自动化回归测试固化 Windows PowerShell 5 兼容性

- **Severity**: Medium
- **File & Line**: `docs/07-changes/2026-09-04-review-visibility-and-liveness.md`（新增“Windows PowerShell 兼容性修补”章节）
- **Evidence**:

  ```text
  已使用 `powershell.exe -NoProfile -File scripts/agent-bridge/Show-ReviewStatus.ps1` 成功显示状态，并用 Windows PowerShell AST 解析 `Start-BridgeMonitor.ps1` 通过。
  ```

- **Description**:
  文档只描述了**一次性手工验证**，没有对应自动化测试。由于本 commit 的核心诉求就是“不能在 Windows PowerShell 5 下乱码退出”，且后续任何编辑器或 CI 都可能意外去掉 BOM，缺乏回归保护会使同类问题复发。这也与项目文档中“验证 PowerShell AST、状态脚本、可见窗口…”的验证要求不一致（本次没有新增任何测试文件或测试脚本）。

- **Suggested Fix**:
  1. 新增一个测试脚本（如 `scripts/agent-bridge/test-compat.ps1`）或在现有验证脚本中加入两个命令：
     - `powershell.exe -NoProfile -Command "$tokens=$null; $errors=$null; [System.Management.Automation.Language.Parser]::ParseFile('...',[ref]$tokens,[ref]$errors)"` 断言 `$errors.Count -eq 0`；
     - `powershell.exe -NoProfile -File Show-ReviewStatus.ps1` 断言退出码为 0 且 JSON 含 `monitorPid` 字段。
  2. 将该测试纳入提交前验证流程，并把结果写回变更文档。

---

### R4 — `Show-ReviewStatus.ps1` 首行 BOM 污染签名

- **Severity**: Low
- **File & Line**: `scripts/agent-bridge/Show-ReviewStatus.ps1:1`
- **Evidence**:

  ```diff
  -[CmdletBinding()]
  +锘縖CmdletBinding()]
  ```

- **Description**:
  其它 4 个脚本的 BOM 出现在注释块 `<#` 前，而 `Show-ReviewStatus.ps1` 把 BOM 直接加在了 `[CmdletBinding()]` 前。虽然 PowerShell 解释器最终能解析（BOM 被忽略），但若存在基于首行文本的工具链（例如某些 lint、指纹生成、文档生成器或统一的 `[CmdletBinding()]` 行匹配），会因首字符不是 `[` 而失败。该文件与其它文件的处理一致性较差。

- **Suggested Fix**:
  若最终保留 BOM 方案，建议统一在文件首部增加注释头（与其它脚本一致），或者确保所有后缀签名检查在读取文件时显式调用 `Remove` 或检测 BOM 后跳过。若切换到无 BOM + 显式编码方案，则本问题自然消失。

---

### R5 — “勿保存为无 BOM”缺少可执行的操作指引

- **Severity**: Low
- **File & Line**: `docs/06-operations/review-orchestration.md`（新增 1 行）
- **Evidence**:

  ```text
  ...不要用编辑器把它们保存为无 BOM 的 UTF-8。
  ```

- **Description**:
  该要求面向维护者，但没有给出：
  - 如何在 VS Code / PowerShell ISE 等常用编辑器中确保保存时保留 BOM；
  - 如何验证一个文件当前是否含 BOM（是否要读前三个字节）；
  - 若已经错误保存为无 BOM，如何批量修复（例如用 `[System.IO.File]::WriteAllText($path, $text, [System.Text.UTF8Encoding]::new($true))`）。
  缺少这些可执行说明会使约束沦为口头约定。

- **Suggested Fix**:
  在文档同一小节补充：
  1. 检测命令：
     ```powershell
     $b=[IO.File]::ReadAllBytes($p)[0..2]
     $b[0] -eq 0xEF -and $b[1] -eq 0xBB -and $b[2] -eq 0xBF
     ```
  2. 修复命令：
     ```powershell
     $t=[IO.File]::ReadAllText($p,[Text.UTF8Encoding]::new($false))
     [IO.File]::WriteAllText($p,$t,[Text.UTF8Encoding]::new($true))
     ```
  3. VS Code 提示：在设置中开启 `files.encoding: "utf8bom"`（注意仅适用于本项目文件）。

---

## 4. 主开发 (Codex) 评估回填区

| Issue ID | Codex 是否认可 | Codex 处理意见 | 状态 |
|----------|---------------|----------------|------|
| R1 | 不认可 | Windows PowerShell 5 对无 BOM UTF-8 源码按本机代码页读取，中文异常文本会被破坏；运行时设置 Console 编码不能改变脚本文件的解析编码。BOM 是该双运行时约束下的明确兼容方案，且只用于 `.ps1`。 | 保留 BOM |
| R2 | 认可 | 运维文档已收窄表述：BOM 只解决源码解码，执行策略、无 Profile 和依赖仍需满足。 | 已修复 |
| R3 | 认可 | 新增 `Test-ReviewBridge.ps1`，并已分别用 Windows PowerShell 5 与 PowerShell 7 执行。 | 已修复 |
| R4 | 不认可 | BOM 是编码签名，不是 PowerShell 解析后的源码字符；PS5/PS7 AST 和执行测试均通过。仓库没有依赖原始首字节匹配的工具。 | 无需修改 |
| R5 | 认可 | 运维文档新增批量恢复 UTF-8 BOM 的命令；回归脚本增加字节签名检查。 | 已修复 |

---

## 5. 审查结论摘要

- **不涉及** Spring Security / JWT / DTO 契约 / Controller 状态码 / 乐观锁并发等后端问题，本 commit 范围限定于脚本编码与文档。
- **未发现**空指针、未捕获异常、代码注入等运行时安全漏洞。
- **未引入**任何被禁止的 V2/V3 组件。
- **核心问题**：依赖文件字节级 BOM 作为跨运行时兼容方案，脆弱、污染源码、缺少自动化回归保障；文档表述将其过于绝对化。
- **建议**：在**保留 BOM 方案的前提下**，至少补充自动化兼容性测试（R3）、统一文档精确表述（R2）、提供 BOM 检测与修复操作指引（R5）；**理想方案**是从 BOM 切换到无 BOM + 运行时显式编码声明（R1）。

**最终结论：NEEDS_FIX（需修复后交付）**。
