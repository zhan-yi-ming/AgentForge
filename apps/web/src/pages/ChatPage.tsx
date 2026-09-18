import type { ReactNode } from "react";
import type { AgentAction, AgentSource } from "../api";
import { MarkdownPreview } from "../MarkdownPreview";

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
  composer: ReactNode;
  onNewChat: () => void;
  onToggle: (id: string) => void;
  onDecision: (decision: "confirm" | "reject") => void;
};

export default function ChatPage({ conversationId, history, expandedIds, streaming, pendingAction,
  busy, expandedComposer, composer, onNewChat, onToggle, onDecision }: Props) {
  return <section className={`panel agent-panel chat-session${expandedComposer ? " composer-expanded" : ""}`}>
    <div className="panel-heading"><div><span className="eyebrow">AI COPILOT</span><h2>项目对话</h2></div>{conversationId && <span className="conversation">会话 {conversationId.slice(0, 8)}</span>}</div>
    <button type="button" className="ghost" onClick={onNewChat}>新建会话</button>
    {history.length > 0 && <div className="conversation-history">{history.map((item, index) => {
      const expanded = expandedIds.has(item.id);
      const isLatest = index === history.length - 1;
      return <article className="history-item" key={item.id}>
        <button type="button" className="history-toggle" aria-expanded={expanded} onClick={() => onToggle(item.id)}><span>{item.question}</span><small>{expanded ? "收起" : "展开"}</small></button>
        {expanded && <div className={streaming && isLatest ? "answer streaming" : "answer"}><div className="answer-label"><span>AI 回答</span>{streaming && isLatest && <span className="stream-state"><i /> 正在流式生成</span>}</div>{item.answer ? <MarkdownPreview content={item.answer} /> : <div className="typing-dots"><i /><i /><i /></div>}{item.sources.length > 0 && <div className="sources"><p className="section-label">已引用项目来源</p>{item.sources.map((source) => <article key={`${source.title}-${source.excerpt}`}><strong>{source.title}</strong><span>{source.excerpt}</span></article>)}</div>}</div>}
      </article>;
    })}</div>}
    {pendingAction && <div className="action-card"><span className="eyebrow">等待你的确认</span><h3>{pendingAction.title || pendingAction.actionType}</h3><p>{pendingAction.description || `${pendingAction.taskStatus ?? ""} ${pendingAction.priority ?? ""}`}</p><div><button onClick={() => onDecision("confirm")} disabled={busy}>确认执行</button><button className="danger" onClick={() => onDecision("reject")} disabled={busy}>拒绝</button></div></div>}
    {composer}
  </section>;
}
