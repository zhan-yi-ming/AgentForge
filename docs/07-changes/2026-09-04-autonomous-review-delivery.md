# 自主审查与修复交付循环

- 日期：2026-09-04
- 状态：Proposed
- 阶段：V1 开发治理

## 背景与目标

用户已确认：阶段内允许 Codex 与 Pi 自动完成“开发/修复、提交、Pi 审查”最多三轮；通过后自动排队下一阶段，第三次仍为 `NEEDS_FIX` 才由用户决策。现有 monitor 因 PowerShell 语法错误退出，且仅能启动 Pi，不能安全串行化 Codex 唤醒与 monitor 调用。

## 范围

- 修复 monitor，并为 review-loop 增加跨进程互斥锁、原子状态写入、已登记阶段识别和严格结果契约。
- 对发送至 Pi 的差异执行本地高信噪比密钥扫描；命中即拒绝外发。
- 以 Codex heartbeat 作为“报告完成/等待修复”后的受控恢复执行者；monitor 绝不直接写业务代码。
- Pi 通过后将下一路线图阶段标为可执行；第三次未通过生成带阶段、轮次、报告路径的人工介入记录并停止。

## 非目标

- 不使用 Flash，不让 Pi 修改代码，不引入云队列、数据库或 V2/V3 组件。
- 不自动绕过第三次人工介入，也不发送敏感 diff 给外部模型。

## 验证与回滚

- 验证 PowerShell AST、状态锁、secret 拒绝、monitor 常驻与三次上限；运行相称的 Maven 测试。
- 回滚时暂停 heartbeat、停止 monitor 并回退本变更；业务代码与报告保留。

## 实现与验证结果

- Codex heartbeat `agentforge-autonomous-review-loop` 已启用，按五分钟间隔消费审查状态。
- 修复 bridge-monitor 的 here-string 解析错误，单次模式成功返回 `WAITING_FOR_CODEX_FIX`。
- review-loop 使用独占锁和临时文件原子替换状态；DryRun 不会错误消耗历史 Day 1/Day 2 轮次。
- run-review 在调用 Pi 前执行高信噪比敏感信息扫描；未改用 Flash。
