import { FormEvent, PointerEvent as ReactPointerEvent, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ApiProblem, createApiClient, type AgentAction, type ApiClient, type ConversationSummary, type Project, type Task, type WikiPage } from "./api";
import { MarkdownPreview, normalizeMarkdownContent } from "./MarkdownPreview";

const TOKEN_KEY = "agentforge.accessToken";
const ONBOARDING_KEY = "agentforge.onboardingComplete";
const LOGIN_FAILURE_MESSAGE = "请联系我 向我索要体验账号";
const DEFAULT_FORMATTED_WIKI_TITLE = "AI 整理文档";

type ChatHistoryItem = {
  id: string;
  question: string;
  answer: string;
  sources: { title: string; excerpt: string }[];
};

type WorkspacePanel = "wiki" | "tasks" | "format";

function Icon({ name }: { name: "info" | "logout" | "back" | "expand" | "shrink" }) {
  const paths = {
    info: <><circle cx="12" cy="12" r="8.5" /><path d="M12 10.8v5.3M12 7.5h.01" /></>,
    logout: <><path d="M10 5H6.5A1.5 1.5 0 0 0 5 6.5v11A1.5 1.5 0 0 0 6.5 19H10" /><path d="M13 8l4 4-4 4M8.5 12H17" /></>,
    back: <><path d="M15 5l-7 7 7 7" /><path d="M8.5 12H20" /></>,
    expand: <><path d="M8 10V7h3M16 10V7h-3M8 14v3h3M16 14v3h-3" /></>,
    shrink: <><path d="M11 8H8v3M13 8h3v3M11 16H8v-3M13 16h3v-3" /></>,
  };
  return <svg className="ui-icon" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">{paths[name]}</svg>;
}

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

