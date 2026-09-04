# Day 1 / Day 2 Attempt 2 集中收口

- 日期：2026-09-04
- 状态：Implemented（Pi 本地集中验证通过，远端 CI 待推送后核验）
- 范围：Codex-Pi 审查状态机、CI 门禁、乐观锁 HTTP 契约与文档入口

## 背景

提交 `aa49fb8` 已携带 `Review-Fixes: review-visibility-and-liveness`，但该阶段由早期自动发现产生，只存在于 `.review-loop-state.json`，不在版本化历史阶段列表。当前循环只遍历版本化 definitions；当提交含 trailer 时又不会创建自动阶段，因此合法修复提交无法触发该状态阶段的下一轮审查。

## 目标

- 对 `Review-Fixes` 明确引用、且已存在于运行时状态的阶段，将其临时定义加入本轮遍历。
- 保持未知 stage ID 拒绝、三次上限、祖先关系和只读 Pi 审查约束不变。
- 增加回归用例，证明 state-only 阶段可被调度，未引用的 state-only 阶段不会被误调度。
- 将 CI 集成测试断言改为“至少 2 项且零跳过”，并用明确的报告查找与错误信息降低重命名脆弱性。
- 为 Spring `OptimisticLockingFailureException` 增加统一 HTTP 409 映射与 MVC 契约测试，覆盖事务提交阶段才抛出的并发冲突。
- 更新 `AGENTS.md` 当前变更指针；以现有独占文件句柄、原子替换与 BUSY 测试证据关闭状态锁误报。

## 影响与回滚

不改变业务 API 的既有 409 契约、数据模型或 Day 3 范围，只补足框架异常映射与门禁健壮性。回滚时恢复本批脚本、CI 与异常处理变化，并保留本记录与 Pi 报告。

## 验证计划

由 Pi DeepSeek V4-pro 执行 Windows PowerShell 5/7 的 `Test-ReviewBridge.ps1`，并运行针对实际 `aa49fb8` trailer 的 DryRun/唤醒检查。Codex 不执行测试。

## 实现

- `review-state.ps1` 新增纯函数 `Add-ExplicitStateStageDefinitions`，只把当前 trailer 明确引用的 state-only 阶段补入 definitions。
- `review-loop.ps1` 在未知 ID 校验通过后调用该函数，再进入统一调度分支。
- `Test-ReviewBridge.ps1` 增加显式引用会加入、未引用不会加入两项回归断言，总检查数调整为 14。
- `prompts/review-state-only-stage-validation.md` 固定 Pi 的 PowerShell 5/7 回归与真实状态 DryRun 验证命令。
- `.github/workflows/core-api-ci.yml` 用报告 glob、唯一报告断言和 `tests >= 2` 保持零跳过门禁同时允许新增用例。
- `ApiExceptionHandler` 统一把 Spring 乐观锁异常映射为 409；`ResourceApiTest` 增加事务级冲突的 MVC 契约用例。
- 验证 Prompt 扩展为本批次一次性运行 bridge 双版本测试、Core API `verify`、暂存差异与 CI 配置核验。

## Pi 集中验证结果

- `VALIDATION_RESULT: PASS`，报告：`docs/08-reviews/2026-09-04-review-attempt2-batch-validation.md`。
- Windows PowerShell 5 与 PowerShell 7 均为 `Passed=True`、`Checks=14`。
- Core API `verify`：46 项、0 失败、0 错误、2 项跳过；新增 `wikiUpdateMapsDatabaseOptimisticLockToConflict` 已真实执行且未跳过。
- 2 项 PostgreSQL Testcontainers 用例因本机无 Docker 跳过；CI 对唯一报告、`tests >= 2`、`skipped == 0`、0 失败/0 错误进行强制断言。
- DryRun 保持历史阶段为 `WAITING_FOR_CODEX_FIX`，未修改状态；新提交携带 trailer 后由 monitor 触发 Attempt 3。
- `%TEMP%\agentforge-review-test-*` 无残留，未访问生产数据或执行 Git 写操作。
