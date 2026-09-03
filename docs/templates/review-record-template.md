# 代码审查报告：[阶段 / 主题名称]

- 日期：YYYY-MM-DD
- 审查阶段：V1 / Day X
- 审查对象：Git Commit `[commit-sha]` 或当前工作区 Diff
- 审查工具：Pi Agent (DeepSeek)
- 状态：PENDING_CODEX_FIX / RESOLVED / ACCEPTED_WITH_EXCLUSIONS

## 1. 审查概述

- **总体结论**：[通过 / 存在阻断性问题需修复 / 建议性改进]
- **审查范围**：本次提交或阶段涉及的核心模块（如 `services/core-api` 等）。
- **审查重点**：真实 Bug、权限与数据隔离绕过、API 契约不一致、并发/幂等缺陷、遗漏测试、阶段边界越界。

## 2. 详细发现清单

| ID | 严重级别 | 文件路径 | 行号 | 核心问题 | 状态 (待处理/已修复/已豁免) |
|---|---|---|---|---|---|
| ISSUE-01 | CRITICAL / HIGH / MEDIUM / LOW / SUGGESTION | `path/to/file` | L10-L25 | 简要说明 | 待处理 |

---

### ISSUE-01: [简要标题]

- **严重级别 (Severity)**: CRITICAL / HIGH / MEDIUM / LOW / SUGGESTION
- **文件位置 (File & Line)**: `path/to/file:Lxx-Lyy`
- **代码证据 (Evidence)**:
```java
// 截取引发问题的关键代码
```
- **问题分析 (Description)**: 详细描述为什么这里存在 Bug、安全漏洞或违背契约。
- **修复建议 (Suggested Fix)**:
```java
// 建议的修复方式
```

---

## 3. 主开发 (Codex) 评估与修复回填

Codex 在收到本报告后，需对上述 Issue 逐一研判并回填本节：

| Issue ID | 研判结果 (采纳/误报豁免/下阶段处理) | 修复说明 / 技术依据 | 验证测试用例 |
|---|---|---|---|
| ISSUE-01 | 采纳并修复 | 修正了 ...，避免了 ... | `SomeTest#testCase` |

## 4. 最终核验记录

- 修复后复核结果：
- 复核时间：
- 审查归档状态：
