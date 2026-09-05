import { FormEvent, useCallback, useEffect, useMemo, useState } from "react";
import { ApiProblem, createApiClient, type AgentAction, type ApiClient, type Project, type Task, type WikiPage } from "./api";
import { MarkdownPreview } from "./MarkdownPreview";

const TOKEN_KEY = "agentforge.accessToken";

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
  const [answer, setAnswer] = useState("");
  const [sources, setSources] = useState<{ title: string; excerpt: string }[]>([]);
  const [pendingAction, setPendingAction] = useState<AgentAction>();
  const [formatInput, setFormatInput] = useState("");
  const [formattedText, setFormattedText] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

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
    let active = true;
    setError("");
    setConversationId(undefined);
    setAnswer("");
    setSources([]);
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
    return () => { active = false; };
  }, [api, projectId, report, selectWiki]);

  async function login(event: FormEvent) {
    event.preventDefault();
    setBusy(true); setError("");
    try {
      const result = await api.login(email.trim(), password);
      sessionStorage.setItem(TOKEN_KEY, result.accessToken);
      setAuthenticated(true);
    } catch (cause) { report(cause); } finally { setBusy(false); }
  }

  async function saveWiki() {
    if (!projectId || !wikiTitle.trim()) return;
    setBusy(true); setError("");
    try {
      const saved = wikiId
        ? await api.updateWikiPage(projectId, wikiId, wikiTitle, wikiContent, wikiVersion)
        : await api.createWikiPage(projectId, wikiTitle, wikiContent);
      const pages = await api.listWikiPages(projectId);
      setWikiPages(pages);
      selectWiki(pages.find((page) => page.id === saved.id) ?? saved);
    } catch (cause) { report(cause); } finally { setBusy(false); }
  }

  async function sendChat(event: FormEvent) {
    event.preventDefault();
    if (!projectId || !chatMessage.trim()) return;
    setBusy(true); setError("");
    try {
      const result = await api.chat(projectId, chatMessage.trim(), conversationId);
      setConversationId(result.conversationId);
      setAnswer(result.answer);
      setSources(result.sources);
      setPendingAction(result.pendingAction);
      setChatMessage("");
    } catch (cause) { report(cause); } finally { setBusy(false); }
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
    setBusy(true); setError("");
    try {
      const result = await api.chat(projectId, `请将以下内容整理为 Markdown，保留事实，不执行写入：\n\n${formatInput.trim()}`);
      setFormattedText(result.answer);
      if (result.pendingAction) setPendingAction(result.pendingAction);
    } catch (cause) { report(cause); } finally { setBusy(false); }
  }

  if (!authenticated) {
    return <main className="auth-shell">
      <section className="auth-copy"><span className="eyebrow">AGENTFORGE / V1</span><h1>把项目知识变成可确认的行动。</h1><p>知识、任务与 Agent 在一个清晰的工作区里协同，所有写入仍由你决定。</p></section>
      <form className="login-card" onSubmit={login}>
        <div className="mark">AF</div><h2>进入工作区</h2>
        <label>邮箱<input type="email" value={email} onChange={(event) => setEmail(event.target.value)} required /></label>
        <label>密码<input type="password" value={password} onChange={(event) => setPassword(event.target.value)} required minLength={8} /></label>
        {error && <p role="alert" className="error">{error}</p>}
        <button disabled={busy}>{busy ? "登录中…" : "登录"}</button>
      </form>
    </main>;
  }

  return <div className="app-shell">
    <header className="topbar"><div><span className="mark small">AF</span><strong>AgentForge</strong></div><span className="status"><i /> V1 本地工作区</span></header>
    <aside className="sidebar">
      <p className="section-label">项目</p>
      {projects.map((project) => <button key={project.id} className={project.id === projectId ? "project active" : "project"} onClick={() => setProjectId(project.id)}><strong>{project.name}</strong><span>{project.description || "暂无描述"}</span></button>)}
      {!projects.length && <p className="empty-state">还没有项目</p>}
    </aside>
    <main className="workspace">
      {error && <p role="alert" className="error banner">{error}</p>}
      <section className="panel wiki-panel">
        <div className="panel-heading"><div><span className="eyebrow">KNOWLEDGE</span><h2>Wiki 工作台</h2></div><button className="ghost" onClick={() => selectWiki()}>新建页面</button></div>
        <div className="wiki-layout"><nav className="wiki-list">{wikiPages.map((page) => <button key={page.id} className={page.id === wikiId ? "active" : ""} onClick={() => selectWiki(page)}>{page.title}</button>)}</nav>
          <div className="editor"><input aria-label="Wiki 标题" placeholder="页面标题" value={wikiTitle} onChange={(event) => setWikiTitle(event.target.value)} maxLength={200} />
            <textarea aria-label="Wiki Markdown 草稿" placeholder="# 从这里开始记录…" value={wikiContent} onChange={(event) => setWikiContent(event.target.value)} maxLength={100000} />
            <button onClick={saveWiki} disabled={busy || !wikiTitle.trim()}>保存 Wiki</button></div>
          <div className="preview"><p className="section-label">实时预览</p><MarkdownPreview content={wikiContent} /></div>
        </div>
      </section>

      <section className="panel task-panel"><div className="panel-heading"><div><span className="eyebrow">EXECUTION</span><h2>任务脉搏</h2></div><span className="count">{tasks.length}</span></div>
        <div className="task-list">{tasks.map((task) => <article key={task.id}><span className={`priority ${task.priority.toLowerCase()}`}>{task.priority}</span><h3>{task.title}</h3><p>{task.description || "暂无描述"}</p><footer><span>{task.status.replace("_", " ")}</span><span>v{task.version}</span></footer></article>)}{!tasks.length && <p className="empty-state">暂无任务，可让 Agent 提出一个。</p>}</div>
      </section>

      <section className="panel agent-panel"><div className="panel-heading"><div><span className="eyebrow">AGENT</span><h2>项目对话</h2></div>{conversationId && <span className="conversation">会话 {conversationId.slice(0, 8)}</span>}</div>
        <form className="chat-form" onSubmit={sendChat}><textarea aria-label="给 Agent 的消息" value={chatMessage} onChange={(event) => setChatMessage(event.target.value)} placeholder="询问项目知识，或提出一个任务…" /><button disabled={busy || !chatMessage.trim()}>发送</button></form>
        {answer && <div className="answer"><MarkdownPreview content={answer} />{sources.length > 0 && <div className="sources"><p className="section-label">来源</p>{sources.map((source) => <article key={`${source.title}-${source.excerpt}`}><strong>{source.title}</strong><span>{source.excerpt}</span></article>)}</div>}</div>}
        {pendingAction && <div className="action-card"><span className="eyebrow">等待你的确认</span><h3>{pendingAction.title || pendingAction.actionType}</h3><p>{pendingAction.description || `${pendingAction.taskStatus ?? ""} ${pendingAction.priority ?? ""}`}</p><div><button onClick={() => void decideAction("confirm")} disabled={busy}>确认执行</button><button className="danger" onClick={() => void decideAction("reject")} disabled={busy}>拒绝</button></div></div>}
      </section>

      <section className="panel format-panel"><div className="panel-heading"><div><span className="eyebrow">DRAFT LAB</span><h2>AI 文本整理</h2></div><span className="safe-note">预览优先 · 不自动写回</span></div>
        <div className="format-grid"><div><label>待整理原文<textarea value={formatInput} onChange={(event) => setFormatInput(event.target.value)} placeholder="粘贴零散会议记录或技术笔记…" /></label><button onClick={() => void formatText()} disabled={busy || !formatInput.trim()}>AI 整理并预览</button></div>
          <div><p className="section-label">整理结果</p><MarkdownPreview content={formattedText} />{formattedText && <button className="ghost" onClick={() => setWikiContent(formattedText)}>应用到 Wiki 草稿</button>}</div></div>
      </section>
    </main>
  </div>;
}