function streamingPreviewText(content: string) {
  return content.replace(/^```(?:markdown|md)?[ \t]*\r?\n/i, "");
}

function formattedWikiTitle(content: string) {
  let fenceMarker = "";
  for (const line of normalizeMarkdownContent(content).split(/\r?\n/)) {
    const fence = line.match(/^[ \t]{0,3}(`{3,}|~{3,})/);
    if (fence) {
      const marker = fence[1];
      if (!fenceMarker) fenceMarker = marker;
      else if (marker[0] === fenceMarker[0] && marker.length >= fenceMarker.length) fenceMarker = "";
      continue;
    }
    if (!fenceMarker) {
      const heading = line.match(/^#[ \t]+(.+?)[ \t]*$/)?.[1]?.trim();
      if (heading) return heading.slice(0, 200);
    }
  }
  return DEFAULT_FORMATTED_WIKI_TITLE;
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
  const [conversationSummaries, setConversationSummaries] = useState<ConversationSummary[]>([]);
  const [expandedChatIds, setExpandedChatIds] = useState<Set<string>>(() => new Set());
  const [pendingAction, setPendingAction] = useState<AgentAction>();
  const [formatInput, setFormatInput] = useState("");
  const [formattedText, setFormattedText] = useState("");
  const [formatComplete, setFormatComplete] = useState(false);
  const [formatApplied, setFormatApplied] = useState(false);
  const [wikiFeedback, setWikiFeedback] = useState("");
  const [busy, setBusy] = useState(false);
  const [streaming, setStreaming] = useState(false);
  const [error, setError] = useState("");
  const [onboardingOpen, setOnboardingOpen] = useState(() => !hasCompletedOnboarding());
  const [activePanel, setActivePanel] = useState<WorkspacePanel | null>(null);
  const [chatMode, setChatMode] = useState(false);
  const [chatExpanded, setChatExpanded] = useState(true);
  const [unreadChat, setUnreadChat] = useState(false);
  const [projectsOpen, setProjectsOpen] = useState(false);
  const [tasksOpen, setTasksOpen] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [wikiCreateOpen, setWikiCreateOpen] = useState(false);
  const [previewOpen, setPreviewOpen] = useState(false);
  const [previewPosition, setPreviewPosition] = useState({ x: 0, y: 0 });
  const [previewSize, setPreviewSize] = useState({ width: 680, height: 420 });
  const previewDrag = useRef<{ startX: number; startY: number; originX: number; originY: number } | undefined>(undefined);
  const streamAbort = useRef<AbortController | undefined>(undefined);
  const formatAbort = useRef<AbortController | undefined>(undefined);
  const activeProjectId = useRef("");
  const wikiPanel = useRef<HTMLElement | null>(null);
  const chatSequence = useRef(0);
  const chatModeRef = useRef(false);
  const decisionKeys = useRef(new Map<string, string>());

  const resetWorkspaceState = useCallback(() => {
    streamAbort.current?.abort();
    formatAbort.current?.abort();
    streamAbort.current = undefined;
    formatAbort.current = undefined;
    activeProjectId.current = "";
    decisionKeys.current.clear();
    setProjects([]);
    setProjectId("");
    setWikiPages([]);
    setTasks([]);
    setWikiId("");
    setWikiTitle("");
    setWikiContent("");
    setWikiVersion(0);
    setChatMessage("");
    setConversationId(undefined);
    setChatHistory([]);
    setConversationSummaries([]);
    setExpandedChatIds(new Set());
    setPendingAction(undefined);
    setFormatInput("");
    setFormattedText("");
    setFormatComplete(false);
    setFormatApplied(false);
    setWikiFeedback("");
    setBusy(false);
    setStreaming(false);
    setActivePanel(null);
    setChatMode(false);
    chatModeRef.current = false;
    setChatExpanded(true);
    setUnreadChat(false);
    setProjectsOpen(false);
    setTasksOpen(false);
    setHistoryOpen(false);
    setWikiCreateOpen(false);
    setPreviewOpen(false);
  }, []);

  const report = useCallback((cause: unknown) => {
    if (cause instanceof ApiProblem) {
      if (cause.status === 401) {
        sessionStorage.removeItem(TOKEN_KEY);
        resetWorkspaceState();
        setAuthenticated(false);
      }
      setError(`${cause.detail}${cause.requestId ? ` · request ${cause.requestId}` : ""}`);
    } else setError(cause instanceof Error ? cause.message : "请求失败，请稍后重试。");
  }, [resetWorkspaceState]);

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
    setBusy(false);
    setError("");
    setConversationId(undefined);
    setChatHistory([]);
    setExpandedChatIds(new Set());
    setPendingAction(undefined);
    setConversationSummaries([]);
    setHistoryOpen(false);
    setFormatInput("");
    setFormattedText("");
    setFormatComplete(false);
    setFormatApplied(false);
    Promise.all([api.listWikiPages(projectId), api.listTasks(projectId), api.listConversations(projectId)])
      .then(([pages, loadedTasks, loadedConversations]) => {
        if (!active) return;
        setWikiPages(pages);
        setTasks(loadedTasks);
        setConversationSummaries(loadedConversations);
        selectWiki(pages[0]);
      }).catch(report);
    return () => { active = false; streamAbort.current?.abort(); formatAbort.current?.abort(); };
  }, [api, projectId, report, selectWiki]);

  async function openConversation(summary: ConversationSummary) {
    if (!projectId) return;
    const requestedProjectId = projectId;
    setBusy(true); setError("");
    try {
      const detail = await api.getConversation(requestedProjectId, summary.conversationId);
      if (activeProjectId.current !== requestedProjectId) return;
      const items: ChatHistoryItem[] = [];
      for (let index = 0; index < detail.messages.length; index += 2) {
        const question = detail.messages[index];
        const answer = detail.messages[index + 1];
        if (question?.role === "USER" && answer?.role === "ASSISTANT") {
          items.push({ id: `persisted-${index}`, question: question.content,
            answer: answer.content, sources: answer.sources });
        }
      }
      setConversationId(detail.conversationId);
      setChatHistory(items);
      setExpandedChatIds(new Set(items.map((item) => item.id)));
      setPendingAction(undefined);
      setHistoryOpen(false);
      setChatMode(true);
      chatModeRef.current = true;
    } catch (cause) {
      if (activeProjectId.current === requestedProjectId) report(cause);
    } finally {
      if (activeProjectId.current === requestedProjectId) setBusy(false);
    }
  }

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
    if (!formatComplete) return;
    setWikiId("");
    setWikiVersion(0);
    setWikiTitle(formattedWikiTitle(formattedText));
    setWikiContent(formattedText);
    setFormatApplied(true);
    setWikiFeedback("已应用到 Wiki 草稿，请确认后保存");
    setActivePanel("wiki");
    setPreviewOpen(true);
    wikiPanel.current?.scrollIntoView?.({ behavior: "smooth", block: "start" });
  }

  function openPanel(panel: WorkspacePanel) {
    setActivePanel(panel);
    setChatMode(false);
    setChatExpanded(false);
    setProjectsOpen(false);
    setTasksOpen(false);
    setHistoryOpen(false);
  }

  function openChatTool(panel: WorkspacePanel) {
    setActivePanel((current) => current === panel ? null : panel);
    setProjectsOpen(false);
    setTasksOpen(false);
    setHistoryOpen(false);
  }

  function enterChatMode() {
    chatModeRef.current = true;
    setChatMode(true);
    setActivePanel(null);
    setChatExpanded(true);
    setUnreadChat(false);
  }

  function leaveChatMode() {
    chatModeRef.current = false;
    setChatMode(false);
    setActivePanel(null);
    setChatExpanded(true);
    setProjectsOpen(false);
    setTasksOpen(false);
    setHistoryOpen(false);
  }

  function logout() {
    sessionStorage.removeItem(TOKEN_KEY);
    resetWorkspaceState();
    setAuthenticated(false);
    setEmail("");
    setPassword("");
    setError("");
  }

  function startPreviewDrag(event: ReactPointerEvent<HTMLElement>) {
    if ((event.target as HTMLElement).closest("button")) return;
    previewDrag.current = { startX: event.clientX, startY: event.clientY, originX: previewPosition.x, originY: previewPosition.y };
    event.currentTarget.setPointerCapture(event.pointerId);
  }

  function movePreviewDrag(event: ReactPointerEvent<HTMLElement>) {
    const drag = previewDrag.current;
    if (!drag) return;
    setPreviewPosition({ x: drag.originX + event.clientX - drag.startX, y: drag.originY + event.clientY - drag.startY });
  }

  function endPreviewDrag() {
    previewDrag.current = undefined;
  }

  function createBlankWiki() {
    selectWiki();
    setWikiCreateOpen(false);
    setActivePanel("wiki");
  }

  function createWikiWithAi() {
    selectWiki();
    setWikiCreateOpen(false);
    setActivePanel("format");
  }

  async function sendChat(event: FormEvent) {
    event.preventDefault();
    if (!projectId || !chatMessage.trim()) return;
    enterChatMode();
    setProjectsOpen(false);
    setTasksOpen(false);
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
      if (!chatModeRef.current) setUnreadChat(true);
      setChatMessage("");
      api.listConversations(requestedProjectId).then((items) => {
        if (activeProjectId.current === requestedProjectId) setConversationSummaries(items);
      }).catch(report);
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
    const actionId = pendingAction.id;
    let idempotencyKey = decisionKeys.current.get(actionId);
    if (!idempotencyKey) {
      idempotencyKey = crypto.randomUUID();
      decisionKeys.current.set(actionId, idempotencyKey);
    }
    setBusy(true); setError("");
    try {
      if (decision === "confirm") {
        const result = await api.confirmAction(projectId, actionId, idempotencyKey);
        if (result.status === "FAILED") {
          decisionKeys.current.delete(actionId);
          setPendingAction(undefined);
          setError("审批已记录，但执行失败。请刷新目标后重新发起。");
          return;
        }
        await loadTasks(projectId);
      } else await api.rejectAction(projectId, actionId, idempotencyKey);
      decisionKeys.current.delete(actionId);
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
    setFormatComplete(false);
    setFormatApplied(false);
    try {
      const result = await api.chatStream(
        projectId,
        `请将以下内容整理为 Markdown，保留事实，使用一个明确的一级标题，不执行写入：\n\n${formatInput.trim()}`,
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
      setFormatComplete(true);
    } catch (cause) {
      if (!isAbortError(cause) && activeProjectId.current === requestedProjectId) {
        setFormattedText("");
        setFormatComplete(false);
        report(cause);
      }
    } finally {
      if (formatAbort.current === controller) {
        formatAbort.current = undefined;
        setBusy(false);
      }
    }
  }

  function renderChatComposer() {
    return <form className="chat-composer" onSubmit={(event) => { event.stopPropagation(); void sendChat(event); }}><textarea aria-label="给 Agent 的消息" value={chatMessage} onChange={(event) => setChatMessage(event.target.value)} placeholder="向 Agent 提问，探索项目上下文…" /><div className="composer-footer"><span>Agent 会基于当前项目 Wiki 与任务回答</span>{activePanel && <button type="button" className="expand-chat-button" aria-label={chatExpanded ? "缩小聊天输入框" : "放大聊天输入框"} onClick={() => setChatExpanded((expanded) => !expanded)}><Icon name={chatExpanded ? "shrink" : "expand"} /></button>}<button aria-label="发送" disabled={busy || streaming || !chatMessage.trim()}>{streaming ? "生成中…" : "发送"}<span>↗</span></button></div></form>;
  }

  if (!authenticated) {
    return <main className="auth-shell">
      <section className="login-card">
        <div className="auth-copy"><span className="eyebrow">AGENTFORGE / AI ENGINEERING WORKSPACE</span><h1>把复杂项目，变成可协作的确定性行动。</h1><p className="auth-lead">从真实的项目上下文开始。</p><p>Agent 会读取项目 Wiki 与任务、流式回答，并在任何业务写入前等待你的确认。</p></div>
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
    <header className="topbar"><div className="topbar-logo"><span className="mark small">AF</span><span className="brand"><strong>AgentForge</strong><small>Project intelligence workspace</small></span></div><div className="topbar-right"><button className="guide-button icon-button" onClick={() => setOnboardingOpen(true)}><Icon name="info" />产品说明</button><span className="status"><i /> V1.2 Live Demo</span><button className="logout-button icon-button" onClick={logout}><Icon name="logout" />退出</button></div></header>
    {chatMode && <button className="chat-back-button" aria-label="返回首页工作台" onClick={leaveChatMode}><Icon name="back" /></button>}
    {chatMode && <div className="chat-tools-rail" aria-label="聊天界面导航"><button className="rail-button" onClick={() => { setProjectsOpen((open) => !open); setTasksOpen(false); setHistoryOpen(false); }}><span>⌘</span><small>项目</small></button><button className="rail-button" onClick={() => { setTasksOpen((open) => !open); setProjectsOpen(false); setHistoryOpen(false); }}><span>✓</span><small>任务</small></button><button className="rail-button" onClick={() => { setHistoryOpen((open) => !open); setProjectsOpen(false); setTasksOpen(false); }}><span>◴</span><small>历史</small></button><button className="rail-button" onClick={() => openChatTool("wiki")}><span>▤</span><small>Wiki</small></button><button className="rail-button" onClick={() => openChatTool("tasks")}><span>≡</span><small>执行</small></button><button className="rail-button" onClick={() => openChatTool("format")}><span>✎</span><small>整理</small></button></div>}
    {!chatMode && <div className="floating-rail" aria-label="快速导航">
      <button className="rail-button" aria-expanded={projectsOpen} onClick={() => { setProjectsOpen((open) => !open); setTasksOpen(false); }}><span>⌘</span><small>项目</small></button>
      <button className="rail-button" aria-expanded={tasksOpen} onClick={() => { setTasksOpen((open) => !open); setProjectsOpen(false); }}><span>✓</span><small>任务</small></button>
      <button className="rail-button" aria-expanded={historyOpen} onClick={() => { setHistoryOpen((open) => !open); setProjectsOpen(false); setTasksOpen(false); }}><span>◴</span><small>历史</small></button>
      {(chatHistory.length > 0 || streaming) && <button className="rail-button conversation-button" onClick={enterChatMode}><span>◌</span><small>对话</small>{unreadChat && <i className="unread-badge" aria-label="有新的 AI 回答" />}</button>}
    </div>}
    {projectsOpen && <aside className="floating-drawer projects-drawer"><div className="drawer-heading"><div><span className="section-label">WORKSPACES</span><strong>项目空间</strong></div><button className="drawer-close" aria-label="关闭项目空间" onClick={() => setProjectsOpen(false)}>×</button></div>{projects.map((project) => <button key={project.id} className={project.id === projectId ? "project active" : "project"} onClick={() => { setProjectId(project.id); setProjectsOpen(false); }}><strong>{project.name}</strong><span>{project.description || "暂无描述"}</span></button>)}{!projects.length && <p className="empty-state">还没有项目</p>}</aside>}
    {historyOpen && <aside className="floating-drawer history-drawer"><div className="drawer-heading"><div><span className="section-label">HISTORY</span><strong>历史会话</strong></div><button className="drawer-close" aria-label="关闭历史会话" onClick={() => setHistoryOpen(false)}>×</button></div>{conversationSummaries.map((conversation) => <button key={conversation.conversationId} className="project" onClick={() => void openConversation(conversation)} disabled={busy}><strong>{conversation.preview}</strong><span>{conversation.messageCount} 条消息</span></button>)}{!conversationSummaries.length && <p className="empty-state">暂无历史会话</p>}</aside>}
    {tasksOpen && <aside className="floating-drawer tasks-drawer"><div className="drawer-heading"><div><span className="section-label">EXECUTION</span><strong>执行任务</strong></div><button className="drawer-close" aria-label="关闭执行任务" onClick={() => setTasksOpen(false)}>×</button></div><div className="task-list">{tasks.map((task) => <article key={task.id}><span className={`priority ${task.priority.toLowerCase()}`}>{task.priority}</span><h3>{task.title}</h3><p>{task.description || "暂无描述"}</p><footer><span>{task.status.replace("_", " ")}</span><span>v{task.version}</span></footer></article>)}{!tasks.length && <p className="empty-state">暂无任务，可让 Agent 提出一个。</p>}</div></aside>}
    <main className="workspace centered-workspace">
      {error && <p role="alert" className="error banner">{error}</p>}
      <section className={chatMode ? `panel agent-panel chat-session${chatExpanded ? " composer-expanded" : ""}` : activePanel ? `panel agent-panel chat-collapsed${chatExpanded ? " composer-expanded" : ""}` : "panel agent-panel home-chat composer-expanded"}><div className="panel-heading"><div><span className="eyebrow">AI COPILOT</span><h2>项目对话</h2></div>{conversationId && <span className="conversation">会话 {conversationId.slice(0, 8)}</span>}</div>
        {!activePanel && !chatMode && <div className="agent-welcome"><span className="agent-orb">✦</span><div><strong>你好，我是 AgentForge</strong><p>我会结合当前项目的 Wiki 与任务回答，并在写入前征求你的确认。</p></div></div>}
        {!chatMode && renderChatComposer()}
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
        {chatMode && renderChatComposer()}
      </section>

      <section className={chatMode ? activePanel ? "workspace-panel chat-tool-window" : "workspace-panel workspace-hidden" : "workspace-panel"} ref={wikiPanel}>
        {chatMode && activePanel && <button className="drawer-close chat-tool-close" aria-label="关闭悬浮工作台" onClick={() => setActivePanel(null)}>×</button>}
        {!chatMode && <nav className="workspace-tabs" aria-label="工作台导航">
          <button className={activePanel === "wiki" ? "active" : ""} onClick={() => openPanel("wiki")}>Wiki 工作台</button>
          <button className={activePanel === "tasks" ? "active" : ""} onClick={() => openPanel("tasks")}>执行任务 <span>{tasks.length}</span></button>
          <button className={activePanel === "format" ? "active" : ""} onClick={() => openPanel("format")}>AI 文本整理</button>
        </nav>}
        {activePanel === "wiki" && <div className="workspace-view wiki-panel">
          <div className="panel-heading"><div><span className="eyebrow">KNOWLEDGE</span><h2>Wiki 工作台</h2></div><div className="heading-actions"><div className="new-wiki-wrap"><button className="ghost" onClick={() => setWikiCreateOpen((open) => !open)}>新建页面 <span>＋</span></button>{wikiCreateOpen && <div className="new-wiki-menu"><button onClick={createBlankWiki}><strong>空白页面</strong><small>从零开始记录项目知识</small></button><button onClick={createWikiWithAi}><strong>AI 整理</strong><small>把零散内容整理成 Wiki 草稿</small></button></div>}</div><button className="preview-toggle" onClick={() => setPreviewOpen((open) => !open)}>{previewOpen ? "隐藏预览" : "打开预览"}</button></div></div>
          <div className="wiki-layout"><nav className="wiki-list">{wikiPages.map((page) => <button key={page.id} className={page.id === wikiId ? "active" : ""} onClick={() => selectWiki(page)}>{page.title}</button>)}</nav>
            <div className="editor"><input aria-label="Wiki 标题" placeholder="页面标题" value={wikiTitle} onChange={(event) => { setWikiTitle(event.target.value); setWikiFeedback(""); }} maxLength={200} />
              <textarea aria-label="Wiki Markdown 草稿" placeholder="# 从这里开始记录…" value={wikiContent} onChange={(event) => { setWikiContent(event.target.value); setWikiFeedback(""); }} maxLength={100000} />
              <button onClick={saveWiki} disabled={busy || !wikiTitle.trim()}>保存 Wiki</button>
              {wikiFeedback && <p role="status" className="success">{wikiFeedback}</p>}</div>
          </div>
        </div>}
        {activePanel === "tasks" && <div className="workspace-view tasks-view"><div className="panel-heading"><div><span className="eyebrow">EXECUTION</span><h2>执行任务</h2></div><span className="count">{tasks.length}</span></div><p className="task-explanation">这里只展示明确创建并经你确认的任务；普通提问和 Wiki 保存不会新增任务。</p><div className="task-list">{tasks.map((task) => <article key={task.id}><span className={`priority ${task.priority.toLowerCase()}`}>{task.priority}</span><h3>{task.title}</h3><p>{task.description || "暂无描述"}</p><footer><span>{task.status.replace("_", " ")}</span><span>v{task.version}</span></footer></article>)}{!tasks.length && <p className="empty-state">暂无任务，可让 Agent 提出一个。</p>}</div></div>}
        {activePanel === "format" && <div className="workspace-view format-view"><div className="panel-heading"><div><span className="eyebrow">DRAFT LAB</span><h2>AI 文本整理</h2></div><span className="safe-note">预览优先 · 不自动写回</span></div><div className="format-grid"><div><label>待整理原文<textarea value={formatInput} onChange={(event) => setFormatInput(event.target.value)} placeholder="粘贴零散会议记录或技术笔记…" /></label><button onClick={() => void formatText()} disabled={busy || streaming || !formatInput.trim()}>AI 整理并预览</button></div><div><p className="section-label">整理结果</p>{formatComplete ? <MarkdownPreview content={formattedText} /> : formattedText ? <div className="streaming-preview" aria-live="polite">{streamingPreviewText(formattedText)}</div> : <MarkdownPreview content="" />}{formattedText && <button className="ghost" onClick={applyFormattedText} disabled={busy || !formatComplete || formatApplied}>应用到 Wiki 草稿</button>}</div></div></div>}
      </section>
      {previewOpen && activePanel === "wiki" && wikiContent.trim() && <aside className="preview-float" style={{ transform: `translate(calc(-50% + ${previewPosition.x}px), calc(-50% + ${previewPosition.y}px))`, width: previewSize.width, height: previewSize.height }}><div className="preview-float-heading" onPointerDown={startPreviewDrag} onPointerMove={movePreviewDrag} onPointerUp={endPreviewDrag}><div><span className="section-label">LIVE PREVIEW</span><strong>实时预览</strong></div><button className="drawer-close" aria-label="关闭实时预览" onClick={() => setPreviewOpen(false)}>×</button></div><div className="preview-float-body"><MarkdownPreview content={wikiContent} /></div></aside>}
    </main>
    {onboardingOpen && <div className="onboarding-backdrop"><section className="onboarding-dialog" role="dialog" aria-modal="true" aria-labelledby="onboarding-title"><span className="eyebrow">WHY AGENTFORGE</span><h2 id="onboarding-title">让项目知识真正参与执行</h2><p>AgentForge 把分散在 Wiki、任务和对话里的上下文放到同一个工作台，让团队更快理解问题、形成决策，并在确认后安全落地。</p><div className="onboarding-value"><span>问题</span><strong>信息散落，判断依赖个人记忆，执行容易失真。</strong><span>方法</span><strong>从项目上下文出发，让 AI 先解释、再提议，最后由人确认。</strong></div><div className="onboarding-modules"><details open><summary>项目空间</summary><p>切换项目时，Wiki、任务与对话上下文会严格隔离，避免跨项目混淆。</p></details><details><summary>项目对话</summary><p>直接询问架构、需求和风险；回答会带来源，涉及业务写入时会等待你的确认。</p></details><details><summary>Wiki 工作台</summary><p>把稳定知识沉淀为可编辑页面，并用底部预览窗即时检查 Markdown 结构。</p></details><details><summary>AI 文本整理</summary><p>把会议记录或技术笔记整理为可审阅的 Wiki 草稿，不会自动写回。</p></details></div><button onClick={completeOnboarding}>开始体验</button></section></div>}
  </div>;
}
