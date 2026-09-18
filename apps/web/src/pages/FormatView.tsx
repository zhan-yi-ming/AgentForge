import { MarkdownPreview } from "../MarkdownPreview";

type Props = {
  input: string;
  text: string;
  complete: boolean;
  applied: boolean;
  busy: boolean;
  streaming: boolean;
  onInput: (value: string) => void;
  onFormat: () => void;
  onApply: () => void;
};

function streamingPreviewText(content: string) {
  return content.replace(/^```(?:markdown|md)?[ \t]*\r?\n/i, "");
}

export default function FormatView({ input, text, complete, applied, busy, streaming,
  onInput, onFormat, onApply }: Props) {
  return <div className="workspace-view format-view"><div className="panel-heading"><div><span className="eyebrow">DRAFT LAB</span><h2>AI 文本整理</h2></div><span className="safe-note">预览优先 · 不自动写回</span></div><div className="format-grid"><div><label>待整理原文<textarea value={input} onChange={(event) => onInput(event.target.value)} placeholder="粘贴零散会议记录或技术笔记…" /></label><button onClick={onFormat} disabled={busy || streaming || !input.trim()}>AI 整理并预览</button></div><div><p className="section-label">整理结果</p>{complete ? <MarkdownPreview content={text} /> : text ? <div className="streaming-preview" aria-live="polite">{streamingPreviewText(text)}</div> : <MarkdownPreview content="" />}{text && <button className="ghost" onClick={onApply} disabled={busy || !complete || applied}>应用到 Wiki 草稿</button>}</div></div></div>;
}
