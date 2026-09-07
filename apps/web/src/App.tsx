import { FormEvent, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ApiProblem, createApiClient, type AgentAction, type ApiClient, type Project, type Task, type WikiPage } from "./api";
import { MarkdownPreview, normalizeMarkdownContent } from "./MarkdownPreview";

const TOKEN_KEY = "agentforge.accessToken";
const ONBOARDING_KEY = "agentforge.onboardingComplete";
const LOGIN_FAILURE_MESSAGE = "请联系我 向我索要体验账号";

type ChatHistoryItem = {
  id: string;
  question: string;
  answer: string;
  sources: { title: string; excerpt: string }[];
};

function hasCompletedOnboarding() {
  try { return localStorage.getItem(ONBOARDING_KEY) === "true"; }
  catch { return false; }
}

function rememberOnboardingComplete() {
  try { localStorage.setItem(ONBOARDING_KEY, "true"); }
  catch { /* The in-memory dialog can still close when storage is unavailable. */ }
}

function isAbortError(cause: unknown) {
  return typeof cause === "object" && cause !== null &&
    "name" in cause && cause.name === "AbortError";
}

export function App({ api: injectedApi }: { api?: ApiClient }) {
  const api = useMemo(() => injectedApi ?? createApiClient(() => sessionStorage.getItem(TOKEN_KEY)), [injectedApi]);
  const [authenticated, setAuthenticated] = useState(() => Boolean(sessionStorage.getItem(TOKEN_KEY)));
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [projects, setProjects] = useState<Project[]>([]);
  const [projectId, setProjectId] = useState("");
  const [wikiPages, setWikiPages] = useState<WikiPage[]>([]);
  const [tasks, setTasks] = useState<Task[]>([]);
  const [wikiId, setWikiId] = useState("");
  const [wikiTitle, setWikiTitle] = useState("");
  const [wikiContent, setWikiContent] = useState("");
  const [wikiVersion, setWikiVersion] = useState(0);
  const [chatMessage, setChatMessage] = useState("");
  const [conversationId, setConversationId] = useState<string>();
  const [chatHistory, setChatHistory] = useState<ChatHistoryItem[]>([]);
  const [expandedChatIds, setExpandedChatIds] = useState<Set<string>>(() => new Set());
  const [pendingAction, setPendingAction] = useState<AgentAction>();
  const [formatInput, setFormatInput] = useState("");
  const [formattedText, setFormattedText] = useState("");
  const [wikiFeedback, setWikiFeedback] = useState("");
  const [busy, setBusy] = useState(false);
  const [streaming, setStreaming] = useState(false);
  const [error, setError] = useState("");
  const [onboardingOpen, setOnboardingOpen] = useState(() => !hasCompletedOnboarding());
  const streamAbort = useRef<AbortController | undefined>(undefined);
  const formatAbort = useRef<AbortController | undefined>(undefined);
  const activeProjectId = useRef("");
  const wikiPanel = useRef<HTMLElement | null>(null);
  const chatSequence = useRef(0);

  const report = useCallback((cause: unknown) => {
    if (cause instanceof ApiProblem) {
      setError(`${cause.detail}${cause.requestId ? ` · request ${cause.requestId}` : ""}`);
      if (cause.status === 401) {
        sessionStorage.removeItem(TOKEN_KEY);
        setAuthenticated(false);
      }
    } else setError(cause instanceof Error ? cause.message : "请求失败，请稍后重试。");
  }, []);

  const loadTasks = useCallback(async (selectedProjectId: string) => {
    setTasks(await api.listTasks(selectedProjectId));
  }, [api]);

  const selectWiki = useCallback((page?: WikiPage) => {
    setWikiFeedback("");
    setWikiId(page?.id ?? "");
    setWikiTitle(page?.title ?? "");
    setWikiContent(page?.content ?? "");
    setWikiVersion(page?.version ?? 0);
  }, []);

  useEffect(() => {
    if (!authenticated) return;
    let active = true;
    api.listProjects().then((items) => {
      if (!active) return;
      setProjects(items);
      setProjectId((current) => current || items[0]?.id || "");
    }).catch(report);
    return () => { active = false; };
  }, [api, authenticated, report]);

  useEffect(() => {
    if (!projectId) return;
    activeProjectId.current = projectId;
    streamAbort.current?.abort();
    formatAbort.current?.abort();
    let active = true;
    setError("");
    setConversationId(undefined);
    setChatHistory([]);
    setExpandedChatIds(new Set());
    setPendingAction(undefined);
    setFormatInput("");
    setFormattedText("");
    Promise.all([api.listWikiPages(projectId), api.listTasks(projectId)])
      .then(([pages, loadedTasks]) => {
        if (!active) return;
        setWikiPages(pages);
        setTasks(loadedTasks);
        selectWiki(pages[0]);
      }).catch(report);
    return () => { active = false; streamAbort.current?.abort(); formatAbort.current?.abort(); };
  }, [api, projectId, report, selectWiki]);

  async function login(event: FormEvent) {
    event.preventDefault();
    setBusy(true); setError("");
    try {
      const result = await api.login(email.trim(), password);
      sessionStorage.setItem(TOKEN_KEY, result.accessToken);
      setAuthenticated(true);
    } catch { setError(LOGIN_FAILURE_MESSAGE); } finally { setBusy(false); }
  }

  function completeOnboarding() {
    rememberOnboardingComplete();
    setOnboardingOpen(false);
  }

  async function saveWiki() {
    if (!projectId || !wikiTitle.trim()) return;
    setBusy(true); setError(""); setWikiFeedback("");
    try {
      const saved = wikiId
        ? await api.updateWikiPage(projectId, wikiId, wikiTitle, wikiContent, wikiVersion)
        : await api.createWikiPage(projectId, wikiTitle, wikiContent);
      const pages = await api.listWikiPages(projectId);
      setWikiPages(pages);
      const latest = pages.find((page) => page.id === saved.id) ?? saved;
      selectWiki(latest);
      setWikiFeedback(`Wiki 已保存 · v${latest.version}`);
    } catch (cause) { report(cause); } finally { setBusy(false); }
  }

  function applyFormattedText() {
    setWikiContent(formattedText);
    setWikiFeedback("已应用到 Wiki 草稿，请确认后保存");
    wikiPanel.current?.scrollIntoView({ behavior: "smooth", block: "start" });
  }

  async function sendChat(event: FormEvent) {
    event.preventDefault();
    if (!projectId || !chatMessage.trim()) return;
    const requestedProjectId = projectId;
    const question = chatMessage.trim();
    const historyId = `chat-${++chatSequence.current}`;
    const controller = new AbortController();
    streamAbort.current?.abort();
    streamAbort.current = controller;
    setStreaming(true); setError(""); setPendingAction(undefined);
    setChatHistory((current) => [...current, { id: historyId, question, answer: "", sources: [] }]);
    setExpandedChatIds(new Set([historyId]));
    try {
      const result = await api.chatStream(projectId, question, conversationId, {
        onMetadata: (metadata) => {
          if (activeProjectId.current !== requestedProjectId) return;
          setConversationId(metadata.conversationId);
          setChatHistory((current) => current.map((item) => item.id === historyId ? { ...item, sources: metadata.sources } : item));
        },
        onDelta: (text) => {
          if (activeProjectId.current === requestedProjectId) {
            setChatHistory((current) => current.map((item) => item.id === historyId ? { ...item, answer: item.answer + text } : item));
          }
        },
      }, controller.signal);
      if (activeProjectId.current !== requestedProjectId) return;
      setConversationId(result.conversationId);
      setChatHistory((current) => current.map((item) => item.id === historyId ? { ...item, answer: result.answer, sources: result.sources } : item));
      setPendingAction(result.pendingAction);
      setChatMessage("");
    } catch (cause) {
      setChatHistory((current) => current.filter((item) => item.id !== historyId));
      if (!isAbortError(cause)) report(cause);
    } finally {
      if (streamAbort.current === controller) {
        streamAbort.current = undefined;
        setStreaming(false);
      }
    }
  }

  async function decideAction(decision: "confirm" | "reject") {
    if (!projectId || !pendingAction) return;
    setBusy(true); setError("");
    try {
      if (decision === "confirm") {
        await api.confirmAction(projectId, pendingAction.id);
        await loadTasks(projectId);
      } else await api.rejectAction(projectId, pendingAction.id);
      setPendingAction(undefined);
    } catch (cause) { report(cause); } finally { setBusy(false); }
  }

  async function formatText() {
    if (!projectId || !formatInput.trim()) return;
    const requestedProjectId = projectId;
    const controller = new AbortController();
    formatAbort.current?.abort();
    formatAbort.current = controller;
    setBusy(true); setError("");
    setFormattedText("");
    try {
      const result = await api.chatStream(
        projectId,
        `请将以下内容整理为 Markdown，保留事实，不执行写入：\n\n${formatInput.trim()}`,
        undefined,
        {
          onDelta: (text) => {
            if (activeProjectId.current === requestedProjectId) {
              setFormattedText((current) => current + text);
            }
          },
        },
        controller.signal,
      );
      if (activeProjectId.current !== requestedProjectId) return;
      setFormattedText(normalizeMarkdownContent(result.answer));
    } catch (cause) {
      if (!isAbortError(cause) && activeProjectId.current === requestedProjectId) {
        setFormattedText("");
        report(cause);
      }
    } finally {
      if (formatAbort.current === controller) {
        formatAbort.current = undefined;
        setBusy(false);
      }
    }
  }

  if (!authenticated) {
    return <main className="auth-shell">
      <section className="login-card">
        <div className="auth-copy"><span className="author-chip">Built by zhan-yi-ming</span><span className="eyebrow">AGENTFORGE / AI ENGINEERING WORKSPACE</span><h1>你好，面试官 👋</h1><p className="auth-lead">从一次真实的项目对话开始。</p><p>Agent 会读取项目 Wiki 与任务、流式回答，并在任何业务写入前等待你的确认。</p></div>
        <div className="login-heading"><div className="mark">AF</div><div><span className="eyebrow">LIVE DEMO</span><h2>进入 AgentForge</h2></div></div><p className="login-note">账号是简历上的邮箱，密码是微信号</p>
        <form className="login-form" onSubmit={login}>
          <label>邮箱<input type="email" value={email} onChange={(event) => setEmail(event.target.value)} required /></label>
          <label>密码<input type="password" value={password} onChange={(event) => setPassword(event.target.value)} required minLength={8} /></label>
          {error && <p role="alert" className="error">{error}</p>}
          <button disabled={busy}>{busy ? "登录中…" : "登录"}</button>
        </form>
      </section>
    </main>;
  }

  return <div className="app-shell">
    <header className="topbar"><div><span className="mark small">AF</span><span className="brand"><strong>AgentForge</strong><small>by zhan-yi-ming</small></span></div><div className="topbar-actions"><button className="guide-button" onClick={() => setOnboardingOpen(true)}>新手引导</button><span className="status"><i /> V1.2 Live Demo</span></div></header>
    <aside className="sidebar">
      <div className="sidebar-intro"><p className="section-label">WORKSPACES</p><strong>项目空间</strong></div>
      {projects.map((project) => <button key={project.id} className={project.id === projectId ? "project active" : "project"} onClick={() => setProjectId(project.id)}><strong>{project.name}</strong><span>{project.description || "暂无描述"}</span></button>)}
      {!projects.length && <p className="empty-state">还没有项目</p>}
    </aside>
    <main className="workspace centered-workspace">
      {error && <p role="alert" className="error banner">{error}</p>}
      <section className="panel agent-panel"><div className="panel-heading"><div><span className="eyebrow">AI COPILOT</span><h2>项目对话</h2></div>{conversationId && <span className="conversation">会话 {conversationId.slice(0, 8)}</span>}</div>
        <div className="agent-welcome"><span className="agent-orb">✦</span><div><strong>你好，我是 AgentForge</strong><p>我会结合当前项目的 Wiki 与任务回答，并在写入前征求你的确认。</p></div></div>
        <form className="chat-form" onSubmit={sendChat}><textarea aria-label="给 Agent 的消息" value={chatMessage} onChange={(event) => setChatMessage(event.target.value)} placeholder="例如：这个项目的架构边界是什么？" /><button aria-label="发送" disabled={busy || streaming || !chatMessage.trim()}>{streaming ? "生成中…" : "发送 ↗"}</button></form>
        {chatHistory.length > 0 && <div className="conversation-history">{chatHistory.map((item, index) => {
          const expanded = expandedChatIds.has(item.id);
          const isLatest = index === chatHistory.length - 1;
          return <article className="history-item" key={item.id}>
            <button type="button" className="history-toggle" aria-expanded={expanded} onClick={() => setExpandedChatIds((current) => {
              const next = new Set(current);
              if (next.has(item.id)) next.delete(item.id); else next.add(item.id);
              return next;
            })}><span>{item.question}</span><small>{expanded ? "收起" : "展开"}</small></button>
            {expanded && <div className={streaming && isLatest ? "answer streaming" : "answer"}><div className="answer-label"><span>AI 回答</span>{streaming && isLatest && <span className="stream-state"><i /> 正在流式生成</span>}</div>{item.answer ? <MarkdownPreview content={item.answer} /> : <div className="typing-dots"><i /><i /><i /></div>}{item.sources.length > 0 && <div className="sources"><p className="section-label">已引用项目来源</p>{item.sources.map((source) => <article key={`${source.title}-${source.excerpt}`}><strong>{source.title}</strong><span>{source.excerpt}</span></article>)}</div>}</div>}
          </article>;
        })}</div>}
        {pendingAction && <div className="action-card"><span className="eyebrow">等待你的确认</span><h3>{pendingAction.title || pendingAction.actionType}</h3><p>{pendingAction.description || `${pendingAction.taskStatus ?? ""} ${pendingAction.priority ?? ""}`}</p><div><button onClick={() => void decideAction("confirm")} disabled={busy}>确认执行</button><button className="danger" onClick={() => void decideAction("reject")} disabled={busy}>拒绝</button></div></div>}
      </section>

      <section className="panel wiki-panel" ref={wikiPanel}>
        <div className="panel-heading"><div><span className="eyebrow">KNOWLEDGE</span><h2>Wiki 工作台</h2></div><button className="ghost" onClick={() => selectWiki()}>新建页面</button></div>
        <div className="wiki-layout"><nav className="wiki-list">{wikiPages.map((page) => <button key={page.id} className={page.id === wikiId ? "active" : ""} onClick={() => selectWiki(page)}>{page.title}</button>)}</nav>
          <div className="editor"><input aria-label="Wiki 标题" placeholder="页面标题" value={wikiTitle} onChange={(event) => { setWikiTitle(event.target.value); setWikiFeedback(""); }} maxLength={200} />
            <textarea aria-label="Wiki Markdown 草稿" placeholder="# 从这里开始记录…" value={wikiContent} onChange={(event) => { setWikiContent(event.target.value); setWikiFeedback(""); }} maxLength={100000} />
            <button onClick={saveWiki} disabled={busy || !wikiTitle.trim()}>保存 Wiki</button>
            {wikiFeedback && <p role="status" className="success">{wikiFeedback}</p>}</div>
          <div className="preview"><p className="section-label">实时预览</p><MarkdownPreview content={wikiContent} /></div>
        </div>
      </section>

      <section className="panel task-panel"><div className="panel-heading"><div><span className="eyebrow">EXECUTION</span><h2>执行任务</h2></div><span className="count">{tasks.length}</span></div>
        <p className="task-explanation">这里只展示明确创建并经你确认的任务；普通提问和 Wiki 保存不会新增任务。</p>
        <div className="task-list">{tasks.map((task) => <article key={task.id}><span className={`priority ${task.priority.toLowerCase()}`}>{task.priority}</span><h3>{task.title}</h3><p>{task.description || "暂无描述"}</p><footer><span>{task.status.replace("_", " ")}</span><span>v{task.version}</span></footer></article>)}{!tasks.length && <p className="empty-state">暂无任务，可让 Agent 提出一个。</p>}</div>
      </section>

      <section className="panel format-panel"><div className="panel-heading"><div><span className="eyebrow">DRAFT LAB</span><h2>AI 文本整理</h2></div><span className="safe-note">预览优先 · 不自动写回</span></div>
        <div className="format-grid"><div><label>待整理原文<textarea value={formatInput} onChange={(event) => setFormatInput(event.target.value)} placeholder="粘贴零散会议记录或技术笔记…" /></label><button onClick={() => void formatText()} disabled={busy || streaming || !formatInput.trim()}>AI 整理并预览</button></div>
          <div><p className="section-label">整理结果</p><MarkdownPreview content={formattedText} />{formattedText && <button className="ghost" onClick={applyFormattedText} disabled={busy}>应用到 Wiki 草稿</button>}</div></div>
      </section>
    </main>
    {onboardingOpen && <div className="onboarding-backdrop"><section className="onboarding-dialog" role="dialog" aria-modal="true" aria-labelledby="onboarding-title"><span className="eyebrow">QUICK START</span><h2 id="onboarding-title">新手引导</h2><p>四步看懂 AgentForge，不需要先研究所有面板。</p><ol><li><strong>选择项目</strong><span>左侧切换项目，所有 Wiki、任务和对话都严格隔离。</span></li><li><strong>从中央对话开始</strong><span>直接询问架构、需求或让 Agent 提出任务。</span></li><li><strong>检查来源与操作</strong><span>回答会带项目来源；业务写入必须由你确认。</span></li><li><strong>需要时再向下探索</strong><span>Wiki、任务和文本整理都保留在对话下方。</span></li></ol><button onClick={completeOnboarding}>开始体验</button></section></div>}
  </div>;
}
