# 聊天语音输入

- 状态：Implemented
- 阶段：P3-05

仅 `/chat` 会话页显示麦克风操作。用户开始录音后，转写可实时显示；停止时等待最终句并合并到现有可编辑草稿，用户仍需主动发送。录音中不发送聊天消息，也不创建 Action 提案。离开聊天、切项目或取消录音会关闭采集与服务端 ASR 会话。

浏览器把麦克风音频转换为 16 kHz 单声道 PCM16，借已有 Bearer 请求通过 Core API 上传小块和查询文字。Core API 每次验证项目访问并把 actor/project 作为作用域转发给 Agent Service；后者仅用服务端 `AGENTFORGE_AGENT_ASR_*` 配置调用阿里云 Qwen3-ASR-Flash-Realtime WebSocket。阿里云 API Key 不进入浏览器包、URL、响应、日志或聊天历史。会话状态短暂驻留 Python 进程内，服务重启或多实例切换会中断该录音。

实时协议参考官方 `session.update`、`input_audio_buffer.append`、`session.finish` 与最终 `session.finished`；文件转写 HTTP API 因需要公网文件 URL 和异步任务，仅作为方案对照，不在本阶段实现。

ASR 独立配置：`AGENTFORGE_AGENT_ASR_API_KEY`、`AGENTFORGE_AGENT_ASR_WORKSPACE_ID`、`AGENTFORGE_AGENT_ASR_REGION`（默认 `cn-beijing`）。未配置时语音入口返回 503，文字聊天照常运行。每次开始录音消耗现有用户 AI 日配额，单用户同时最多一段录音；全局并发、音频时长和块大小另有限制。
