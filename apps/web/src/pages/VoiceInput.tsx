import { useEffect, useRef, useState } from "react";
import type { ApiClient } from "../api";

type Props = {
  api: Pick<ApiClient, "startVoice" | "appendVoiceAudio" | "getVoice" | "finishVoice" | "cancelVoice">;
  projectId: string;
  onTranscript: (text: string) => void;
};

type Capture = {
  sessionId: string;
  stream: MediaStream;
  context: AudioContext;
  source: MediaStreamAudioSourceNode;
  processor: ScriptProcessorNode;
  pending: Uint8Array[];
  pendingBytes: number;
  queue: Promise<void>;
  poll: number;
  closed: boolean;
};

function pcm16(samples: Float32Array, sourceRate: number): Uint8Array {
  const ratio = sourceRate / 16000;
  const count = Math.floor(samples.length / ratio);
  const bytes = new Uint8Array(count * 2);
  const view = new DataView(bytes.buffer);
  for (let index = 0; index < count; index += 1) {
    const sample = Math.max(-1, Math.min(1, samples[Math.floor(index * ratio)]));
    view.setInt16(index * 2, sample < 0 ? sample * 32768 : sample * 32767, true);
  }
  return bytes;
}

export default function VoiceInput({ api, projectId, onTranscript }: Props) {
  const [phase, setPhase] = useState<"idle" | "starting" | "recording" | "finishing">("idle");
  const [preview, setPreview] = useState("");
  const [error, setError] = useState("");
  const capture = useRef<Capture | null>(null);
  const generation = useRef(0);

  function closeLocal(current: Capture) {
    current.closed = true;
    window.clearInterval(current.poll);
    current.processor.disconnect();
    current.source.disconnect();
    current.processor.onaudioprocess = null;
    current.stream.getTracks().forEach((track) => track.stop());
    void current.context.close();
  }

  function flush(current: Capture) {
    if (current.pendingBytes === 0) return;
    const bytes = new Uint8Array(current.pendingBytes);
    let offset = 0;
    for (const part of current.pending) { bytes.set(part, offset); offset += part.length; }
    current.pending = [];
    current.pendingBytes = 0;
    current.queue = current.queue.then(() => api.appendVoiceAudio(projectId, current.sessionId, bytes));
    void current.queue.catch(() => {
      if (capture.current === current && !current.closed) {
        setError("语音上传中断，请重试。");
        void cancel();
      }
    });
  }

  async function cancel() {
    generation.current += 1;
    const current = capture.current;
    capture.current = null;
    if (current) {
      closeLocal(current);
      try { await api.cancelVoice(projectId, current.sessionId); } catch { /* session may already be gone */ }
    }
    setPhase("idle");
    setPreview("");
  }

  async function start() {
    if (phase !== "idle") return;
    const ticket = generation.current;
    setPhase("starting"); setError(""); setPreview("");
    let stream: MediaStream | undefined;
    let context: AudioContext | undefined;
    let sessionId: string | undefined;
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: true } });
      if (ticket !== generation.current) { stream.getTracks().forEach((track) => track.stop()); setPhase("idle"); return; }
      sessionId = (await api.startVoice(projectId)).sessionId;
      if (ticket !== generation.current) {
        stream.getTracks().forEach((track) => track.stop());
        void api.cancelVoice(projectId, sessionId).catch(() => undefined);
        setPhase("idle");
        return;
      }
      context = new AudioContext();
      const source = context.createMediaStreamSource(stream);
      const processor = context.createScriptProcessor(4096, 1, 1);
      const current: Capture = { sessionId, stream, context, source, processor, pending: [],
        pendingBytes: 0, queue: Promise.resolve(), poll: 0, closed: false };
      capture.current = current;
      processor.onaudioprocess = (event) => {
        if (current.closed) return;
        const bytes = pcm16(event.inputBuffer.getChannelData(0), context!.sampleRate);
        current.pending.push(bytes);
        current.pendingBytes += bytes.length;
        if (current.pendingBytes >= 16_000) flush(current);
      };
      source.connect(processor);
      processor.connect(context.destination);
      current.poll = window.setInterval(() => {
        void api.getVoice(projectId, sessionId!).then((snapshot) => {
          if (capture.current === current) setPreview(snapshot.text);
        }).catch(() => {
          if (capture.current === current && !current.closed) { setError("语音识别中断，请重试。"); void cancel(); }
        });
      }, 500);
      setPhase("recording");
    } catch {
      stream?.getTracks().forEach((track) => track.stop());
      if (context) void context.close();
      capture.current = null;
      if (sessionId) void api.cancelVoice(projectId, sessionId).catch(() => undefined);
      setError("无法开始语音输入，请检查麦克风权限或语音服务配置。");
      setPhase("idle");
    }
  }

  async function stop() {
    const current = capture.current;
    if (!current || phase !== "recording") return;
    setPhase("finishing");
    closeLocal(current);
    try {
      flush(current);
      await current.queue;
      const final = await api.finishVoice(projectId, current.sessionId);
      if (capture.current === current && final.text.trim()) onTranscript(final.text.trim());
    } catch {
      if (capture.current === current) setError("语音识别失败，请重试。");
    } finally {
      if (capture.current === current) {
        capture.current = null;
        setPhase("idle");
        setPreview("");
      }
      void api.cancelVoice(projectId, current.sessionId).catch(() => undefined);
    }
  }

  useEffect(() => () => {
    generation.current += 1;
    const current = capture.current;
    capture.current = null;
    if (current) {
      closeLocal(current);
      void api.cancelVoice(projectId, current.sessionId).catch(() => undefined);
    }
    setPhase("idle");
    setPreview("");
  }, [api, projectId]);

  const label = phase === "recording" ? "停止录音" : phase === "starting" ? "连接语音中" : phase === "finishing" ? "转写中" : "语音输入";
  return <div className="voice-input" aria-live="polite">
    <button type="button" className="composer-utility-button" aria-label={label} title={label}
      onClick={() => void (phase === "recording" ? stop() : start())}
      disabled={phase === "starting" || phase === "finishing" || !navigator.mediaDevices?.getUserMedia}>
      {phase === "recording" ? <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" aria-hidden="true"><rect x="6" y="6" width="12" height="12" rx="2" /></svg> : <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><rect x="9" y="3" width="6" height="12" rx="3" /><path d="M6 11a6 6 0 0 0 12 0M12 17v4m-4 0h8" /></svg>}
    </button>
    {phase === "recording" && <button type="button" className="composer-utility-button" aria-label="取消录音" title="取消录音" onClick={() => void cancel()}>×</button>}
    {phase !== "idle" && <span className="voice-preview">{preview || "正在听…"}</span>}
    {error && <span role="alert" className="voice-error">{error}</span>}
  </div>;
}
