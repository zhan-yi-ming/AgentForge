# ADR-0007：以 Heartbeat 驱动的 Codex-Pi 自主闭环

- 状态：Accepted
- 日期：2026-09-04

## 决定

monitor 只负责串行触发只读 Pi 审查和写入状态；Codex heartbeat 定期读取权威状态与报告，在 `WAITING_FOR_CODEX_FIX` 时执行文档先行修复、测试、提交。`PASS` 使下一路线图阶段进入队列；第三次 `NEEDS_FIX` 进入 `HUMAN_REQUIRED` 并停止 heartbeat 的业务执行。

## 后果

这让异步审查能恢复 Codex 工作而不授权 Pi 写代码。代价是本机必须保持 Codex heartbeat 可用；互斥锁和原子状态写入防止用户唤醒与 monitor 并发消耗轮次。
