import { FormEvent, PointerEvent as ReactPointerEvent, Suspense, lazy, useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { ApiProblem, createApiClient, type AgentAction, type ApiClient, type ConversationSummary, type Project, type Task, type WikiPage } from "./api";
import { normalizeMarkdownContent } from "./markdown";
import { parseRoute, useAppRoute } from "./route";
import type { ChatHistoryItem } from "./pages/ChatPage";

const ChatPage = lazy(() => import("./pages/ChatPage"));
const WikiPage = lazy(() => import("./pages/WikiPage"));
const WikiGraphPage = lazy(() => import("./pages/WikiGraphPage"));
const TaskView = lazy(() => import("./pages/TaskView"));
const FormatView = lazy(() => import("./pages/FormatView"));

const TOKEN_KEY = "agentforge.accessToken";
const ONBOARDING_KEY = "agentforge.onboardingComplete";
const LOGIN_FAILURE_MESSAGE = "请联系我 向我索要体验账号";
const DEFAULT_FORMATTED_WIKI_TITLE = "AI 整理文档";
const FORMAT_PROMPT_PREFIX = "请将以下内容整理为 Markdown，保留事实，使用一个明确的一级标题，不执行写入：\n\n";
const MAX_FORMAT_INPUT_LENGTH = 16_000 - FORMAT_PROMPT_PREFIX.length;

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
  const { route, navigate } = useAppRoute();
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
  const [chatExpanded, setChatExpanded] = useState(() => parseRoute(window.location.pathname).page !== "chat");
  const [unreadChat, setUnreadChat] = useState(false);
  const [projectsOpen, setProjectsOpen] = useState(false);
  const [tasksOpen, setTasksOpen] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [conversationToDelete, setConversationToDelete] = useState<ConversationSummary>();
  const [deleteBusy, setDeleteBusy] = useState(false);
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
  const activeConversationLoad = useRef(0);
  const loadingConversationId = useRef<string | undefined>(undefined);
  const conversationIdRef = useRef<string | undefined>(undefined);
  const previousRoutePath = useRef(window.location.pathname);
  const decisionKeys = useRef(new Map<string, string>());
  const deleteCancelRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!conversationToDelete || deleteBusy) return;
    deleteCancelRef.current?.focus();
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        setConversationToDelete(undefined);
      } else if (event.key === "Tab") {
        const buttons = Array.from(document.querySelectorAll<HTMLButtonElement>(".delete-dialog-actions button:not(:disabled)"));
        if (event.shiftKey && document.activeElement === buttons[0]) {
          event.preventDefault();
          buttons[buttons.length - 1]?.focus();
        } else if (!event.shiftKey && document.activeElement === buttons[buttons.length - 1]) {
          event.preventDefault();
          buttons[0]?.focus();
        }
      }
    };
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [conversationToDelete, deleteBusy]);

  useEffect(() => { conversationIdRef.current = conversationId; }, [conversationId]);

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
    setConversationToDelete(undefined);
    setDeleteBusy(false);
    setPreviewOpen(false);
  }, []);

  const report = useCallback((cause: unknown) => {
    if (cause instanceof ApiProblem) {
      if (cause.status === 401) {
        sessionStorage.removeItem(TOKEN_KEY);
        resetWorkspaceState();
        navigate("/");
        setAuthenticated(false);
      }
      setError(`${cause.detail}${cause.requestId ? ` · request ${cause.requestId}` : ""}`);
    } else setError(cause instanceof Error ? cause.message : "请求失败，请稍后重试。");
  }, [resetWorkspaceState, navigate]);

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
    activeConversationLoad.current += 1;
    loadingConversationId.current = undefined;
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
    setConversationToDelete(undefined);
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

  async function openConversation(selectedConversationId: string) {
    if (!projectId) return;
    const requestedProjectId = projectId;
    const load = ++activeConversationLoad.current;
    loadingConversationId.current = selectedConversationId;
    streamAbort.current?.abort();
    navigate(`/chat/${encodeURIComponent(selectedConversationId)}`);
    setBusy(true); setError("");
    setChatHistory([]);
    setConversationId(undefined);
    setPendingAction(undefined);
    try {
      const detail = await api.getConversation(requestedProjectId, selectedConversationId);
      if (activeProjectId.current !== requestedProjectId || activeConversationLoad.current !== load) return;
      const items: ChatHistoryItem[] = [];
      let pendingQuestion: typeof detail.messages[number] | undefined;
      let activeItem: ChatHistoryItem | undefined;
      for (const [index, message] of detail.messages.entries()) {
        if (message.role === "USER") {
          pendingQuestion = message;
          activeItem = undefined;
        } else if (pendingQuestion) {
          if (activeItem) {
            activeItem.answer += `${activeItem.answer ? "\n\n" : ""}${message.content}`;
            activeItem.sources.push(...message.sources);
          } else {
            activeItem = { id: `persisted-${index}`, question: pendingQuestion.content,
              answer: message.content, sources: [...message.sources] };
            items.push(activeItem);
          }
        }
      }
      setConversationId(detail.conversationId);
      setChatHistory(items);
      setExpandedChatIds(new Set(items.map((item) => item.id)));
      setPendingAction(undefined);
      setHistoryOpen(false);
      setChatMode(true);
      chatModeRef.current = true;
      setChatExpanded(false);
      navigate(`/chat/${encodeURIComponent(detail.conversationId)}`);
    } catch (cause) {
      if (activeProjectId.current === requestedProjectId && activeConversationLoad.current === load) report(cause);
    } finally {
      if (activeProjectId.current === requestedProjectId && activeConversationLoad.current === load) {
        loadingConversationId.current = undefined;
        setBusy(false);
      }
    }
  }

  function newChat() {
    activeConversationLoad.current += 1;
    loadingConversationId.current = undefined;
    streamAbort.current?.abort();
    setConversationId(undefined);
    setChatHistory([]);
    setExpandedChatIds(new Set());
    setPendingAction(undefined);
    setChatMessage("");
    setBusy(false);
    setStreaming(false);
    setError("");
    setHistoryOpen(false);
    setConversationToDelete(undefined);
    setChatMode(true);
    chatModeRef.current = true;
    setChatExpanded(false);
    navigate("/chat");
  }

  async function deleteSelectedConversation() {
    if (!projectId || !conversationToDelete || deleteBusy) return;
    const selectedId = conversationToDelete.conversationId;
    const requestedProjectId = projectId;
    const requestedToken = sessionStorage.getItem(TOKEN_KEY);
    setDeleteBusy(true);
    setError("");
    try {
      await api.deleteConversation(requestedProjectId, selectedId);
      if (activeProjectId.current !== requestedProjectId || sessionStorage.getItem(TOKEN_KEY) !== requestedToken) return;
      setConversationSummaries((current) => current.filter((item) => item.conversationId !== selectedId));
      setConversationToDelete(undefined);
      const currentRoute = parseRoute(window.location.pathname);
      if (currentRoute.page === "chat" && currentRoute.conversationId === selectedId) {
        newChat();
      } else if (currentRoute.page !== "chat" && conversationIdRef.current === selectedId) {
        activeConversationLoad.current += 1;
        streamAbort.current?.abort();
        setConversationId(undefined);
        setChatHistory([]);
        setPendingAction(undefined);
        setStreaming(false);
      }
    } catch (cause) {
      if (activeProjectId.current === requestedProjectId && sessionStorage.getItem(TOKEN_KEY) === requestedToken) report(cause);
    } finally {
      setDeleteBusy(false);
    }
  }

  useEffect(() => {
    if (!authenticated || !projectId) return;
    const routeChanged = previousRoutePath.current !== window.location.pathname;
    previousRoutePath.current = window.location.pathname;
    if (routeChanged && route.page !== "chat") {
      activeConversationLoad.current += 1;
      loadingConversationId.current = undefined;
      streamAbort.current?.abort();
      setBusy(false);
    }
    if (route.page === "chat") {
      setChatMode(true);
      chatModeRef.current = true;
      if (routeChanged) setChatExpanded(false);
      if (route.conversationId && route.conversationId !== conversationId &&
        loadingConversationId.current !== route.conversationId) {
        void openConversation(route.conversationId);
      } else if (!route.conversationId && routeChanged && (loadingConversationId.current || conversationId)) {
        activeConversationLoad.current += 1;
        loadingConversationId.current = undefined;
        streamAbort.current?.abort();
        setConversationId(undefined);
        setChatHistory([]);
        setPendingAction(undefined);
      }
    } else if (route.page === "wiki" || route.page === "wiki-graph") {
      setChatMode(false);
      chatModeRef.current = false;
      setActivePanel("wiki");
    } else {
      setChatMode(false);
      chatModeRef.current = false;
    }
  // The selected route or project is the load trigger; openConversation validates late responses.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [authenticated, projectId, route.page, route.conversationId]);

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
    navigate("/wiki");
    setPreviewOpen(true);
    wikiPanel.current?.scrollIntoView?.({ behavior: "smooth", block: "start" });
  }

  function openPanel(panel: WorkspacePanel) {
    activeConversationLoad.current += 1;
    loadingConversationId.current = undefined;
    streamAbort.current?.abort();
    setActivePanel(panel);
    setChatMode(false);
    navigate(panel === "wiki" ? "/wiki" : "/");
    setChatExpanded(false);
    setProjectsOpen(false);
    setTasksOpen(false);
    setHistoryOpen(false);
  }

  function openChatTool(panel: WorkspacePanel) {
    if (panel === "wiki") { openPanel("wiki"); return; }
    setActivePanel((current) => current === panel ? null : panel);
    setProjectsOpen(false);
    setTasksOpen(false);
    setHistoryOpen(false);
  }

  function enterChatMode() {
    chatModeRef.current = true;
    setChatMode(true);
    navigate(conversationId ? `/chat/${encodeURIComponent(conversationId)}` : "/chat");
    setActivePanel(null);
    setChatExpanded(false);
    setUnreadChat(false);
  }

  function leaveChatMode() {
    activeConversationLoad.current += 1;
    loadingConversationId.current = undefined;
    streamAbort.current?.abort();
    chatModeRef.current = false;
    setChatMode(false);
    navigate("/");
    setActivePanel(null);
    setChatExpanded(true);
    setProjectsOpen(false);
    setTasksOpen(false);
    setHistoryOpen(false);
  }

  function logout() {
    sessionStorage.removeItem(TOKEN_KEY);
    resetWorkspaceState();
    navigate("/");
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
    setExpandedChatIds((current) => new Set(current).add(historyId));
    try {
      const result = await api.chatStream(projectId, question, conversationId, {
        onMetadata: (metadata) => {
          if (activeProjectId.current !== requestedProjectId || streamAbort.current !== controller ||
            controller.signal.aborted || parseRoute(window.location.pathname).page !== "chat") return;
          setConversationId(metadata.conversationId);
          navigate(`/chat/${encodeURIComponent(metadata.conversationId)}`, true);
          setChatHistory((current) => current.map((item) => item.id === historyId ? { ...item, sources: metadata.sources } : item));
        },
        onDelta: (text) => {
          if (activeProjectId.current === requestedProjectId && streamAbort.current === controller && !controller.signal.aborted) {
            setChatHistory((current) => current.map((item) => item.id === historyId ? { ...item, answer: item.answer + text } : item));
          }
        },
      }, controller.signal);
      if (activeProjectId.current !== requestedProjectId || streamAbort.current !== controller || controller.signal.aborted) return;
      setConversationId(result.conversationId);
      setChatHistory((current) => current.map((item) => item.id === historyId ? { ...item, answer: result.answer, sources: result.sources } : item));
      setPendingAction(result.pendingAction);
      if (!chatModeRef.current) setUnreadChat(true);
      setChatMessage("");
      api.listConversations(requestedProjectId).then((items) => {
        if (activeProjectId.current === requestedProjectId) setConversationSummaries(items);
      }).catch(report);
    } catch (cause) {
      if (streamAbort.current === controller) {
        setChatHistory((current) => current.filter((item) => item.id !== historyId));
        if (!isAbortError(cause)) report(cause);
      }
    } finally {
      if (streamAbort.current === controller) {
        streamAbort.current = undefined;
        setStreaming(false);
      }
    }
  }

  async function decideAction(decision: "confirm" | "reject", automatic = false) {
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
        const result = automatic
          ? await api.autoConfirmAction(projectId, actionId, idempotencyKey)
          : await api.confirmAction(projectId, actionId, idempotencyKey);
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
    if (!projectId || !formatInput.trim() || formatInput.trim().length > MAX_FORMAT_INPUT_LENGTH) return;
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
        `${FORMAT_PROMPT_PREFIX}${formatInput.trim()}`,
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

  function renderChatComposer(controls?: ReactNode) {
    return <form className="chat-composer" onSubmit={(event) => { event.stopPropagation(); void sendChat(event); }}><textarea aria-label="给 Agent 的消息" value={chatMessage} onChange={(event) => setChatMessage(event.target.value)} placeholder="向 Agent 提问，探索项目上下文…" /><div className="composer-footer"><span>Agent 会基于当前项目 Wiki 与任务回答</span>{controls}{(chatMode || activePanel) && <button type="button" className="expand-chat-button" aria-label={chatExpanded ? "缩小聊天输入框" : "放大聊天输入框"} onClick={() => setChatExpanded((expanded) => !expanded)}><Icon name={chatExpanded ? "shrink" : "expand"} /></button>}<button aria-label="发送" disabled={busy || streaming || !chatMessage.trim()}>{streaming ? "生成中…" : "发送"}<span>↗</span></button></div></form>;
  }

  if (!authenticated) {
    return <main className="auth-shell">
      <div className="auth-watermark" aria-hidden="true" />
      <header className="auth-brand"><img className="brand-mark" src="/brand-mark.svg" alt="" /><strong>AgentForge</strong></header>
      <div className="auth-layout">
        <section className="auth-copy">
          <span className="eyebrow">AGENTFORGE / AI ENGINEERING WORKSPACE</span>
          <h1>把复杂项目，<br />变成可协作的确定性行动。</h1>
          <p className="auth-lead">从真实的项目上下文开始。</p>
          <p>Agent 会读取项目 Wiki 与任务、流式回答，<br />并在任何业务写入前等待你的确认。</p>
          <div className="auth-benefits" aria-label="产品能力">
            <div><span aria-hidden="true">▣</span><strong>项目上下文</strong><small>理解你的业务全貌</small></div>
            <div><span aria-hidden="true">ϟ</span><strong>流式智能问答</strong><small>更快获得可靠结果</small></div>
            <div><span aria-hidden="true">◎</span><strong>协作更高效</strong><small>从想法到执行</small></div>
          </div>
        </section>
        <section className="login-card">
          <div className="login-heading"><img className="brand-mark" src="/brand-mark.svg" alt="AgentForge" /><div><span className="eyebrow">LIVE DEMO</span><h2>进入 AgentForge</h2></div></div>
          <p className="login-note">账号是简历上的邮箱，密码是微信号</p>
          <form className="login-form" onSubmit={login}>
            <label>邮箱<input type="email" value={email} onChange={(event) => setEmail(event.target.value)} placeholder="请输入你的邮箱" required /></label>
            <label>密码<input type="password" value={password} onChange={(event) => setPassword(event.target.value)} placeholder="请输入你的密码" required minLength={8} /></label>
            {error && <p role="alert" className="error">{error}</p>}
            <button disabled={busy}>{busy ? "登录中…" : "登录"}</button>
          </form>
        </section>
      </div>
      <p className="auth-footer"><span /> BUILD WITH AGENTS. SHIP REAL IMPACT.</p>
    </main>;
  }

  if (route.page === "wiki-graph") {
    return <Suspense fallback={<p role="status">正在打开知识图谱…</p>}><WikiGraphPage projectId={projectId} pages={wikiPages} error={error} onBack={() => navigate("/wiki")} onOpenPage={(page) => { selectWiki(page); navigate("/wiki"); }} /></Suspense>;
  }

  return <div className="app-shell">
    <header className={chatMode ? "topbar chat-mode" : "topbar"}><div className="topbar-logo"><img className="brand-mark small" src="/brand-mark.svg" alt="AgentForge" /><span className="brand"><strong>AgentForge</strong><small>Project intelligence workspace</small></span></div><div className="topbar-right"><button className="guide-button icon-button" onClick={() => setOnboardingOpen(true)}><Icon name="info" />产品说明</button><span className="status"><i /> V2 Live Demo</span><button className="logout-button icon-button" onClick={logout}><Icon name="logout" />退出</button></div></header>
    {chatMode && <button className="chat-back-button" aria-label="返回首页工作台" onClick={leaveChatMode}><Icon name="back" /></button>}
    {chatMode && <div className="chat-tools-rail" aria-label="聊天界面导航"><button className="rail-button" onClick={() => { setProjectsOpen((open) => !open); setTasksOpen(false); setHistoryOpen(false); }}><span>⌘</span><small>项目</small></button><button className="rail-button" onClick={() => { setTasksOpen((open) => !open); setProjectsOpen(false); setHistoryOpen(false); }}><span>✓</span><small>任务</small></button><button className="rail-button" onClick={() => { setHistoryOpen((open) => !open); setProjectsOpen(false); setTasksOpen(false); }}><span>◴</span><small>历史</small></button><button className="rail-button" onClick={() => openChatTool("wiki")}><span>▤</span><small>Wiki</small></button><button className="rail-button" onClick={() => openChatTool("tasks")}><span>≡</span><small>执行</small></button><button className="rail-button" onClick={() => openChatTool("format")}><span>✎</span><small>整理</small></button></div>}
    {!chatMode && <div className="floating-rail" aria-label="快速导航">
      <button className="rail-button" aria-expanded={projectsOpen} onClick={() => { setProjectsOpen((open) => !open); setTasksOpen(false); }}><span>⌘</span><small>项目</small></button>
      <button className="rail-button" aria-expanded={tasksOpen} onClick={() => { setTasksOpen((open) => !open); setProjectsOpen(false); }}><span>✓</span><small>任务</small></button>
      <button className="rail-button" aria-expanded={historyOpen} onClick={() => { setHistoryOpen((open) => !open); setProjectsOpen(false); setTasksOpen(false); }}><span>◴</span><small>历史</small></button>
      {(chatHistory.length > 0 || streaming) && <button className="rail-button conversation-button" onClick={enterChatMode}><span>◌</span><small>对话</small>{unreadChat && <i className="unread-badge" aria-label="有新的 AI 回答" />}</button>}
    </div>}
    {projectsOpen && <aside className="floating-drawer projects-drawer"><div className="drawer-heading"><div><span className="section-label">WORKSPACES</span><strong>项目空间</strong></div><button className="drawer-close" aria-label="关闭项目空间" onClick={() => setProjectsOpen(false)}>×</button></div>{projects.map((project) => <button key={project.id} className={project.id === projectId ? "project active" : "project"} onClick={() => { setProjectId(project.id); if (route.page === "chat") navigate("/chat"); setProjectsOpen(false); }}><strong>{project.name}</strong><span>{project.description || "暂无描述"}</span></button>)}{!projects.length && <p className="empty-state">还没有项目</p>}</aside>}
    {historyOpen && <aside className="floating-drawer history-drawer"><div className="drawer-heading"><div><span className="section-label">HISTORY</span><strong>历史会话</strong></div><button className="drawer-close" aria-label="关闭历史会话" onClick={() => setHistoryOpen(false)}>×</button></div><button className="ghost" onClick={newChat}>新建会话</button>{conversationSummaries.map((conversation) => <div className="history-record" key={conversation.conversationId}><button className="project" onClick={() => void openConversation(conversation.conversationId)} disabled={busy || deleteBusy}><strong>{conversation.preview}</strong><span>{conversation.messageCount} 条消息</span></button><button type="button" className="history-delete" aria-label={`删除会话 ${conversation.preview}`} onClick={() => setConversationToDelete(conversation)} disabled={deleteBusy}>删除</button></div>)}{!conversationSummaries.length && <p className="empty-state">暂无历史会话</p>}</aside>}
    {tasksOpen && <aside className="floating-drawer tasks-drawer"><div className="drawer-heading"><div><span className="section-label">EXECUTION</span><strong>执行任务</strong></div><button className="drawer-close" aria-label="关闭执行任务" onClick={() => setTasksOpen(false)}>×</button></div><div className="task-list">{tasks.map((task) => <article key={task.id}><span className={`priority ${task.priority.toLowerCase()}`}>{task.priority}</span><h3>{task.title}</h3><p>{task.description || "暂无描述"}</p><footer><span>{task.status.replace("_", " ")}</span><span>v{task.version}</span></footer></article>)}{!tasks.length && <p className="empty-state">暂无任务，可让 Agent 提出一个。</p>}</div></aside>}
    <main className="workspace centered-workspace">
      {error && <p role="alert" className="error banner">{error}</p>}
      {chatMode ? <Suspense fallback={<p role="status">正在打开聊天…</p>}><ChatPage
        conversationId={conversationId} history={chatHistory} expandedIds={expandedChatIds}
        streaming={streaming} pendingAction={pendingAction} busy={busy} expandedComposer={chatExpanded}
        composer={(controls) => renderChatComposer(controls)} onNewChat={newChat}
        projectId={projectId ?? ""} api={api} onVoiceTranscript={(text) => setChatMessage((current) => current.trim() ? `${current.trim()} ${text}` : text)}
        onToggle={(id) => setExpandedChatIds((current) => {
          const next = new Set(current);
          if (next.has(id)) next.delete(id); else next.add(id);
          return next;
        })}
        onDecision={(decision) => void decideAction(decision)}
        onAutoDecision={() => void decideAction("confirm", true)}
      /></Suspense> : <section className={activePanel ? `panel agent-panel chat-collapsed${chatExpanded ? " composer-expanded" : ""}` : "panel agent-panel home-chat composer-expanded"}>
        <div className="panel-heading"><div><span className="eyebrow">AI COPILOT</span><h2>项目对话</h2></div>{conversationId && <span className="conversation">会话 {conversationId.slice(0, 8)}</span>}</div>
        {!activePanel && <div className="agent-welcome"><span className="agent-orb">✦</span><div><strong>你好，我是 AgentForge</strong><p>我会结合当前项目的 Wiki 与任务回答，并在写入前征求你的确认。</p></div></div>}
        {renderChatComposer()}
      </section>}

      <section className={chatMode ? activePanel ? "workspace-panel chat-tool-window" : "workspace-panel workspace-hidden" : "workspace-panel"} ref={wikiPanel}>
        {chatMode && activePanel && <button className="drawer-close chat-tool-close" aria-label="关闭悬浮工作台" onClick={() => setActivePanel(null)}>×</button>}
        {!chatMode && <nav className="workspace-tabs" aria-label="工作台导航">
          <button className={activePanel === "wiki" ? "active" : ""} onClick={() => openPanel("wiki")}>Wiki 工作台</button>
          <button className={activePanel === "tasks" ? "active" : ""} onClick={() => openPanel("tasks")}>执行任务 <span>{tasks.length}</span></button>
          <button className={activePanel === "format" ? "active" : ""} onClick={() => openPanel("format")}>AI 文本整理</button>
        </nav>}
        {activePanel === "wiki" && <Suspense fallback={<p role="status">正在打开 Wiki…</p>}><WikiPage
          pages={wikiPages} selectedId={wikiId} title={wikiTitle} content={wikiContent} feedback={wikiFeedback}
          busy={busy} createOpen={wikiCreateOpen} previewOpen={previewOpen}
          previewPosition={previewPosition} previewSize={previewSize} onSelect={selectWiki}
          onOpenGraph={() => { setPreviewOpen(false); navigate("/wiki/graph"); }}
          onTitleChange={(value) => { setWikiTitle(value); setWikiFeedback(""); }}
          onContentChange={(value) => { setWikiContent(value); setWikiFeedback(""); }}
          onSave={() => void saveWiki()} onToggleCreate={() => setWikiCreateOpen((open) => !open)}
          onBlank={createBlankWiki} onCreateWithAi={createWikiWithAi}
          onTogglePreview={() => setPreviewOpen((open) => !open)} onClosePreview={() => setPreviewOpen(false)}
          onPreviewPointerDown={startPreviewDrag} onPreviewPointerMove={movePreviewDrag}
          onPreviewPointerUp={endPreviewDrag}
        /></Suspense>}
        {activePanel === "tasks" && <Suspense fallback={<p role="status">正在打开任务…</p>}><TaskView tasks={tasks} /></Suspense>}
        {activePanel === "format" && <Suspense fallback={<p role="status">正在打开整理…</p>}><FormatView
          input={formatInput} text={formattedText} complete={formatComplete} applied={formatApplied}
          maxInputLength={MAX_FORMAT_INPUT_LENGTH}
          busy={busy} streaming={streaming} onInput={setFormatInput}
          onFormat={() => void formatText()} onApply={applyFormattedText}
        /></Suspense>}
      </section>
    </main>
    {conversationToDelete && <div className="onboarding-backdrop"><section className="onboarding-dialog" role="dialog" aria-modal="true" aria-labelledby="delete-conversation-title"><span className="eyebrow">HISTORY</span><h2 id="delete-conversation-title">删除聊天记录</h2><p>确定删除“{conversationToDelete.preview}”的聊天记录？删除后无法恢复；若还有待确认操作，请先完成或拒绝。</p><div className="delete-dialog-actions"><button type="button" className="ghost" ref={deleteCancelRef} onClick={() => setConversationToDelete(undefined)} disabled={deleteBusy}>取消</button><button type="button" className="danger" onClick={() => void deleteSelectedConversation()} disabled={deleteBusy}>{deleteBusy ? "删除中…" : "确认删除"}</button></div></section></div>}
    {onboardingOpen && <div className="onboarding-backdrop"><section className="onboarding-dialog" role="dialog" aria-modal="true" aria-labelledby="onboarding-title"><span className="eyebrow">WHY AGENTFORGE</span><h2 id="onboarding-title">让项目知识真正参与执行</h2><p>AgentForge 把分散在 Wiki、任务和对话里的上下文放到同一个工作台，让团队更快理解问题、形成决策，并在确认后安全落地。</p><div className="onboarding-value"><span>问题</span><strong>信息散落，判断依赖个人记忆，执行容易失真。</strong><span>方法</span><strong>从项目上下文出发，让 AI 先解释、再提议，最后由人确认。</strong></div><div className="onboarding-modules"><details open><summary>项目空间</summary><p>切换项目时，Wiki、任务与对话上下文会严格隔离，避免跨项目混淆。</p></details><details><summary>项目对话</summary><p>直接询问架构、需求和风险；回答会带来源，涉及业务写入时会等待你的确认。</p></details><details><summary>Wiki 工作台</summary><p>把稳定知识沉淀为可编辑页面，并用底部预览窗即时检查 Markdown 结构。</p></details><details><summary>AI 文本整理</summary><p>把会议记录或技术笔记整理为可审阅的 Wiki 草稿，不会自动写回。</p></details></div><button onClick={completeOnboarding}>开始体验</button></section></div>}
  </div>;
}
