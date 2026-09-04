# Day 1 / Day 2 最终审查证据与 Pi 验证职责

- 日期：2026-09-04
- 状态：Implemented（Pi 本地门禁通过，远端 CI 待推送后核验）
- 范围：Day 1 / Day 2 质量门与 Codex-Pi 分工

## 背景

Pi DeepSeek V4-pro 的第三轮报告未发现新的业务缺陷，但因 PostgreSQL/Testcontainers 用例未实际运行、JWT 密钥校验缺少独立测试、review-loop 关键状态迁移缺少自动化覆盖，以及审查输入出现中文乱码，Day 1 / Day 2 均进入 `HUMAN_REQUIRED`。用户已明确授权继续补齐证据，并要求后续测试设计、执行和测试数据清理由 Pi 主导。

## 目标与范围

- 为 JWT secret 的非法 Base64、长度不足和合法值增加独立测试。
- 把 review outcome 迁移提取为可测试函数，覆盖第三次失败、PASS 幂等和锁占用不增 attempt。
- CI 使用仓库 Maven Wrapper，并检查 Surefire XML 中 PostgreSQL 集成测试为 2 项、0 跳过，防止 Docker 缺失时绿灯掩盖证据缺口。
- 为状态读取提供可注入运行目录，覆盖损坏 JSON 与 `STALLED` 判断。
- 修复 Git diff 进入 Pi 提示词时的 UTF-8 解码边界。
- 新增 Pi 专属 `AGENTS.md` 并由审查、验证启动器显式注入，避免 `--no-context-files` 导致职责规则失效。
- Pi 负责设计复核并实际执行测试：验证会话只允许 read/grep/find/ls 与受限 PowerShell，不提供 edit/write；测试代码由 Pi 给出建议、Codex 按文档落盘，执行与清理由 Pi 完成。
- PostgreSQL 集成测试优先由 GitHub Actions 的 Linux Docker 环境执行；报告引用可核验的 job 结果。

## 非目标

- Pi 不修改业务实现、Git 历史、生产数据或真实环境配置。
- 不为通过审查而降低三次上限或使用 Flash。
- 不开始 Day 3，直到本记录的审查证据闭环。

## 验证与清理

- Pi 执行 PowerShell 5/7 bridge 回归、Core API `mvnw.cmd verify` 并复核 CI 结果；Codex 不执行测试。
- 测试只能使用 Testcontainers、测试 profile 和系统临时目录；Pi 结束后删除本次测试明确创建的临时目录，容器由 Testcontainers 回收。
- 本机 Docker 不可用时不得伪造通过；以远端 CI 的真实 Testcontainers 结果为准。

## Pi 首轮执行发现

Pi 实际执行后发现全新状态目录会拒绝当前提交中的 `review-visibility-and-liveness` trailer：该阶段来自变更记录自动发现，但不在历史注册表和空状态中。采用 Pi 建议的通用修复，将 `docs/07-changes/` 可派生的阶段 ID 纳入已知集合，继续拒绝没有变更记录依据的拼写错误 trailer。修复后由 Pi 重跑同一验证命令。

Pi 提交前门禁进一步发现：Linux CI 中 `mvnw` 缺少执行位、第三轮报告存在尾随空格、Pi 启动器硬编码个人绝对路径，以及变更记录阶段派生未限定日期前缀。修复方案为恢复 Git 执行位、清理空格、以 `AGENTFORGE_PI_CMD`/PATH 解析启动器并收紧为日期命名正则；修复后再次执行门禁。

## 回滚

回退新增测试和状态迁移辅助脚本，恢复 review-loop 内联状态更新；保留 Pi 报告和本记录。若受限测试工具执行越界，立即恢复 `--no-tools` 并停止自动验证。

2026-09-04 用户最终确认将全部测试执行与测试数据清理交给 Pi。Pi 的代码审查会话保持完全只读；独立验证会话仅增加受限 PowerShell 执行能力，仍禁止 edit/write、源码或文档写入以及 Git 写操作。Codex 不再执行测试。

## 本地验证结果

- Pi V4-pro 首轮执行发现全新状态 trailer 识别缺陷；Codex 修复后由 Pi 重跑。
- Windows PowerShell 5：`Passed=True`，`Checks=12`，退出码 0。
- PowerShell 7：`Passed=True`，`Checks=12`，退出码 0。
- Core API `mvnw.cmd verify`：45 项、0 失败、0 错误、2 项跳过；JWT secret 3 项全部真实执行。
- PostgreSQL 2 项因本机 Docker daemon 未运行而跳过；CI 已增加 2 项、0 跳过的强制断言，远端结果待推送后由 Pi 核验。
- Pi 清理确认：`%TEMP%\agentforge-review-test-*` 无残留，未创建 Testcontainers 容器，Maven `target` 保留为 Surefire 证据且被 Git 忽略。
- Pi 最终门禁结论为 `VALIDATION_RESULT: PASS`。门禁同时发现两项提交前整理：从仓库根目录直接调用子目录 `mvnw.cmd` 会错误定位 `.mvn`，因此 Prompt 改为先进入 `services/core-api`；暂存区的第三轮报告仍含旧尾随空格版本，因此提交前重新暂存全部文件。
- 整理后 Pi 对最终暂存区再次执行完整门禁，结论为 `VALIDATION_RESULT: PASS`：`git diff --cached --check` 退出码 0；Windows PowerShell 5 与 PowerShell 7 均为 `Checks=12`、`Passed=True`；Core API `verify` 为 45 项、0 失败、0 错误、2 项因本机无 Docker 跳过；`%TEMP%\agentforge-review-test-*` 残留 0。报告见 `docs/08-reviews/2026-09-04-review-day1-day2-final-pi-validation-rerun.md`。
