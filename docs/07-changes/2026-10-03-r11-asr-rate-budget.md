# R11 ASR 请求频率与 Nginx 限流预算

- 日期：2026-10-03
- 状态：Completed
- 风险：L2（公网网关配置、语音工作流）
- 影响域：Web、Nginx、ASR Docs

## 问题确认

深层审查 R11 成立。16 kHz PCM16 以 16,000 字节批次约每秒上传两次，同时每 500 ms 查询一次状态，单录音约 4 req/s；现有普通 API 区仅 120 req/min 且 burst 10，持续录音会稳定耗尽 burst 并返回 429。

## 目标与边界

- Web 以 48,000 字节触发上传，并把任何积压拆成不超过 64,000 字节且保持偶数字节的顺序队列；停止时先排空队列再 finish。
- 状态轮询改为 1,500 ms；单录音稳态约 1.34 req/s，仍保留实时预览。
- Nginx 为 ASR session 路径设置独立 `asr_per_ip` 区（600 req/min、burst 30），不消耗普通 API 区；普通 `/api/`、登录和 Chat 限流保持不变。
- 会话开始仍消费 AI 日额度；Java/Python 的 64,000 字节单包、总时长和并发限制不放宽。

## 约定测试 seam

Web DOM/API seam 验证批次上限、顺序与轮询节奏；生产 Nginx seam 验证 ASR 精确路径命中独立区、普通 API 仍命中原区，并以真实 Nginx 连续至少 60 秒模拟同 IP 两个录音用户及并行普通 API。

## 验证计划

先新增会在当前 16,000/500 ms 行为和缺失独立 location 下失败的测试，再最小修改 Web/Nginx；运行 Web 全量与生产构建、Nginx 配置/真实 60 秒 smoke、门禁规划、diff/敏感扫描与本批 Pi Milestone Review。

## 验证回填

- TDD 红灯：Web 定向测试 6 项中新增预算用例失败，暴露 40,000 字节即上传；Agent 部署模型测试 5 项中新增 Nginx 用例失败，暴露缺失 `asr_per_ip` 区。最小实现后分别 6/6、5/5 通过。
- Web 全量通过：6 files、80 tests；`npm run build` 成功，Vite 转换 295 modules。npm 保留既有 `home` 用户配置弃用警告。
- 真实 Nginx 60 秒 smoke：`asr-nginx-rate-limit.ps1` 通过；同 IP 两个 ASR session 持续请求时普通 API burst 可被原区拒绝，ASR 仍由独立区放行；临时容器与配置已清理。
- 生产 Nginx/TLS 配置门禁通过，包括 PowerShell IPv4/root-domain/existing-www 场景，以及 WSL 下的 IPv4/IPv6/domain issue、renewal、bootstrap、validation、generation、rejection 与健康检查契约。
- Agent 全量通过：222 passed、4 warnings；本批 `plan-change-gates.ps1` 判定 L3 / Milestone Review，`git diff --check` 通过。
- Pi Attempt 1：R11 单项 PASS；批次、偶数字节、顺序队列和独立限流区均被确认正确。缺少额外 Nginx timeout/body 指令仅为未复现的低风险容量建议，不触发本批修改。
- Pi Attempt 2：本批 Milestone Review `PASS`，无必须修改项；同一 Nginx 建议仍为 Low，不触发 Attempt 3。

## 风险与回滚

批次变大会增加单次上传延迟，独立区过宽会放大单 IP 请求量。现有会话日额度、单用户/全局并发、单包与总音频限制继续约束费用；回滚可恢复旧前端节奏与 location，但会重新暴露持续 429。
