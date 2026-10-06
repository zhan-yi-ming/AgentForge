import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "../src/App";
import { ApiProblem, type ApiClient, type AgentAction, type AgentChat, type Project, type Task, type WikiPage } from "../src/api";

const project: Project = {
  id: "project-1", ownerId: "user-1", name: "AgentForge", description: "Workspace",
  createdAt: "2026-09-05T00:00:00Z", updatedAt: "2026-09-05T00:00:00Z",
};
const secondProject: Project = {
  ...project, id: "project-2", name: "Second Project", description: "Another workspace",
};
const task: Task = {
  id: "task-1", projectId: project.id, title: "Ship UI", description: "Day 6", status: "TODO",
  priority: "HIGH", version: 0, createdAt: "2026-09-05T00:00:00Z", updatedAt: "2026-09-05T00:00:00Z",
};

function api(overrides: Partial<ApiClient> = {}): ApiClient {
  return {
    login: vi.fn().mockResolvedValue({ accessToken: "token", tokenType: "Bearer", expiresIn: 1800,
      user: { id: "user-1", email: "owner@example.com", displayName: "Owner", role: "USER" } }),
    listProjects: vi.fn().mockResolvedValue([project]),
    listWikiPages: vi.fn().mockResolvedValue([] as WikiPage[]),
    createWikiPage: vi.fn(), updateWikiPage: vi.fn(),
    listTasks: vi.fn().mockResolvedValue([task]),
    listConversations: vi.fn().mockResolvedValue([]), getConversation: vi.fn(), deleteConversation: vi.fn().mockResolvedValue(undefined),
    listRecoverableActions: vi.fn().mockResolvedValue([]),
    chat: vi.fn(), chatStream: vi.fn(), confirmAction: vi.fn(), autoConfirmAction: vi.fn(), rejectAction: vi.fn(),
    startVoice: vi.fn(), appendVoiceAudio: vi.fn(), getVoice: vi.fn(), finishVoice: vi.fn(), cancelVoice: vi.fn(),
    ...overrides,
  };
}

async function login(mockApi: ApiClient) {
  const user = userEvent.setup();
  render(<App api={mockApi} />);
  await user.type(screen.getByLabelText("邮箱"), "owner@example.com");
  await user.type(screen.getByLabelText("密码"), "password-123");
  await user.click(screen.getByRole("button", { name: "登录" }));
  await screen.findByText("你好，我是 AgentForge");
  return user;
}

