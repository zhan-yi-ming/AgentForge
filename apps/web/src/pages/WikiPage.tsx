import type { PointerEvent as ReactPointerEvent } from "react";
import type { WikiPage as WikiRecord } from "../api";
import { MarkdownPreview } from "../MarkdownPreview";

type Props = {
  pages: WikiRecord[];
  selectedId: string;
  title: string;
  content: string;
  feedback: string;
  busy: boolean;
  createOpen: boolean;
  previewOpen: boolean;
  previewPosition: { x: number; y: number };
  previewSize: { width: number; height: number };
  onSelect: (page: WikiRecord) => void;
  onTitleChange: (value: string) => void;
  onContentChange: (value: string) => void;
  onSave: () => void;
  onToggleCreate: () => void;
  onBlank: () => void;
  onCreateWithAi: () => void;
  onTogglePreview: () => void;
  onClosePreview: () => void;
  onPreviewPointerDown: (event: ReactPointerEvent<HTMLElement>) => void;
  onPreviewPointerMove: (event: ReactPointerEvent<HTMLElement>) => void;
  onPreviewPointerUp: () => void;
};

export default function WikiPage({ pages, selectedId, title, content, feedback, busy, createOpen,
  previewOpen, previewPosition, previewSize, onSelect, onTitleChange, onContentChange, onSave,
  onToggleCreate, onBlank, onCreateWithAi, onTogglePreview, onClosePreview,
  onPreviewPointerDown, onPreviewPointerMove, onPreviewPointerUp }: Props) {
  return <>
    <div className="workspace-view wiki-panel">
      <div className="panel-heading"><div><span className="eyebrow">KNOWLEDGE</span><h2>Wiki 工作台</h2></div><div className="heading-actions"><div className="new-wiki-wrap"><button className="ghost" onClick={onToggleCreate}>新建页面 <span>＋</span></button>{createOpen && <div className="new-wiki-menu"><button onClick={onBlank}><strong>空白页面</strong><small>从零开始记录项目知识</small></button><button onClick={onCreateWithAi}><strong>AI 整理</strong><small>把零散内容整理成 Wiki 草稿</small></button></div>}</div><button className="preview-toggle" onClick={onTogglePreview}>{previewOpen ? "隐藏预览" : "打开预览"}</button></div></div>
      <div className="wiki-layout"><nav className="wiki-list">{pages.map((page) => <button key={page.id} className={page.id === selectedId ? "active" : ""} onClick={() => onSelect(page)}>{page.title}</button>)}</nav>
        <div className="editor"><input aria-label="Wiki 标题" placeholder="页面标题" value={title} onChange={(event) => onTitleChange(event.target.value)} maxLength={200} />
          <textarea aria-label="Wiki Markdown 草稿" placeholder="# 从这里开始记录…" value={content} onChange={(event) => onContentChange(event.target.value)} maxLength={100000} />
          <button onClick={onSave} disabled={busy || !title.trim()}>保存 Wiki</button>
          {feedback && <p role="status" className="success">{feedback}</p>}</div>
      </div>
    </div>
    {previewOpen && content.trim() && <aside className="preview-float" style={{ transform: `translate(calc(-50% + ${previewPosition.x}px), calc(-50% + ${previewPosition.y}px))`, width: previewSize.width, height: previewSize.height }}><div className="preview-float-heading" onPointerDown={onPreviewPointerDown} onPointerMove={onPreviewPointerMove} onPointerUp={onPreviewPointerUp}><div><span className="section-label">LIVE PREVIEW</span><strong>实时预览</strong></div><button className="drawer-close" aria-label="关闭实时预览" onClick={onClosePreview}>×</button></div><div className="preview-float-body"><MarkdownPreview content={content} /></div></aside>}
  </>;
}
