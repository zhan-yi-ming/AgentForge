# 本地语音停止后空转写与多余删除请求修复

- 状态：Implemented
- 日期：2026-09-23
- 基线：`761fab6049ca7f8d7ed4f4fbd8ec4c333b9a2f4d`；目标远端 `origin/codex/pre-v3-chat-experience`
- 用户已有改动：旧 Pi 审计记录、`.worktrees/`、产品规划 docx；受 ACL 保护的 pytest 目录均保留。

## 故障信号与可证伪假设

用户停止录音后浏览器显示 `DELETE .../asr/sessions/{id}` 404，且草稿没有文字。用户提供的请求含 Bearer 凭据；本记录不保存、不复述该凭据。假设 A：成功的 finish 已删除服务端会话，Web 再次 cancel 产生多余 404；假设 B：上游未检测到语音时 finish 返回空文本，Web 静默丢弃；假设 C：服务端无法识别有效音频或最终事件未进入草稿。

本机以合成中文语音走真实已配置的 Agent Service 与阿里云链路：start 200、preview 200/23 字、finish 200/23 字、其后 DELETE 404，支持 A，排除当前服务端配置和最终事件解析的普遍性故障。官方协议允许未检测到语音时直接返回 session.finished；当前 Web 对空文本无提示，支持 B。C 仍需用户真实麦克风设备复验。

## 范围与公共 seam

仅修聊天语音输入组件：成功 finish 后不再 cancel 已释放的会话；失败时仍清理；空最终结果明确提示用户重试。以 VoiceInput 可见行为及 API 客户端调用作为已存在的 Web 公共 seam，先红后绿。不改 ASR 公共 HTTP 合约、Java/Python 实现、密钥或配额策略。风险 L1，影响域 Web；按规划器及 Web 测试/构建验证，本地 Web 镜像刷新后供用户麦克风复验。

## 验证回填

- 真实阿里云链路（合成中文语音，不含用户音频）：start 200，16 kHz PCM16 200,404 字节分块上传；preview 200 / 23 字，finish 200 / 23 字，其后取消 404。证实配置和转写事件处理正常，多余 DELETE 可重现。测试音频已清理。
- Web 公共 seam TDD：成功 finish 后不得发 DELETE，先 1 failed 后通过；空最终转写必须显示提示且不写入草稿，先 1 failed 后通过。
- `cd apps/web; npm test -- --run`：Vitest 3.2.7，6 文件 / 72 passed / 0 failed；既有 npm `home` 配置 warning。
- `cd apps/web; npm run build`：TypeScript 与 Vite 构建退出 0。
- `scripts/validation/plan-change-gates.ps1 -BaseRef HEAD -TargetRef WORKTREE -Paths apps/web/src/pages/VoiceInput.tsx,apps/web/tests/voice-input.test.tsx,docs/03-features/voice-input.md,docs/07-changes/2026-09-23-asr-finish-feedback.md -Json`：L1 / Web + Docs / 无 Pi。全工作树模式因用户已有 untracked 文件判为 Unknown/L2；显式路径与人工差异检查确认本次仅 Web + Docs。
- `docker compose --env-file .env -f infra/compose.yaml build web` 与 `up -d --no-deps --no-build web` 成功；`http://127.0.0.1:5173/chat` 返回 200，引用当前构建 JS。
- 真实用户麦克风的输入音量、设备选择及其最终文本仍由用户在浏览器复验。按照阿里云官方协议，无语音时 finish 可能成功但文本为空；本修复给出可见提示。
- Pi：L1 单模块行为修改，按治理规则不调用。
