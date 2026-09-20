import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import type { AgentAction, AgentSource, ApiClient } from "../api";
import { MarkdownPreview } from "../MarkdownPreview";
import { ActionApprovalDialog } from "./ActionApprovalDialog";
import VoiceInput from "./VoiceInput";

export type ChatHistoryItem = {
  id: string;
  question: string;
  answer: string;
  sources: AgentSource[];
};

type Props = {
  conversationId?: string;
  history: ChatHistoryItem[];
  expandedIds: Set<string>;
  streaming: boolean;
  pendingAction?: AgentAction;
  busy: boolean;
  expandedComposer: boolean;
  composer: (controls: ReactNode) => ReactNode;
  projectId: string;
  api: ApiClient;
  onVoiceTranscript: (text: string) => void;
  onNewChat: () => void;
  onToggle: (id: string) => void;
  onDecision: (decision: "confirm" | "reject") => void;
  onAutoDecision: () => void;
};

export default function ChatPage({ conversationId, history, expandedIds, streaming, pendingAction,
  busy, expandedComposer, composer, projectId, api, onVoiceTranscript, onNewChat, onToggle, onDecision, onAutoDecision }: Props) {
  const [openSources, setOpenSources] = useState<Set<string>>(() => new Set());
  const [dismissedActionId, setDismissedActionId] = useState<string>();
  const reopenRef = useRef<HTMLButtonElement>(null);
  const dismissAction = useCallback(() => setDismissedActionId(pendingAction?.id), [pendingAction?.id]);
  useEffect(() => setOpenSources(new Set()), [conversationId]);
  useEffect(() => {
    if (pendingAction && dismissedActionId === pendingAction.id) reopenRef.current?.focus();
  }, [dismissedActionId, pendingAction]);
  return <section className={`panel agent-panel chat-session${expandedComposer ? " composer-expanded" : ""}`}>
    <div className="panel-heading"><div><span className="eyebrow">AI COPILOT</span><h2>项目对话</h2></div>{conversationId && <span className="conversation">会话 {conversationId.slice(0, 8)}</span>}</div>
    {history.length > 0 && <div className="conversation-history">{history.map((item, index) => {
      const expanded = expandedIds.has(item.id);
      const isLatest = index === history.length - 1;
      return <article className="history-item" key={item.id}>
        <button type="button" className="history-toggle" aria-expanded={expanded} onClick={() => onToggle(item.id)}><span>{item.question}</span><small>{expanded ? "收起" : "展开"}</small></button>
        {expanded && <div className={streaming && isLatest ? "answer streaming" : "answer"}><div className="answer-label"><span>AI 回答</span>{streaming && isLatest && <span className="stream-state"><i /> 正在流式生成</span>}</div>{item.answer ? <MarkdownPreview content={item.answer} /> : <div className="typing-dots"><i /><i /><i /></div>}{item.sources.length > 0 && <div className="sources"><button type="button" className="sources-toggle" aria-expanded={openSources.has(item.id)} onClick={() => setOpenSources((current) => { const next = new Set(current); if (next.has(item.id)) next.delete(item.id); else next.add(item.id); return next; })}>引用来源（{item.sources.length}）<span aria-hidden="true">{openSources.has(item.id) ? "−" : "+"}</span></button>{openSources.has(item.id) && <div className="sources-list">{item.sources.map((source) => <article key={`${source.sourceType}-${source.sourceId}-${source.title}`}><strong>{source.title}</strong><span>{source.excerpt}</span></article>)}</div>}</div>}</div>}
      </article>;
    })}</div>}
    {pendingAction && dismissedActionId === pendingAction.id && <button type="button" className="pending-action-reopen" ref={reopenRef} onClick={() => setDismissedActionId(undefined)}>继续处理待确认操作</button>}
    {pendingAction && dismissedActionId !== pendingAction.id && <ActionApprovalDialog key={pendingAction.id} action={pendingAction} busy={busy} onClose={dismissAction} onDecision={onDecision} onAutoDecision={onAutoDecision} />}
    {composer(<><button type="button" className="composer-utility-button" aria-label="新建会话" title="新建会话" onClick={onNewChat}><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M12 4H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-6" /><path d="M12 12 20 4m-5 0h5v5" /></svg></button><VoiceInput api={api} projectId={projectId} onTranscript={onVoiceTranscript} /></>)}
  </section>;
}