describe("App", () => {
  beforeEach(() => window.history.replaceState({}, "", "/"));
  it("renders the Wiki graph outside the workspace card", async () => {
    window.history.replaceState({}, "", "/wiki/graph");
    sessionStorage.setItem("agentforge.accessToken", "token");
    localStorage.setItem("agentforge.onboardingComplete", "true");
    render(<App api={api()} />);
    const graph = await screen.findByRole("region", { name: "Wiki 知识图谱" });
    expect(graph.closest(".workspace-panel")).toBeNull();
    expect(graph.closest(".centered-workspace")).toBeNull();
  });

  it("shows Wiki loading failures on the dedicated graph page", async () => {
    window.history.replaceState({}, "", "/wiki/graph");
    sessionStorage.setItem("agentforge.accessToken", "token");
    localStorage.setItem("agentforge.onboardingComplete", "true");
    render(<App api={api({ listWikiPages: vi.fn().mockRejectedValue(new Error("Wiki pages unavailable")) })} />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Wiki pages unavailable");
  });

  it("keeps new chat and voice buttons inside the composer next to expand", async () => {
    localStorage.setItem("agentforge.onboardingComplete", "true");
    const user = await login(api({ chatStream: vi.fn().mockResolvedValue({
      conversationId: "icon-chat", answer: "Ready", requestId: "r-icon", sources: [],
    }) }));
    await user.type(screen.getByLabelText("给 Agent 的消息"), "hello");
    await user.click(screen.getByRole("button", { name: "发送" }));
    expect(await screen.findByText("Ready")).toBeInTheDocument();
    const composer = screen.getByLabelText("给 Agent 的消息").closest("form");
    expect(composer).not.toBeNull();
    expect(within(composer!).getByRole("button", { name: "新建会话" })).toBeInTheDocument();
    expect(within(composer!).getByRole("button", { name: "语音输入" })).toBeInTheDocument();
    expect(within(composer!).getByRole("button", { name: "放大聊天输入框" })).toBeInTheDocument();
  });

  it("explains when formatting input exceeds the supported message length", async () => {
    localStorage.setItem("agentforge.onboardingComplete", "true");
    const mockApi = api();
    const user = await login(mockApi);
    await user.click(screen.getByRole("button", { name: "AI 文本整理" }));
    fireEvent.change(await screen.findByLabelText("待整理原文"), { target: { value: "文".repeat(16_000) } });
    expect(screen.getByText(/超过单次整理上限/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "AI 整理并预览" })).toBeDisabled();
    expect(mockApi.chatStream).not.toHaveBeenCalled();
  });

  it("loads current-project Wiki pages when the graph route is opened directly", async () => {
    window.history.replaceState({}, "", "/wiki/graph");
    sessionStorage.setItem("agentforge.accessToken", "token");
    localStorage.setItem("agentforge.onboardingComplete", "true");
    const wiki = { id: "wiki-1", projectId: project.id, title: "系统架构", content: "页面内容", version: 1,
      createdAt: "2026-09-20T00:00:00Z", updatedAt: "2026-09-20T00:00:00Z" };
    const mockApi = api({ listWikiPages: vi.fn().mockResolvedValue([wiki]) });
    render(<App api={mockApi} />);
    expect(await screen.findByRole("region", { name: "Wiki 知识图谱" })).toBeInTheDocument();
    expect(await screen.findByRole("button", { name: "查看 系统架构" })).toBeInTheDocument();
    expect(mockApi.listWikiPages).toHaveBeenCalledWith(project.id);
    expect(window.location.pathname).toBe("/wiki/graph");
  });

  it("navigates between Wiki editor and a separately routed graph without changing the selected page", async () => {
    localStorage.setItem("agentforge.onboardingComplete", "true");
    const wiki = { id: "wiki-1", projectId: project.id, title: "系统架构", content: "页面内容", version: 1,
      createdAt: "2026-09-20T00:00:00Z", updatedAt: "2026-09-20T00:00:00Z" };
    const user = await login(api({ listWikiPages: vi.fn().mockResolvedValue([wiki]) }));
    await user.click(screen.getByRole("button", { name: "Wiki 工作台" }));
    await user.click(await screen.findByRole("button", { name: /知识图谱/ }));
    expect(window.location.pathname).toBe("/wiki/graph");
    expect(await screen.findByRole("region", { name: "Wiki 知识图谱" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "查看 系统架构" }));
    await user.click(screen.getByRole("button", { name: /打开 Wiki 页面/ }));
    expect(window.location.pathname).toBe("/wiki");
    expect(screen.getByLabelText("Wiki 标题")).toHaveValue("系统架构");
  });

  it("opens a selected conversation at its own route and starts a separate blank chat", async () => {
    localStorage.setItem("agentforge.onboardingComplete", "true");
    const mockApi = api({
      listConversations: vi.fn().mockResolvedValue([{ conversationId: "conversation-old", preview: "Earlier question",
        messageCount: 2, createdAt: "2026-09-08T00:00:00Z", updatedAt: "2026-09-08T00:01:00Z" }]),
      getConversation: vi.fn().mockResolvedValue({ conversationId: "conversation-old", preview: "Earlier question",
        messageCount: 2, createdAt: "2026-09-08T00:00:00Z", updatedAt: "2026-09-08T00:01:00Z",
        messages: [
          { role: "USER", content: "Earlier question", sources: [], createdAt: "2026-09-08T00:00:00Z" },
          { role: "ASSISTANT", content: "Earlier answer", sources: [], createdAt: "2026-09-08T00:01:00Z" },
        ] }),
    });
    const user = await login(mockApi);
    await user.click(screen.getByRole("button", { name: /历史/ }));
    await user.click(await screen.findByRole("button", { name: /^Earlier question/ }));
    expect(window.location.pathname).toBe("/chat/conversation-old");
    expect(await screen.findByText("Earlier answer")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "放大聊天输入框" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "新建会话" }));
    expect(window.location.pathname).toBe("/chat");
    expect(screen.queryByText("Earlier answer")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "放大聊天输入框" })).toBeInTheDocument();
  });

  it("deletes one selected history entry only after manual confirmation", async () => {
    localStorage.setItem("agentforge.onboardingComplete", "true");
    const summary = { conversationId: "conversation-old", preview: "Earlier question", messageCount: 2,
      createdAt: "2026-09-08T00:00:00Z", updatedAt: "2026-09-08T00:01:00Z" };
    const mockApi = api({
      listConversations: vi.fn().mockResolvedValue([summary]),
      getConversation: vi.fn().mockResolvedValue({ ...summary, messages: [
        { role: "USER", content: "Earlier question", sources: [], createdAt: summary.createdAt },
        { role: "ASSISTANT", content: "Earlier answer", sources: [], createdAt: summary.updatedAt },
      ] }),
    });
    const user = await login(mockApi);
    await user.click(screen.getByRole("button", { name: /历史/ }));
    await user.click(await screen.findByRole("button", { name: /^Earlier question/ }));
    expect(await screen.findByText("Earlier answer")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /历史/ }));
    await user.click(screen.getByRole("button", { name: "删除会话 Earlier question" }));
    expect(mockApi.deleteConversation).not.toHaveBeenCalled();
    expect(within(screen.getByRole("dialog", { name: "删除聊天记录" }))
      .getByRole("button", { name: "取消" })).toHaveFocus();
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("dialog", { name: "删除聊天记录" })).not.toBeInTheDocument();
    expect(mockApi.deleteConversation).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "删除会话 Earlier question" }));
    await user.click(within(screen.getByRole("dialog", { name: "删除聊天记录" }))
      .getByRole("button", { name: "确认删除" }));
    await waitFor(() => expect(mockApi.deleteConversation).toHaveBeenCalledWith(project.id, "conversation-old"));
    expect(window.location.pathname).toBe("/chat");
    expect(screen.queryByText("Earlier answer")).not.toBeInTheDocument();
  });

  it("keeps the newly opened chat when an older conversation deletion finishes", async () => {
    localStorage.setItem("agentforge.onboardingComplete", "true");
    const summary = (id: string) => ({ conversationId: id, preview: `Question ${id}`, messageCount: 2,
      createdAt: "2026-09-08T00:00:00Z", updatedAt: "2026-09-08T00:01:00Z" });
    let resolveDelete!: () => void;
    const mockApi = api({
      listConversations: vi.fn().mockResolvedValue([summary("A"), summary("B")]),
      getConversation: vi.fn().mockImplementation(async (_projectId, id: string) => ({ ...summary(id), messages: [
        { role: "USER", content: `Question ${id}`, sources: [], createdAt: "2026-09-08T00:00:00Z" },
        { role: "ASSISTANT", content: `Answer ${id}`, sources: [], createdAt: "2026-09-08T00:01:00Z" },
      ] })),
      deleteConversation: vi.fn().mockReturnValue(new Promise<void>((resolve) => { resolveDelete = resolve; })),
    });
    const user = await login(mockApi);
    await user.click(screen.getByRole("button", { name: /历史/ }));
    await user.click(await screen.findByRole("button", { name: /^Question A/ }));
    expect(await screen.findByText("Answer A")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /历史/ }));
    await user.click(screen.getByRole("button", { name: "删除会话 Question A" }));
    await user.click(within(screen.getByRole("dialog", { name: "删除聊天记录" }))
      .getByRole("button", { name: "确认删除" }));
    act(() => {
      window.history.pushState({}, "", "/chat/B");
      window.dispatchEvent(new PopStateEvent("popstate"));
    });
    expect(await screen.findByText("Answer B")).toBeInTheDocument();
    await act(async () => resolveDelete());
    expect(window.location.pathname).toBe("/chat/B");
    expect(screen.getByText("Answer B")).toBeInTheDocument();
  });

  it("stays on the home route when deleting the last background conversation", async () => {
    localStorage.setItem("agentforge.onboardingComplete", "true");
    const summary = { conversationId: "conversation-old", preview: "Old question", messageCount: 2,
      createdAt: "2026-09-08T00:00:00Z", updatedAt: "2026-09-08T00:01:00Z" };
    const mockApi = api({
      listConversations: vi.fn().mockResolvedValue([summary]),
      getConversation: vi.fn().mockResolvedValue({ ...summary, messages: [
        { role: "USER", content: "Old question", sources: [], createdAt: summary.createdAt },
        { role: "ASSISTANT", content: "Old answer", sources: [], createdAt: summary.updatedAt },
      ] }),
    });
    const user = await login(mockApi);
    await user.click(screen.getByRole("button", { name: /历史/ }));
    await user.click(await screen.findByRole("button", { name: /^Old question/ }));
    expect(await screen.findByText("Old answer")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "返回首页工作台" }));
    expect(window.location.pathname).toBe("/");
    await user.click(screen.getByRole("button", { name: /历史/ }));
    await user.click(screen.getByRole("button", { name: "删除会话 Old question" }));
    await user.click(within(screen.getByRole("dialog", { name: "删除聊天记录" }))
      .getByRole("button", { name: "确认删除" }));
    await waitFor(() => expect(mockApi.deleteConversation).toHaveBeenCalledWith(project.id, "conversation-old"));
    expect(window.location.pathname).toBe("/");
  });

  it("loads only the routed conversation after a direct page visit", async () => {
    localStorage.setItem("agentforge.onboardingComplete", "true");
    sessionStorage.setItem("agentforge.accessToken", "token");
    window.history.replaceState({}, "", "/chat/conversation-direct");
    const mockApi = api({
      getConversation: vi.fn().mockResolvedValue({ conversationId: "conversation-direct", preview: "Direct question",
        messageCount: 2, createdAt: "2026-09-08T00:00:00Z", updatedAt: "2026-09-08T00:01:00Z",
        messages: [
          { role: "USER", content: "Direct question", sources: [], createdAt: "2026-09-08T00:00:00Z" },
          { role: "ASSISTANT", content: "Direct answer", sources: [], createdAt: "2026-09-08T00:01:00Z" },
        ] }),
    });
    render(<App api={mockApi} />);
    expect(await screen.findByText("Direct answer")).toBeInTheDocument();
    expect(mockApi.getConversation).toHaveBeenCalledWith(project.id, "conversation-direct");
    sessionStorage.removeItem("agentforge.accessToken");
  });
  it("recovers an approved action after a direct history refresh and reuses its original key", async () => {
    localStorage.setItem("agentforge.onboardingComplete", "true");
    sessionStorage.setItem("agentforge.accessToken", "token");
    window.history.replaceState({}, "", "/chat/conversation-recovery");
    const approved: AgentAction = {
      id: "action-approved", projectId: project.id, conversationId: "conversation-recovery",
      actionType: "CREATE_TASK", status: "APPROVED", title: "Recovered task", taskStatus: "TODO",
      priority: "HIGH", createdAt: "2026-10-03T00:00:00Z",
    };
    const mockApi = api({
      getConversation: vi.fn().mockResolvedValue({ conversationId: "conversation-recovery", preview: "Recover",
        messageCount: 2, createdAt: "2026-10-03T00:00:00Z", updatedAt: "2026-10-03T00:01:00Z",
        messages: [
          { role: "USER", content: "Recover", sources: [], createdAt: "2026-10-03T00:00:00Z" },
          { role: "ASSISTANT", content: "Review it", sources: [], createdAt: "2026-10-03T00:01:00Z" },
        ] }),
      listRecoverableActions: vi.fn()
        .mockResolvedValueOnce([{ action: approved, source: "CHAT", decisionKey: "original-key" }])
        .mockResolvedValue([]),
      confirmAction: vi.fn().mockResolvedValue({ ...approved, status: "EXECUTED" }),
    });

    render(<App api={mockApi} />);
    const dialog = await screen.findByRole("dialog", { name: "继续执行已批准操作" });
    expect(within(dialog).queryByRole("button", { name: "拒绝" })).not.toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole("button", { name: "继续执行" }));
    await waitFor(() => expect(mockApi.confirmAction)
      .toHaveBeenCalledWith(project.id, "action-approved", "original-key"));
    sessionStorage.removeItem("agentforge.accessToken");
  });
  it("keeps history usable when approval recovery is unavailable", async () => {
    localStorage.setItem("agentforge.onboardingComplete", "true");
    sessionStorage.setItem("agentforge.accessToken", "token");
    window.history.replaceState({}, "", "/chat/conversation-recovery-failure");
    const mockApi = api({
      getConversation: vi.fn().mockResolvedValue({ conversationId: "conversation-recovery-failure", preview: "Recover",
        messageCount: 2, createdAt: "2026-10-03T00:00:00Z", updatedAt: "2026-10-03T00:01:00Z",
        messages: [
          { role: "USER", content: "Still visible", sources: [], createdAt: "2026-10-03T00:00:00Z" },
          { role: "ASSISTANT", content: "History survives", sources: [], createdAt: "2026-10-03T00:01:00Z" },
        ] }),
      listRecoverableActions: vi.fn().mockRejectedValue(new Error("Recovery unavailable")),
    });

    render(<App api={mockApi} />);

    expect(await screen.findByText("History survives")).toBeInTheDocument();
    expect(await screen.findByRole("alert")).toHaveTextContent("Recovery unavailable");
    sessionStorage.removeItem("agentforge.accessToken");
  });
  it("does not automatically reapprove a recovered pending Chat create", async () => {
    localStorage.setItem("agentforge.onboardingComplete", "true");
    sessionStorage.setItem("agentforge.accessToken", "token");
    window.history.replaceState({}, "", "/chat");
    const recovered: AgentAction = {
      id: "action-chat-pending", projectId: project.id, actionType: "CREATE_TASK", status: "PENDING",
      title: "Old pending task", createdAt: "2026-10-01T00:00:00Z",
    };
    const mockApi = api({
      listRecoverableActions: vi.fn().mockResolvedValue([{ action: recovered, source: "CHAT" }]),
    });

    render(<App api={mockApi} />);

    const dialog = await screen.findByRole("dialog", { name: "待确认操作" });
    expect(within(dialog).getByRole("timer")).toHaveTextContent("仍需手动确认");
    expect(mockApi.autoConfirmAction).not.toHaveBeenCalled();
    sessionStorage.removeItem("agentforge.accessToken");
  });
  it("shows a project-level MCP pending action after opening a fresh chat route", async () => {
    localStorage.setItem("agentforge.onboardingComplete", "true");
    sessionStorage.setItem("agentforge.accessToken", "token");
    window.history.replaceState({}, "", "/chat");
    const mcpAction: AgentAction = {
      id: "action-mcp", projectId: project.id, actionType: "CREATE_TASK", status: "PENDING",
      title: "MCP recovered task",
      createdAt: "2026-10-03T00:00:00Z",
    };
    const mockApi = api({
      listRecoverableActions: vi.fn().mockResolvedValue([{ action: mcpAction, source: "MCP" }]),
    });

    render(<App api={mockApi} />);

    const dialog = await screen.findByRole("dialog", { name: "待确认操作" });
    expect(within(dialog).getByText("MCP recovered task")).toBeInTheDocument();
    expect(within(dialog).queryByRole("timer")).toHaveTextContent("仍需手动确认");
    expect(mockApi.listRecoverableActions).toHaveBeenCalledWith(project.id);
    sessionStorage.removeItem("agentforge.accessToken");
  });
  it("advances to the next recoverable action after a decision", async () => {
    localStorage.setItem("agentforge.onboardingComplete", "true");
    sessionStorage.setItem("agentforge.accessToken", "token");
    window.history.replaceState({}, "", "/chat");
    const first: AgentAction = {
      id: "recover-first", projectId: project.id, actionType: "UPDATE_TASK", status: "PENDING",
      taskId: task.id, expectedVersion: 0, title: "First recovery", createdAt: "2026-10-03T00:00:00Z",
    };
    const second: AgentAction = {
      id: "recover-second", projectId: project.id, actionType: "CREATE_TASK", status: "PENDING",
      title: "Second recovery", createdAt: "2026-10-03T00:01:00Z",
    };
    const listRecoverableActions = vi.fn()
      .mockResolvedValueOnce([{ action: first, source: "MCP" }])
      .mockResolvedValueOnce([{ action: second, source: "CHAT" }]);
    const mockApi = api({
      listRecoverableActions,
      rejectAction: vi.fn().mockResolvedValue({ ...first, status: "REJECTED" }),
    });

    render(<App api={mockApi} />);
    await screen.findByText("First recovery");
    await userEvent.click(screen.getByRole("button", { name: "拒绝" }));

    expect(await screen.findByText("Second recovery")).toBeInTheDocument();
    expect(listRecoverableActions).toHaveBeenNthCalledWith(2, project.id, undefined);
    sessionStorage.removeItem("agentforge.accessToken");
  });
  it("does not reopen a conversation after leaving its route while detail is loading", async () => {
    localStorage.setItem("agentforge.onboardingComplete", "true");
    sessionStorage.setItem("agentforge.accessToken", "token");
    window.history.replaceState({}, "", "/chat/conversation-late");
    let resolveDetail!: (detail: Awaited<ReturnType<ApiClient["getConversation"]>>) => void;
    const mockApi = api({
      getConversation: vi.fn().mockReturnValue(new Promise((resolve) => { resolveDetail = resolve; })),
    });
    render(<App api={mockApi} />);
    await waitFor(() => expect(mockApi.getConversation).toHaveBeenCalledWith(project.id, "conversation-late"));

    act(() => {
      window.history.pushState({}, "", "/wiki");
      window.dispatchEvent(new PopStateEvent("popstate"));
    });
    expect(window.location.pathname).toBe("/wiki");
    await act(async () => resolveDetail({ conversationId: "conversation-late", preview: "Late question",
      messageCount: 2, createdAt: "2026-09-08T00:00:00Z", updatedAt: "2026-09-08T00:01:00Z",
      messages: [
        { role: "USER", content: "Late question", sources: [], createdAt: "2026-09-08T00:00:00Z" },
        { role: "ASSISTANT", content: "Late secret", sources: [], createdAt: "2026-09-08T00:01:00Z" },
      ] }));
    expect(window.location.pathname).toBe("/wiki");
    expect(screen.queryByText("Late secret")).not.toBeInTheDocument();
    sessionStorage.removeItem("agentforge.accessToken");
  });
  it("replaces a loaded conversation with the newly selected history entry", async () => {
    localStorage.setItem("agentforge.onboardingComplete", "true");
    const summary = (id: string) => ({ conversationId: id, preview: `Question ${id}`, messageCount: 2,
      createdAt: "2026-09-08T00:00:00Z", updatedAt: "2026-09-08T00:01:00Z" });
    const mockApi = api({
      listConversations: vi.fn().mockResolvedValue([summary("A"), summary("B")]),
      getConversation: vi.fn().mockImplementation(async (_projectId, id: string) => ({ ...summary(id), messages: [
        { role: "USER", content: `Question ${id}`, sources: [], createdAt: "2026-09-08T00:00:00Z" },
        { role: "ASSISTANT", content: `Answer ${id}`, sources: [], createdAt: "2026-09-08T00:01:00Z" },
      ] })),
    });
    const user = await login(mockApi);
    await user.click(screen.getByRole("button", { name: /历史/ }));
    await user.click(await screen.findByRole("button", { name: /^Question A/ }));
    expect(await screen.findByText("Answer A")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /历史/ }));
    await user.click(await screen.findByRole("button", { name: /^Question B/ }));
    expect(await screen.findByText("Answer B")).toBeInTheDocument();
    expect(window.location.pathname).toBe("/chat/B");
    expect(screen.queryByText("Answer A")).not.toBeInTheDocument();
  });
  it("keeps an empty chat route when pending conversation detail arrives late", async () => {
    localStorage.setItem("agentforge.onboardingComplete", "true");
    sessionStorage.setItem("agentforge.accessToken", "token");
    window.history.replaceState({}, "", "/chat/A");
    let resolveDetail!: (detail: Awaited<ReturnType<ApiClient["getConversation"]>>) => void;
    const mockApi = api({ getConversation: vi.fn().mockReturnValue(new Promise((resolve) => { resolveDetail = resolve; })) });
    render(<App api={mockApi} />);
    await waitFor(() => expect(mockApi.getConversation).toHaveBeenCalledWith(project.id, "A"));
    act(() => {
      window.history.pushState({}, "", "/chat");
      window.dispatchEvent(new PopStateEvent("popstate"));
    });
    await act(async () => resolveDetail({ conversationId: "A", preview: "Question A", messageCount: 0,
      createdAt: "2026-09-08T00:00:00Z", updatedAt: "2026-09-08T00:01:00Z", messages: [] }));
    expect(window.location.pathname).toBe("/chat");
    sessionStorage.removeItem("agentforge.accessToken");
  });
  it("does not route back to chat when streaming metadata arrives after leaving", async () => {
    localStorage.setItem("agentforge.onboardingComplete", "true");
    let callbacks!: Parameters<ApiClient["chatStream"]>[3];
    const mockApi = api({ chatStream: vi.fn().mockImplementation((_projectId, _message, _conversationId, receivedCallbacks) => {
      callbacks = receivedCallbacks;
      return new Promise(() => {});
    }) });
    const user = await login(mockApi);
    await user.type(screen.getByLabelText("给 Agent 的消息"), "Draft question");
    await user.click(screen.getByRole("button", { name: "发送" }));
    await waitFor(() => expect(mockApi.chatStream).toHaveBeenCalled());
    await user.click(within(screen.getByLabelText("聊天界面导航")).getByRole("button", { name: /Wiki/ }));
    expect(window.location.pathname).toBe("/wiki");
    act(() => callbacks.onMetadata?.({ conversationId: "late-stream", requestId: "late-request", sources: [] }));
    expect(window.location.pathname).toBe("/wiki");
  });
  it("sets the new conversation route when the first stream metadata arrives", async () => {
    localStorage.setItem("agentforge.onboardingComplete", "true");
    const mockApi = api({ chatStream: vi.fn().mockImplementation(async (_projectId, _message, _conversationId, callbacks) => {
      callbacks.onMetadata?.({ conversationId: "conversation-created", requestId: "created-request", sources: [] });
      return { conversationId: "conversation-created", answer: "Created answer", requestId: "created-request", sources: [] };
    }) });
    const user = await login(mockApi);
    await user.type(screen.getByLabelText("给 Agent 的消息"), "Create conversation");
    await user.click(screen.getByRole("button", { name: "发送" }));
    expect(await screen.findByText("Created answer")).toBeInTheDocument();
    expect(window.location.pathname).toBe("/chat/conversation-created");
  });
  it("opens a persisted conversation from the current project history", async () => {
    const mockApi = api({
      listConversations: vi.fn().mockResolvedValue([{ conversationId: "conversation-old", preview: "Earlier question",
        messageCount: 2, createdAt: "2026-09-08T00:00:00Z", updatedAt: "2026-09-08T00:01:00Z" }]),
      getConversation: vi.fn().mockResolvedValue({ conversationId: "conversation-old", preview: "Earlier question",
        messageCount: 2, createdAt: "2026-09-08T00:00:00Z", updatedAt: "2026-09-08T00:01:00Z",
        messages: [
          { role: "USER", content: "Earlier question", sources: [], createdAt: "2026-09-08T00:00:00Z" },
          { role: "ASSISTANT", content: "Earlier answer", sources: [], createdAt: "2026-09-08T00:01:00Z" },
        ] }),
    });
    const user = await login(mockApi);
    await user.click(within(await screen.findByRole("dialog"))
      .getByRole("button", { name: "开始体验" }));
    await user.click(await screen.findByRole("button", { name: /历史/ }));
    await user.click(await screen.findByRole("button", { name: /^Earlier question/ }));

    expect(await screen.findByText("Earlier answer")).toBeInTheDocument();
    expect(mockApi.getConversation).toHaveBeenCalledWith(project.id, "conversation-old");
    localStorage.removeItem("agentforge.onboardingComplete");
  });

  it("keeps later assistant answers when persisted roles are not strictly paired", async () => {
    const mockApi = api({
      listConversations: vi.fn().mockResolvedValue([{ conversationId: "conversation-irregular", preview: "First question",
        messageCount: 4, createdAt: "2026-09-08T00:00:00Z", updatedAt: "2026-09-08T00:03:00Z" }]),
      getConversation: vi.fn().mockResolvedValue({ conversationId: "conversation-irregular", preview: "First question",
        messageCount: 4, createdAt: "2026-09-08T00:00:00Z", updatedAt: "2026-09-08T00:03:00Z",
        messages: [
          { role: "USER", content: "Interrupted question", sources: [], createdAt: "2026-09-08T00:00:00Z" },
          { role: "USER", content: "Completed question", sources: [], createdAt: "2026-09-08T00:01:00Z" },
          { role: "ASSISTANT", content: "Completed answer", sources: [], createdAt: "2026-09-08T00:02:00Z" },
          { role: "ASSISTANT", content: "Additional assistant detail", sources: [], createdAt: "2026-09-08T00:03:00Z" },
        ] }),
    });
    const user = await login(mockApi);
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "开始体验" }));
    await user.click(await screen.findByRole("button", { name: /历史/ }));
    await user.click(await screen.findByRole("button", { name: /^First question/ }));

    expect(await screen.findByText("Completed answer")).toBeInTheDocument();
    expect(screen.getByText("Additional assistant detail")).toBeInTheDocument();
  });

  it("shows the V2 brand mark in the page chrome", async () => {
    localStorage.setItem("agentforge.onboardingComplete", "true");
    await login(api());
    expect(screen.getByRole("img", { name: "AgentForge" })).toHaveAttribute("src", "/brand-mark.svg");
    expect(screen.getByText("V2 Live Demo")).toBeInTheDocument();
  });

  it("ignores a history response that arrives after the project changes", async () => {
    localStorage.setItem("agentforge.onboardingComplete", "true");
    let resolveDetail!: (detail: Awaited<ReturnType<ApiClient["getConversation"]>>) => void;
    const mockApi = api({
      listProjects: vi.fn().mockResolvedValue([project, secondProject]),
      listConversations: vi.fn().mockImplementation(async (projectId) => projectId === project.id
        ? [{ conversationId: "conversation-old", preview: "Old project question", messageCount: 2,
          createdAt: "2026-09-08T00:00:00Z", updatedAt: "2026-09-08T00:01:00Z" }]
        : []),
      getConversation: vi.fn().mockReturnValue(new Promise((resolve) => { resolveDetail = resolve; })),
    });
    const user = await login(mockApi);
    await user.click(screen.getByRole("button", { name: /历史/ }));
    await user.click(await screen.findByRole("button", { name: /^Old project question/ }));
    await user.click(screen.getByRole("button", { name: /项目/ }));
    await user.click(await screen.findByRole("button", { name: /Second Project/ }));

    resolveDetail({ conversationId: "conversation-old", preview: "Old project question", messageCount: 2,
      createdAt: "2026-09-08T00:00:00Z", updatedAt: "2026-09-08T00:01:00Z", messages: [
        { role: "USER", content: "Old project question", sources: [], createdAt: "2026-09-08T00:00:00Z" },
        { role: "ASSISTANT", content: "Old project secret", sources: [], createdAt: "2026-09-08T00:01:00Z" },
      ] });

    await waitFor(() => expect(mockApi.listConversations).toHaveBeenCalledWith(secondProject.id));
    expect(screen.queryByText("Old project secret")).not.toBeInTheDocument();
    localStorage.removeItem("agentforge.onboardingComplete");
  });

  it("clears conversation state before another user logs in", async () => {
    localStorage.setItem("agentforge.onboardingComplete", "true");
    const loginMock = vi.fn()
      .mockResolvedValueOnce({ accessToken: "token-a", tokenType: "Bearer", expiresIn: 1800,
        user: { id: "user-a", email: "a@example.com", displayName: "A", role: "USER" } })
      .mockResolvedValueOnce({ accessToken: "token-b", tokenType: "Bearer", expiresIn: 1800,
        user: { id: "user-b", email: "b@example.com", displayName: "B", role: "USER" } });
    const mockApi = api({
      login: loginMock,
      listProjects: vi.fn().mockResolvedValueOnce([project]).mockReturnValueOnce(new Promise(() => {})),
      listConversations: vi.fn().mockResolvedValue([{ conversationId: "conversation-a", preview: "User A summary",
        messageCount: 2, createdAt: "2026-09-08T00:00:00Z", updatedAt: "2026-09-08T00:01:00Z" }]),
      getConversation: vi.fn().mockResolvedValue({ conversationId: "conversation-a", preview: "User A summary",
        messageCount: 2, createdAt: "2026-09-08T00:00:00Z", updatedAt: "2026-09-08T00:01:00Z", messages: [
          { role: "USER", content: "A question", sources: [], createdAt: "2026-09-08T00:00:00Z" },
          { role: "ASSISTANT", content: "User A secret", sources: [], createdAt: "2026-09-08T00:01:00Z" },
        ] }),
    });
    const user = await login(mockApi);
    await user.click(screen.getByRole("button", { name: /历史/ }));
    await user.click(await screen.findByRole("button", { name: /^User A summary/ }));
    expect(await screen.findByText("User A secret")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "退出" }));
    await user.clear(screen.getByLabelText("邮箱"));
    await user.type(screen.getByLabelText("邮箱"), "b@example.com");
    await user.type(screen.getByLabelText("密码"), "password-b");
    await user.click(screen.getByRole("button", { name: "登录" }));

    expect(await screen.findByText("你好，我是 AgentForge")).toBeInTheDocument();
    expect(screen.queryByText("User A summary")).not.toBeInTheDocument();
    expect(screen.queryByText("User A secret")).not.toBeInTheDocument();
    localStorage.removeItem("agentforge.onboardingComplete");
  });
  it("shows the project positioning without personal branding", () => {
    render(<App api={api()} />);
    expect(screen.getByText(/把复杂项目，变成可协作的确定性行动/)).toBeInTheDocument();
  });

  it("hides demo credentials and explains where to find them", () => {
    render(<App api={api()} />);

    expect(screen.getByText("账号是简历上的邮箱，密码是微信号")).toBeInTheDocument();
    expect(screen.queryByText("210168y@gmail.com")).not.toBeInTheDocument();
    expect(screen.queryByText("Z1060168")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "填入体验账号" })).not.toBeInTheDocument();
  });

  it.each([
    ["wrong@example.com", "password-123"],
    ["owner@example.com", "wrong-password"],
  ])("shows the same contact message when a login credential is wrong", async (email, password) => {
    const user = userEvent.setup();
    const mockApi = api({ login: vi.fn().mockRejectedValue(new Error("invalid credentials")) });
    render(<App api={mockApi} />);

    await user.type(screen.getByLabelText("邮箱"), email);
    await user.type(screen.getByLabelText("密码"), password);
    await user.click(screen.getByRole("button", { name: "登录" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("请联系我 向我索要体验账号");
  });

  it("guides first-time users and lets them reopen the guide", async () => {
    const user = await login(api());
    const guide = await screen.findByRole("dialog");
    expect(within(guide).getByText(/让项目知识真正参与执行/)).toBeInTheDocument();

    await user.click(within(guide).getByRole("button", { name: "开始体验" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(localStorage.getItem("agentforge.onboardingComplete")).toBe("true");

    await user.click(screen.getByRole("button", { name: "产品说明" }));
    expect(await screen.findByRole("dialog")).toBeInTheDocument();
  });

  it("keeps onboarding usable when browser storage is unavailable", async () => {
    vi.stubGlobal("localStorage", {
      getItem: vi.fn(() => { throw new DOMException("Storage disabled", "SecurityError"); }),
      setItem: vi.fn(() => { throw new DOMException("Storage disabled", "SecurityError"); }),
      clear: vi.fn(), removeItem: vi.fn(), key: vi.fn(), length: 0,
    });

    const user = await login(api());
    const guide = await screen.findByRole("dialog");
    await user.click(within(guide).getByRole("button", { name: "开始体验" }));

    expect(screen.queryByRole("dialog", { name: "新手引导" })).not.toBeInTheDocument();
  });

  it("places project conversation first in the centered main workspace", async () => {
    localStorage.setItem("agentforge.onboardingComplete", "true");
    await login(api());

    const workspace = screen.getByRole("main");
    expect(within(workspace).getAllByRole("heading", { level: 2 })[0]).toHaveTextContent("项目对话");
    expect(workspace).toHaveClass("workspace", "centered-workspace");
  });

  it("logs in and loads the selected project resources", async () => {
    const mockApi = api();
    const user = await login(mockApi);
    expect(mockApi.listProjects).toHaveBeenCalled();
    await waitFor(() => expect(mockApi.listWikiPages).toHaveBeenCalledWith(project.id));
    expect(mockApi.listTasks).toHaveBeenCalledWith(project.id);
    await user.click(screen.getByRole("button", { name: /执行任务/ }));
    expect(await screen.findByText("Ship UI")).toBeInTheDocument();
  });

  it("explains which actions appear in the execution task list", async () => {
    const user = await login(api());

    await user.click(screen.getByRole("button", { name: /执行任务/ }));
    expect(screen.getByRole("heading", { name: "执行任务" })).toBeInTheDocument();
    expect(screen.getByText(/普通提问和 Wiki 保存不会新增任务/)).toBeInTheDocument();
  });

  it("keeps the latest project conversation open and older answers expandable", async () => {
    const streamMock = vi.fn()
      .mockResolvedValueOnce({ conversationId: "conversation-1", answer: "First answer", requestId: "r-first", sources: [] })
      .mockResolvedValueOnce({ conversationId: "conversation-1", answer: "Second answer", requestId: "r-second", sources: [] });
    const user = await login(api({ chatStream: streamMock }));

    await user.type(screen.getByLabelText("给 Agent 的消息"), "First question");
    await user.click(screen.getByRole("button", { name: "发送" }));
    expect(await screen.findByText("First answer")).toBeInTheDocument();

    await user.type(screen.getByLabelText("给 Agent 的消息"), "Second question");
    await user.click(screen.getByRole("button", { name: "发送" }));
    expect(await screen.findByText("Second answer")).toBeInTheDocument();
    expect(screen.getByText("First answer")).toBeInTheDocument();

    const olderToggle = screen.getByRole("button", { name: /First question/ });
    expect(olderToggle).toHaveAttribute("aria-expanded", "true");
    await user.click(olderToggle);
    expect(screen.queryByText("First answer")).not.toBeInTheDocument();
  });

  it("opens the chat composer compact and lets the user expand and shrink it", async () => {
    const user = await login(api({ chatStream: vi.fn().mockResolvedValue({
      conversationId: "compact-chat", answer: "Ready", requestId: "r-compact", sources: [],
    }) }));
    await user.type(screen.getByLabelText("给 Agent 的消息"), "hello");
    await user.click(screen.getByRole("button", { name: "发送" }));
    expect(await screen.findByText("Ready")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "放大聊天输入框" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "放大聊天输入框" }));
    expect(screen.getByRole("button", { name: "缩小聊天输入框" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "缩小聊天输入框" }));
    expect(screen.getByRole("button", { name: "放大聊天输入框" })).toBeInTheDocument();
  });

  it("keeps each answer's cited articles collapsed until opened", async () => {
    const chatStream = vi.fn()
      .mockResolvedValueOnce({ conversationId: "cited-chat", answer: "First answer", requestId: "r1",
        sources: [{ sourceType: "WIKI", sourceId: "wiki-a", title: "Architecture", excerpt: "Core owns writes" }] })
      .mockResolvedValueOnce({ conversationId: "cited-chat", answer: "Second answer", requestId: "r2",
        sources: [{ sourceType: "REPOSITORY", sourceId: "repo-b", title: "README.md",
          excerpt: "<img src=x onerror=alert(1)> Core entry" }] });
    const user = await login(api({ chatStream }));
    await user.type(screen.getByLabelText("给 Agent 的消息"), "first");
    await user.click(screen.getByRole("button", { name: "发送" }));
    expect(await screen.findByText("First answer")).toBeInTheDocument();
    await user.type(screen.getByLabelText("给 Agent 的消息"), "second");
    await user.click(screen.getByRole("button", { name: "发送" }));
    expect(await screen.findByText("Second answer")).toBeInTheDocument();
    expect(screen.queryByText("Architecture")).not.toBeInTheDocument();
    expect(screen.queryByText("README.md")).not.toBeInTheDocument();
    const sourceButtons = screen.getAllByRole("button", { name: /引用来源/ });
    expect(sourceButtons).toHaveLength(2);
    await user.click(sourceButtons[0]);
    expect(screen.getByText("Architecture")).toBeInTheDocument();
    expect(screen.queryByText("README.md")).not.toBeInTheDocument();
    await user.click(sourceButtons[0]);
    expect(screen.queryByText("Architecture")).not.toBeInTheDocument();
    await user.click(sourceButtons[1]);
    expect(screen.getByText("README.md")).toBeInTheDocument();
    expect(screen.getByText("<img src=x onerror=alert(1)> Core entry")).toBeInTheDocument();
    const citationContainer = screen.getByText("README.md").closest(".sources-list");
    expect(citationContainer).not.toBeNull();
    expect(citationContainer!.querySelector("img")).toBeNull();
  });

  it("shows pending action above chat content and can reopen it after Escape without deciding", async () => {
    const pending: AgentAction = { id: "action-overlay", projectId: project.id, conversationId: "overlay-chat",
      actionType: "CREATE_TASK", status: "PENDING", title: "Review access", description: "Check rollout",
      priority: "HIGH", taskStatus: "TODO", createdAt: "2026-09-05T00:00:00Z" };
    const mockApi = api({ chatStream: vi.fn().mockResolvedValue({ conversationId: "overlay-chat", answer: "Please review",
      requestId: "r-overlay", sources: [{ sourceType: "WIKI", sourceId: "wiki-1", title: "Plan", excerpt: "Scope" }],
      pendingAction: pending }) });
    const user = await login(mockApi);
    await user.type(screen.getByLabelText("给 Agent 的消息"), "create task");
    await user.click(screen.getByRole("button", { name: "发送" }));
    const dialog = await screen.findByRole("dialog", { name: /待确认操作/ });
    expect(dialog.parentElement?.parentElement).toBe(document.body);
    expect(within(dialog).getByText("Review access")).toBeInTheDocument();
    expect(within(dialog).getByText("Check rollout")).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "稍后处理" })).toHaveFocus();
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("dialog", { name: /待确认操作/ })).not.toBeInTheDocument();
    expect(mockApi.confirmAction).not.toHaveBeenCalled();
    expect(mockApi.rejectAction).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "继续处理待确认操作" }));
    expect(screen.getByRole("dialog", { name: /待确认操作/ })).toBeInTheDocument();
  });

  it("routes an elapsed low-risk dialog through Java auto-confirm", async () => {
    const pending: AgentAction = { id: "action-auto", projectId: project.id,
      conversationId: "conversation-auto", actionType: "CREATE_TASK", status: "PENDING",
      title: "Auto task", createdAt: "2026-09-20T00:00:00Z" };
    const autoConfirmAction = vi.fn().mockResolvedValue({ ...pending, status: "EXECUTED", resultTask: task });
    const mockApi = api({ chatStream: vi.fn().mockResolvedValue({
      conversationId: "conversation-auto", answer: "Review", requestId: "r-auto",
      sources: [], pendingAction: pending,
    }), autoConfirmAction });
    const user = await login(mockApi);
    await user.type(screen.getByLabelText("给 Agent 的消息"), "create task");
    await user.click(screen.getByRole("button", { name: "发送" }));
    await screen.findByRole("dialog", { name: /待确认操作/ });
    const future = Date.now() + 61_000;
    const clock = vi.spyOn(Date, "now").mockReturnValue(future);
    try {
      await waitFor(() => expect(autoConfirmAction).toHaveBeenCalledWith(
        project.id, pending.id, expect.any(String),
      ));
      expect(mockApi.confirmAction).not.toHaveBeenCalled();
    } finally {
      clock.mockRestore();
    }
  });

  it("confirms a pending action through Java before refreshing tasks", async () => {
    const pending: AgentAction = {
      id: "action-1", projectId: project.id, conversationId: "conversation-1", actionType: "CREATE_TASK",
      status: "PENDING", title: "Review auth", priority: "HIGH", createdAt: "2026-09-05T00:00:00Z",
    };
    const chat: AgentChat = { conversationId: "conversation-1", answer: "Please confirm", requestId: "r1", sources: [], pendingAction: pending };
    const mockApi = api({ chatStream: vi.fn().mockImplementation(async (_projectId, _message, _conversationId, callbacks) => {
      callbacks.onDelta("Please ");
      callbacks.onDelta("confirm");
      return chat;
    }),
      confirmAction: vi.fn().mockResolvedValue({ ...pending, status: "EXECUTED", resultTask: task }) });
    const user = await login(mockApi);
    await user.type(screen.getByLabelText("给 Agent 的消息"), "create task");
    await user.click(screen.getByRole("button", { name: "发送" }));
    expect(await screen.findByText("Review auth")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "确认执行" }));
    await waitFor(() => expect(mockApi.confirmAction).toHaveBeenCalledWith(
      project.id, pending.id, expect.any(String),
    ));
    expect(mockApi.listTasks).toHaveBeenCalledTimes(2);
  });

  it("reuses the same idempotency key when a confirmation is retried", async () => {
    const pending: AgentAction = {
      id: "action-retry", projectId: project.id, conversationId: "conversation-retry",
      actionType: "CREATE_TASK", status: "PENDING", title: "Retry safely", createdAt: "2026-09-05T00:00:00Z",
    };
    const confirmAction = vi.fn()
      .mockRejectedValueOnce(new Error("response lost"))
      .mockResolvedValueOnce({ ...pending, status: "EXECUTED", resultTask: task });
    const mockApi = api({
      chatStream: vi.fn().mockResolvedValue({
        conversationId: "conversation-retry", answer: "Review", requestId: "r-retry", sources: [], pendingAction: pending,
      }),
      confirmAction,
    });
    const user = await login(mockApi);
    await user.type(screen.getByLabelText("给 Agent 的消息"), "create task");
    await user.click(screen.getByRole("button", { name: "发送" }));
    await user.click(await screen.findByRole("button", { name: "确认执行" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("response lost");
    await user.click(screen.getByRole("button", { name: "确认执行" }));

    await waitFor(() => expect(confirmAction).toHaveBeenCalledTimes(2));
    expect(confirmAction.mock.calls[0][2]).toBeTruthy();
    expect(confirmAction.mock.calls[1][2]).toBe(confirmAction.mock.calls[0][2]);
  });

  it("shows a stable failure result without refreshing tasks", async () => {
    const pending: AgentAction = {
      id: "action-failed", projectId: project.id, conversationId: "conversation-failed",
      actionType: "UPDATE_TASK", status: "PENDING", taskId: task.id, expectedVersion: task.version,
      title: "Stale update", createdAt: "2026-09-05T00:00:00Z",
    };
    const mockApi = api({
      chatStream: vi.fn().mockResolvedValue({
        conversationId: "conversation-failed", answer: "Review", requestId: "r-failed", sources: [], pendingAction: pending,
      }),
      confirmAction: vi.fn().mockResolvedValue({ ...pending, status: "FAILED", decidedAt: "2026-09-08T00:00:00Z" }),
    });
    const user = await login(mockApi);
    await user.type(screen.getByLabelText("给 Agent 的消息"), "update task");
    await user.click(screen.getByRole("button", { name: "发送" }));
    await user.click(await screen.findByRole("button", { name: "确认执行" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("执行失败");
    expect(mockApi.listTasks).toHaveBeenCalledTimes(1);
    expect(screen.queryByText("Stale update")).not.toBeInTheDocument();
  });

  it("rejects a pending action without refreshing tasks", async () => {
    const pending: AgentAction = {
      id: "action-2", projectId: project.id, conversationId: "conversation-1", actionType: "CREATE_TASK",
      status: "PENDING", title: "Do not create", createdAt: "2026-09-05T00:00:00Z",
    };
    const mockApi = api({
      chatStream: vi.fn().mockResolvedValue({ conversationId: "conversation-1", answer: "Review", requestId: "r3", sources: [], pendingAction: pending }),
      rejectAction: vi.fn().mockResolvedValue({ ...pending, status: "REJECTED" }),
    });
    const user = await login(mockApi);
    await user.type(screen.getByLabelText("给 Agent 的消息"), "create task");
    await user.click(screen.getByRole("button", { name: "发送" }));
    await user.click(await screen.findByRole("button", { name: "拒绝" }));

    await waitFor(() => expect(mockApi.rejectAction).toHaveBeenCalledWith(
      project.id, pending.id, expect.any(String),
    ));
    expect(mockApi.listTasks).toHaveBeenCalledTimes(1);
    expect(screen.queryByText("Do not create")).not.toBeInTheDocument();
  });

  it("creates a wiki page only after save is clicked", async () => {
    const saved: WikiPage = {
      id: "wiki-1", projectId: project.id, title: "Architecture", content: "# Core", version: 0,
      createdAt: "2026-09-05T00:00:00Z", updatedAt: "2026-09-05T00:00:00Z",
    };
    const mockApi = api({
      createWikiPage: vi.fn().mockResolvedValue(saved),
      listWikiPages: vi.fn().mockResolvedValueOnce([]).mockResolvedValueOnce([saved]),
    });
    const user = await login(mockApi);
    await user.click(screen.getByRole("button", { name: "Wiki 工作台" }));
    expect(window.location.pathname).toBe("/wiki");
    await user.type(await screen.findByLabelText("Wiki 标题"), "Architecture");
    await user.type(screen.getByLabelText("Wiki Markdown 草稿"), "# Core");
    expect(mockApi.createWikiPage).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "保存 Wiki" }));

    await waitFor(() => expect(mockApi.createWikiPage).toHaveBeenCalledWith(project.id, "Architecture", "# Core"));
    await user.click(screen.getByRole("button", { name: "打开预览" }));
    expect(await screen.findByRole("heading", { name: "Core" })).toBeInTheDocument();
  });

  it("confirms a successful existing Wiki save with the latest version", async () => {
    const existing: WikiPage = {
      id: "wiki-1", projectId: project.id, title: "Architecture", content: "# Before", version: 3,
      createdAt: "2026-09-05T00:00:00Z", updatedAt: "2026-09-05T00:00:00Z",
    };
    const saved = { ...existing, content: "# After", version: 4, updatedAt: "2026-09-07T00:00:00Z" };
    const mockApi = api({
      listWikiPages: vi.fn().mockResolvedValueOnce([existing]).mockResolvedValueOnce([saved]),
      updateWikiPage: vi.fn().mockResolvedValue(saved),
    });
    const user = await login(mockApi);
    await user.click(screen.getByRole("button", { name: "Wiki 工作台" }));
    const editor = screen.getByLabelText("Wiki Markdown 草稿");
    await waitFor(() => expect(editor).toHaveValue("# Before"));
    await user.clear(editor);
    await user.type(editor, "# After");
    await user.click(screen.getByRole("button", { name: "保存 Wiki" }));

    await waitFor(() => expect(mockApi.updateWikiPage).toHaveBeenCalledWith(
      project.id, existing.id, existing.title, "# After", 3,
    ));
    expect(await screen.findByRole("status")).toHaveTextContent("Wiki 已保存");
  });

  it("keeps the wiki draft until AI text is explicitly applied", async () => {
    const scrollIntoView = vi.fn();
    Object.defineProperty(HTMLElement.prototype, "scrollIntoView", {
      configurable: true, value: scrollIntoView,
    });
    const formatted = "```markdown\n# Structured\n\nKeep this.\n```";
    const formatStream = vi.fn().mockImplementation(async (_projectId, _message, _conversationId, callbacks) => {
      callbacks.onDelta("```markdown\n# Structured");
      callbacks.onDelta("\n\nKeep this.\n```");
      return { conversationId: "format-only", answer: formatted, requestId: "r2", sources: [] };
    });
    const mockApi = api({ chatStream: formatStream });
    const user = await login(mockApi);
    await user.click(screen.getByRole("button", { name: "Wiki 工作台" }));
    const editor = screen.getByLabelText("Wiki Markdown 草稿");
    await user.type(editor, "Original draft");
    await user.click(screen.getByRole("button", { name: "AI 文本整理" }));
    await user.type(await screen.findByLabelText("待整理原文"), "messy notes");
    await user.click(screen.getByRole("button", { name: "AI 整理并预览" }));
    expect(await screen.findByRole("heading", { name: "Structured" })).toBeInTheDocument();
    expect(formatStream).toHaveBeenCalledWith(
      project.id,
      expect.stringContaining("messy notes"),
      undefined,
      expect.any(Object),
      expect.any(AbortSignal),
      "FORMAT",
    );
    await user.click(screen.getByRole("button", { name: "Wiki 工作台" }));
    expect(screen.getByLabelText("Wiki Markdown 草稿")).toHaveValue("Original draft");
    await user.click(screen.getByRole("button", { name: "AI 文本整理" }));
    await user.click(screen.getByRole("button", { name: "应用到 Wiki 草稿" }));
    expect(screen.getByLabelText("Wiki Markdown 草稿")).toHaveValue("# Structured\n\nKeep this.");
    expect(scrollIntoView).toHaveBeenCalledWith({ behavior: "smooth", block: "start" });
    expect(screen.getByRole("status")).toHaveTextContent("已应用到 Wiki 草稿，请确认后保存");
  });

  it("shows real formatting deltas before completion without a full-page code block", async () => {
    let completeStream!: (value: AgentChat) => void;
    const answer = "```markdown\n# Streaming title\n\nBody\n```";
    const streamMock = vi.fn().mockImplementation(async (_projectId, _message, _conversationId, callbacks) => {
      callbacks.onDelta("```markdown\n# Streaming title");
      return new Promise<AgentChat>((resolve) => { completeStream = resolve; });
    });
    const user = await login(api({ chatStream: streamMock }));

    await user.click(screen.getByRole("button", { name: "AI 文本整理" }));
    await user.type(await screen.findByLabelText("待整理原文"), "stream these notes");
    await user.click(screen.getByRole("button", { name: "AI 整理并预览" }));

    expect(await screen.findByText("# Streaming title")).toBeInTheDocument();
    expect(document.querySelector(".format-panel pre")).toBeNull();
    expect(screen.getByRole("button", { name: "应用到 Wiki 草稿" })).toBeDisabled();

    completeStream({ conversationId: "format-stream", answer, requestId: "r-stream", sources: [] });
    expect(await screen.findByRole("heading", { name: "Streaming title" })).toBeInTheDocument();
  });

  it("applies formatted text as a titled new wiki page and never overwrites the selected page", async () => {
    const existing: WikiPage = {
      id: "wiki-existing", projectId: project.id, title: "Existing page", content: "# Existing", version: 4,
      createdAt: "2026-09-05T00:00:00Z", updatedAt: "2026-09-05T00:00:00Z",
    };
    const created: WikiPage = {
      id: "wiki-created", projectId: project.id, title: "Generated title", content: "# Generated title\n\nBody", version: 0,
      createdAt: "2026-09-07T00:00:00Z", updatedAt: "2026-09-07T00:00:00Z",
    };
    const answer = "# Generated title\n\nBody";
    const mockApi = api({
      listWikiPages: vi.fn().mockResolvedValueOnce([existing]).mockResolvedValueOnce([existing, created]),
      chatStream: vi.fn().mockImplementation(async (_projectId, _message, _conversationId, callbacks) => {
        callbacks.onDelta(answer);
        return { conversationId: "format-new-page", answer, requestId: "r-new-page", sources: [] };
      }),
      createWikiPage: vi.fn().mockResolvedValue(created),
      updateWikiPage: vi.fn(),
    });
    const user = await login(mockApi);
    await user.click(screen.getByRole("button", { name: "Wiki 工作台" }));
    await waitFor(() => expect(screen.getByLabelText("Wiki 标题")).toHaveValue("Existing page"));

    await user.click(screen.getByRole("button", { name: "AI 文本整理" }));
    await user.type(await screen.findByLabelText("待整理原文"), "generate a new page");
    await user.click(screen.getByRole("button", { name: "AI 整理并预览" }));
    expect(mockApi.chatStream).toHaveBeenCalledWith(
      project.id,
      expect.stringContaining("一级标题"),
      undefined,
      expect.any(Object),
      expect.any(AbortSignal),
      "FORMAT",
    );
    await user.click(await screen.findByRole("button", { name: "应用到 Wiki 草稿" }));

    expect(screen.getByLabelText("Wiki 标题")).toHaveValue("Generated title");
    expect(screen.getByLabelText("Wiki Markdown 草稿")).toHaveValue(answer);
    expect(screen.queryByRole("button", { name: "应用到 Wiki 草稿" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "保存 Wiki" }));
    await waitFor(() => expect(mockApi.createWikiPage).toHaveBeenCalledWith(
      project.id, "Generated title", answer,
    ));
    expect(mockApi.updateWikiPage).not.toHaveBeenCalled();
  });

  it("uses the default wiki title when hash text only appears inside a code fence", async () => {
    const answer = "Notes without a heading.\n\n```sh\n# not-a-heading\necho ok\n```";
    const mockApi = api({
      chatStream: vi.fn().mockImplementation(async (_projectId, _message, _conversationId, callbacks) => {
        callbacks.onDelta(answer);
        return { conversationId: "format-default-title", answer, requestId: "r-default-title", sources: [] };
      }),
    });
    const user = await login(mockApi);

    await user.click(screen.getByRole("button", { name: "AI 文本整理" }));
    await user.type(await screen.findByLabelText("待整理原文"), "notes with code");
    await user.click(screen.getByRole("button", { name: "AI 整理并预览" }));
    await user.click(await screen.findByRole("button", { name: "应用到 Wiki 草稿" }));

    expect(screen.getByLabelText("Wiki 标题")).toHaveValue("AI 整理文档");
  });

  it("keeps code blocks inside formatted Markdown", async () => {
    const answer = "# Notes\n\n```ts\nconst answer = 42;\n```";
    const mockApi = api({ chatStream: vi.fn().mockImplementation(async (_projectId, _message, _conversationId, callbacks) => {
      callbacks.onDelta(answer);
      return { conversationId: "conversation-code", answer, requestId: "r-code", sources: [] };
    }) });
    const user = await login(mockApi);
    await user.click(screen.getByRole("button", { name: "AI 文本整理" }));
    await user.type(await screen.findByLabelText("待整理原文"), "code notes");
    await user.click(screen.getByRole("button", { name: "AI 整理并预览" }));

    expect(await screen.findByRole("heading", { name: "Notes" })).toBeInTheDocument();
    expect(screen.getByText("const answer = 42;").closest("pre")).toBeInTheDocument();
  });

  it("keeps formatting isolated from chat without an approval result", async () => {
    const streamMock = vi.fn()
      .mockResolvedValueOnce({ conversationId: "project-conversation", answer: "Chat answer", requestId: "r4", sources: [] })
      .mockResolvedValueOnce({
        conversationId: "format-conversation", answer: "# Formatted", requestId: "r5", sources: [],
      });
    const mockApi = api({ chatStream: streamMock });
    const user = await login(mockApi);
    await user.type(screen.getByLabelText("给 Agent 的消息"), "project question");
    await user.click(screen.getByRole("button", { name: "发送" }));
    await user.click(screen.getByRole("button", { name: /整理/ }));
    await user.type(await screen.findByLabelText("待整理原文"), "format me");
    await user.click(screen.getByRole("button", { name: "AI 整理并预览" }));

    await waitFor(() => expect(streamMock).toHaveBeenCalledTimes(2));
    expect(streamMock.mock.calls[1]?.[2]).toBeUndefined();
    expect(await screen.findByRole("heading", { name: "Formatted" })).toBeInTheDocument();
    expect(screen.getByText("会话 project-")).toBeInTheDocument();
  });

  it("prevents AI formatting while project chat is streaming", async () => {
    const streamMock = vi.fn().mockReturnValue(new Promise(() => {}));
    const mockApi = api({ chatStream: streamMock });
    const user = await login(mockApi);
    await user.click(screen.getByRole("button", { name: "AI 文本整理" }));
    await user.type(await screen.findByLabelText("待整理原文"), "format me");
    await user.type(screen.getByLabelText("给 Agent 的消息"), "project question");
    await user.click(screen.getByRole("button", { name: "发送" }));
    await user.click(screen.getByRole("button", { name: /整理/ }));

    expect(screen.getByRole("button", { name: "AI 整理并预览" })).toBeDisabled();
  });

  it("prevents applying partial formatting or starting chat while formatting streams", async () => {
    const streamMock = vi.fn().mockImplementation(async (_projectId, _message, _conversationId, callbacks) => {
      callbacks.onDelta("# Partial");
      return new Promise(() => {});
    });
    const user = await login(api({ chatStream: streamMock }));
    await user.click(screen.getByRole("button", { name: "AI 文本整理" }));
    await user.type(screen.getByLabelText("给 Agent 的消息"), "project question");
    await user.type(await screen.findByLabelText("待整理原文"), "format me");
    await user.click(screen.getByRole("button", { name: "AI 整理并预览" }));

    expect(await screen.findByText("# Partial")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "应用到 Wiki 草稿" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "发送" })).toBeDisabled();
  });

  it("discards a partial formatting result when the stream fails", async () => {
    const mockApi = api({
      chatStream: vi.fn().mockImplementation(async (_projectId, _message, _conversationId, callbacks) => {
        callbacks.onDelta("# Incomplete");
        throw new ApiProblem(503, "AI service is temporarily unavailable.", "request-503");
      }),
    });
    const user = await login(mockApi);
    await user.click(screen.getByRole("button", { name: "AI 文本整理" }));
    await user.type(await screen.findByLabelText("待整理原文"), "format me");
    await user.click(screen.getByRole("button", { name: "AI 整理并预览" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("AI service is temporarily unavailable. · request request-503");
    expect(screen.queryByRole("heading", { name: "Incomplete" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "应用到 Wiki 草稿" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "AI 整理并预览" })).toBeEnabled();
  });

  it("clears formatting drafts when the selected project changes", async () => {
    let formatSignal: AbortSignal | undefined;
    const mockApi = api({
      listProjects: vi.fn().mockResolvedValue([project, secondProject]),
      chatStream: vi.fn().mockImplementation(async (_projectId, _message, _conversationId, callbacks, signal) => {
        formatSignal = signal;
        callbacks.onDelta("# Project one");
        return new Promise((_resolve, reject) => {
          signal.addEventListener("abort", () => reject(new DOMException("Aborted", "AbortError")), { once: true });
        });
      }),
    });
    const user = await login(mockApi);
    await user.click(screen.getByRole("button", { name: "AI 文本整理" }));
    await user.type(await screen.findByLabelText("待整理原文"), "project one notes");
    await user.click(screen.getByRole("button", { name: "AI 整理并预览" }));
    expect(await screen.findByText("# Project one")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /项目/ }));
    await user.click(await screen.findByRole("button", { name: /Second Project/ }));
    expect(formatSignal?.aborted).toBe(true);
    expect(screen.getByLabelText("待整理原文")).toHaveValue("");
    expect(screen.queryByText("# Project one")).not.toBeInTheDocument();
  });

  it("does not apply a Wiki save response after switching projects", async () => {
    localStorage.setItem("agentforge.onboardingComplete", "true");
    const savedInFirst: WikiPage = {
      id: "wiki-first", projectId: project.id, title: "First project saved", content: "# First", version: 0,
      createdAt: "2026-10-04T00:00:00Z", updatedAt: "2026-10-04T00:00:00Z",
    };
    const secondWiki: WikiPage = {
      id: "wiki-second", projectId: secondProject.id, title: "Second project page", content: "# Second", version: 2,
      createdAt: "2026-10-04T00:00:00Z", updatedAt: "2026-10-04T00:00:00Z",
    };
    let resolveSave!: (page: WikiPage) => void;
    let saveFinished = false;
    const mockApi = api({
      listProjects: vi.fn().mockResolvedValue([project, secondProject]),
      listWikiPages: vi.fn().mockImplementation(async (selectedProjectId) => {
        if (selectedProjectId === secondProject.id) return [secondWiki];
        return saveFinished ? [savedInFirst] : [];
      }),
      createWikiPage: vi.fn().mockReturnValue(new Promise<WikiPage>((resolve) => { resolveSave = resolve; })),
    });
    const user = await login(mockApi);
    await user.click(screen.getByRole("button", { name: "Wiki 工作台" }));
    await user.type(await screen.findByLabelText("Wiki 标题"), "First project saved");
    await user.type(screen.getByLabelText("Wiki Markdown 草稿"), "# First");
    await user.click(screen.getByRole("button", { name: "保存 Wiki" }));
    await waitFor(() => expect(mockApi.createWikiPage).toHaveBeenCalledWith(project.id, "First project saved", "# First"));

    await user.click(screen.getByRole("button", { name: /项目/ }));
    await user.click(await screen.findByRole("button", { name: /Second Project/ }));
    await waitFor(() => expect(screen.getByLabelText("Wiki 标题")).toHaveValue("Second project page"));

    saveFinished = true;
    await act(async () => resolveSave(savedInFirst));
    expect(screen.getByLabelText("Wiki 标题")).toHaveValue("Second project page");
    expect(screen.queryByText(/Wiki 已保存/)).not.toBeInTheDocument();
  });

  it("does not apply a Wiki save response after logout and another login", async () => {
    localStorage.setItem("agentforge.onboardingComplete", "true");
    const savedForFirstUser: WikiPage = {
      id: "wiki-user-a", projectId: project.id, title: "User A saved", content: "# A", version: 0,
      createdAt: "2026-10-04T00:00:00Z", updatedAt: "2026-10-04T00:00:00Z",
    };
    const secondUserWiki: WikiPage = {
      id: "wiki-user-b", projectId: project.id, title: "User B page", content: "# B", version: 3,
      createdAt: "2026-10-04T00:00:00Z", updatedAt: "2026-10-04T00:00:00Z",
    };
    let resolveSave!: (page: WikiPage) => void;
    const loginMock = vi.fn()
      .mockResolvedValueOnce({ accessToken: "token-a", tokenType: "Bearer", expiresIn: 1800,
        user: { id: "user-a", email: "a@example.com", displayName: "A", role: "USER" } })
      .mockResolvedValueOnce({ accessToken: "token-b", tokenType: "Bearer", expiresIn: 1800,
        user: { id: "user-b", email: "b@example.com", displayName: "B", role: "USER" } });
    const listWikiPages = vi.fn()
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([secondUserWiki])
      .mockResolvedValueOnce([savedForFirstUser]);
    const mockApi = api({
      login: loginMock,
      listWikiPages,
      createWikiPage: vi.fn().mockReturnValue(new Promise<WikiPage>((resolve) => { resolveSave = resolve; })),
    });
    const user = await login(mockApi);
    await user.click(screen.getByRole("button", { name: "Wiki 工作台" }));
    await user.type(await screen.findByLabelText("Wiki 标题"), "User A saved");
    await user.click(screen.getByRole("button", { name: "保存 Wiki" }));
    await waitFor(() => expect(mockApi.createWikiPage).toHaveBeenCalled());

    await user.click(screen.getByRole("button", { name: "退出" }));
    await user.clear(screen.getByLabelText("邮箱"));
    await user.type(screen.getByLabelText("邮箱"), "b@example.com");
    await user.type(screen.getByLabelText("密码"), "password-b");
    await user.click(screen.getByRole("button", { name: "登录" }));
    await user.click(await screen.findByRole("button", { name: "Wiki 工作台" }));
    await waitFor(() => expect(screen.getByLabelText("Wiki 标题")).toHaveValue("User B page"));

    await act(async () => resolveSave(savedForFirstUser));
    expect(screen.getByLabelText("Wiki 标题")).toHaveValue("User B page");
    expect(screen.queryByText(/Wiki 已保存/)).not.toBeInTheDocument();
  });

  it("ignores an older formatting stream after returning to the same project", async () => {
    localStorage.setItem("agentforge.onboardingComplete", "true");
    type StreamCallbacks = Parameters<ApiClient["chatStream"]>[3];
    const callbacks: StreamCallbacks[] = [];
    const resolvers: Array<(chat: AgentChat) => void> = [];
    const chatStream = vi.fn().mockImplementation((_projectId, _message, _conversationId, receivedCallbacks) => {
      callbacks.push(receivedCallbacks);
      return new Promise<AgentChat>((resolve) => { resolvers.push(resolve); });
    });
    const user = await login(api({ listProjects: vi.fn().mockResolvedValue([project, secondProject]), chatStream }));
    await user.click(screen.getByRole("button", { name: "AI 文本整理" }));
    await user.type(await screen.findByLabelText("待整理原文"), "old notes");
    await user.click(screen.getByRole("button", { name: "AI 整理并预览" }));
    await waitFor(() => expect(chatStream).toHaveBeenCalledTimes(1));

    await user.click(screen.getByRole("button", { name: /项目/ }));
    await user.click(await screen.findByRole("button", { name: /Second Project/ }));
    await user.click(screen.getByRole("button", { name: /项目/ }));
    await user.click(await screen.findByRole("button", { name: /AgentForge/ }));
    await user.type(screen.getByLabelText("待整理原文"), "current notes");
    await user.click(screen.getByRole("button", { name: "AI 整理并预览" }));
    await waitFor(() => expect(chatStream).toHaveBeenCalledTimes(2));
    act(() => callbacks[1].onDelta?.("# Current result"));
    expect(await screen.findByText("# Current result")).toBeInTheDocument();

    act(() => callbacks[0].onDelta?.("# Stale result"));
    await act(async () => resolvers[0]({ conversationId: "old-format", answer: "# Stale final",
      requestId: "old-request", sources: [] }));
    expect(screen.getByText("# Current result")).toBeInTheDocument();
    expect(screen.queryByText(/Stale/)).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "AI 整理并预览" })).toBeDisabled();

    await act(async () => resolvers[1]({ conversationId: "current-format", answer: "# Current result",
      requestId: "current-request", sources: [] }));
  });

  it("does not let an old approval completion clear a new project's pending action", async () => {
    localStorage.setItem("agentforge.onboardingComplete", "true");
    const oldAction: AgentAction = {
      id: "action-old", projectId: project.id, conversationId: "conversation-old", actionType: "CREATE_TASK",
      status: "PENDING", title: "Old project action", createdAt: "2026-10-04T00:00:00Z",
    };
    const newAction: AgentAction = {
      id: "action-new", projectId: secondProject.id, actionType: "CREATE_TASK",
      status: "PENDING", title: "New project action", createdAt: "2026-10-04T00:01:00Z",
    };
    let resolveDecision!: (action: AgentAction) => void;
    const confirmAction = vi.fn().mockReturnValue(new Promise<AgentAction>((resolve) => { resolveDecision = resolve; }));
    const mockApi = api({
      listProjects: vi.fn().mockResolvedValue([project, secondProject]),
      chatStream: vi.fn().mockResolvedValue({ conversationId: "conversation-old", answer: "Review old action",
        requestId: "old-chat", sources: [], pendingAction: oldAction }),
      listRecoverableActions: vi.fn().mockImplementation(async (selectedProjectId) => selectedProjectId === secondProject.id
        ? [{ action: newAction, source: "CHAT" as const }]
        : []),
      confirmAction,
    });
    const user = await login(mockApi);
    await user.type(screen.getByLabelText("给 Agent 的消息"), "create old task");
    await user.click(screen.getByRole("button", { name: "发送" }));
    await user.click(await screen.findByRole("button", { name: "确认执行" }));
    await waitFor(() => expect(confirmAction).toHaveBeenCalledTimes(1));

    await user.click(screen.getByRole("button", { name: /项目/ }));
    await user.click(await screen.findByRole("button", { name: /Second Project/ }));
    expect(await screen.findByText("New project action")).toBeInTheDocument();

    await act(async () => resolveDecision({ ...oldAction, status: "EXECUTED" }));
    expect(screen.getByText("New project action")).toBeInTheDocument();
    expect(confirmAction).toHaveBeenCalledTimes(1);
  });

  it("restores chat controls when switching projects aborts an active stream", async () => {
    localStorage.setItem("agentforge.onboardingComplete", "true");
    let streamSignal: AbortSignal | undefined;
    const chatStream = vi.fn().mockImplementation((_projectId, _message, _conversationId, _callbacks, signal) => {
      streamSignal = signal;
      return new Promise<AgentChat>((_resolve, reject) => {
        signal.addEventListener("abort", () => reject(new DOMException("Aborted", "AbortError")), { once: true });
      });
    });
    const user = await login(api({ listProjects: vi.fn().mockResolvedValue([project, secondProject]), chatStream }));
    await user.type(screen.getByLabelText("给 Agent 的消息"), "old project question");
    await user.click(screen.getByRole("button", { name: "发送" }));
    await waitFor(() => expect(chatStream).toHaveBeenCalledTimes(1));
    expect(await screen.findByRole("button", { name: "发送" })).toHaveTextContent("生成中…");

    await user.click(screen.getByRole("button", { name: /项目/ }));
    await user.click(await screen.findByRole("button", { name: /Second Project/ }));

    expect(streamSignal?.aborted).toBe(true);
    await waitFor(() => expect(screen.getByRole("button", { name: "发送" })).toBeEnabled());
    expect(screen.getByRole("button", { name: "发送" })).toHaveTextContent("发送");
  });

  it("restores history deletion controls after switching projects during deletion", async () => {
    localStorage.setItem("agentforge.onboardingComplete", "true");
    const firstSummary = { conversationId: "delete-first", preview: "First project history", messageCount: 2,
      createdAt: "2026-10-04T00:00:00Z", updatedAt: "2026-10-04T00:01:00Z" };
    const secondSummary = { conversationId: "delete-second", preview: "Second project history", messageCount: 2,
      createdAt: "2026-10-04T00:00:00Z", updatedAt: "2026-10-04T00:01:00Z" };
    let resolveDelete!: () => void;
    const mockApi = api({
      listProjects: vi.fn().mockResolvedValue([project, secondProject]),
      listConversations: vi.fn().mockImplementation(async (selectedProjectId) =>
        selectedProjectId === secondProject.id ? [secondSummary] : [firstSummary]),
      deleteConversation: vi.fn().mockReturnValue(new Promise<void>((resolve) => { resolveDelete = resolve; })),
    });
    const user = await login(mockApi);
    await user.click(screen.getByRole("button", { name: /历史/ }));
    await user.click(await screen.findByRole("button", { name: "删除会话 First project history" }));
    await user.click(within(screen.getByRole("dialog", { name: "删除聊天记录" }))
      .getByRole("button", { name: "确认删除" }));
    await waitFor(() => expect(mockApi.deleteConversation).toHaveBeenCalledTimes(1));

    await user.click(screen.getByRole("button", { name: /项目/ }));
    await user.click(await screen.findByRole("button", { name: /Second Project/ }));
    await user.click(screen.getByRole("button", { name: /历史/ }));
    const secondDelete = await screen.findByRole("button", { name: "删除会话 Second project history" });
    expect(secondDelete).toBeEnabled();

    await act(async () => resolveDelete());
  });
});
