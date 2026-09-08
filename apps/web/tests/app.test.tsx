import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
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
    chat: vi.fn(), chatStream: vi.fn(), confirmAction: vi.fn(), rejectAction: vi.fn(),
    ...overrides,
  };
}

async function login(mockApi: ApiClient) {
  const user = userEvent.setup();
  render(<App api={mockApi} />);
  await user.type(screen.getByLabelText("邮箱"), "owner@example.com");
  await user.type(screen.getByLabelText("密码"), "password-123");
  await user.click(screen.getByRole("button", { name: "登录" }));
  await screen.findByRole("button", { name: /AgentForge/ });
  return user;
}

describe("App", () => {
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
    const guide = await screen.findByRole("dialog", { name: "新手引导" });
    expect(within(guide).getByText(/中央对话/)).toBeInTheDocument();

    await user.click(within(guide).getByRole("button", { name: "开始体验" }));
    expect(screen.queryByRole("dialog", { name: "新手引导" })).not.toBeInTheDocument();
    expect(localStorage.getItem("agentforge.onboardingComplete")).toBe("true");

    await user.click(screen.getByRole("button", { name: "新手引导" }));
    expect(await screen.findByRole("dialog", { name: "新手引导" })).toBeInTheDocument();
  });

  it("keeps onboarding usable when browser storage is unavailable", async () => {
    vi.stubGlobal("localStorage", {
      getItem: vi.fn(() => { throw new DOMException("Storage disabled", "SecurityError"); }),
      setItem: vi.fn(() => { throw new DOMException("Storage disabled", "SecurityError"); }),
      clear: vi.fn(), removeItem: vi.fn(), key: vi.fn(), length: 0,
    });

    const user = await login(api());
    const guide = await screen.findByRole("dialog", { name: "新手引导" });
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
    await login(mockApi);
    expect(mockApi.listProjects).toHaveBeenCalled();
    expect(mockApi.listWikiPages).toHaveBeenCalledWith(project.id);
    expect(mockApi.listTasks).toHaveBeenCalledWith(project.id);
    expect(await screen.findByText("Ship UI")).toBeInTheDocument();
  });

  it("explains which actions appear in the execution task list", async () => {
    await login(api());

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
    expect(screen.queryByText("First answer")).not.toBeInTheDocument();

    const olderToggle = screen.getByRole("button", { name: /First question/ });
    expect(olderToggle).toHaveAttribute("aria-expanded", "false");
    await user.click(olderToggle);
    expect(screen.getByText("First answer")).toBeInTheDocument();
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
    await waitFor(() => expect(mockApi.confirmAction).toHaveBeenCalledWith(project.id, pending.id));
    expect(mockApi.listTasks).toHaveBeenCalledTimes(2);
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

    await waitFor(() => expect(mockApi.rejectAction).toHaveBeenCalledWith(project.id, pending.id));
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
    await user.type(screen.getByLabelText("Wiki 标题"), "Architecture");
    await user.type(screen.getByLabelText("Wiki Markdown 草稿"), "# Core");
    expect(mockApi.createWikiPage).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "保存 Wiki" }));

    await waitFor(() => expect(mockApi.createWikiPage).toHaveBeenCalledWith(project.id, "Architecture", "# Core"));
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
    const editor = screen.getByLabelText("Wiki Markdown 草稿");
    await user.type(editor, "Original draft");
    await user.type(screen.getByLabelText("待整理原文"), "messy notes");
    await user.click(screen.getByRole("button", { name: "AI 整理并预览" }));
    expect(await screen.findByRole("heading", { name: "Structured" })).toBeInTheDocument();
    expect(formatStream).toHaveBeenCalledWith(
      project.id,
      expect.stringContaining("messy notes"),
      undefined,
      expect.any(Object),
      expect.any(AbortSignal),
    );
    expect(editor).toHaveValue("Original draft");
    await user.click(screen.getByRole("button", { name: "应用到 Wiki 草稿" }));
    expect(editor).toHaveValue("# Structured\n\nKeep this.");
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

    await user.type(screen.getByLabelText("待整理原文"), "stream these notes");
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
    await waitFor(() => expect(screen.getByLabelText("Wiki 标题")).toHaveValue("Existing page"));

    await user.type(screen.getByLabelText("待整理原文"), "generate a new page");
    await user.click(screen.getByRole("button", { name: "AI 整理并预览" }));
    expect(mockApi.chatStream).toHaveBeenCalledWith(
      project.id,
      expect.stringContaining("一级标题"),
      undefined,
      expect.any(Object),
      expect.any(AbortSignal),
    );
    await user.click(await screen.findByRole("button", { name: "应用到 Wiki 草稿" }));

    expect(screen.getByLabelText("Wiki 标题")).toHaveValue("Generated title");
    expect(screen.getByLabelText("Wiki Markdown 草稿")).toHaveValue(answer);
    expect(screen.getByRole("button", { name: "应用到 Wiki 草稿" })).toBeDisabled();
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

    await user.type(screen.getByLabelText("待整理原文"), "notes with code");
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
    await user.type(screen.getByLabelText("待整理原文"), "code notes");
    await user.click(screen.getByRole("button", { name: "AI 整理并预览" }));

    expect(await screen.findByRole("heading", { name: "Notes" })).toBeInTheDocument();
    expect(screen.getByText("const answer = 42;").closest("pre")).toBeInTheDocument();
  });

  it("keeps formatting isolated from chat and ignores any proposed action", async () => {
    const pending: AgentAction = {
      id: "action-format", projectId: project.id, conversationId: "format-conversation",
      actionType: "CREATE_TASK", status: "PENDING", title: "Unexpected proposal",
      createdAt: "2026-09-05T00:00:00Z",
    };
    const streamMock = vi.fn()
      .mockResolvedValueOnce({ conversationId: "project-conversation", answer: "Chat answer", requestId: "r4", sources: [] })
      .mockResolvedValueOnce({
        conversationId: "format-conversation", answer: "# Formatted", requestId: "r5", sources: [], pendingAction: pending,
      });
    const mockApi = api({ chatStream: streamMock });
    const user = await login(mockApi);
    await user.type(screen.getByLabelText("给 Agent 的消息"), "project question");
    await user.click(screen.getByRole("button", { name: "发送" }));
    await user.type(screen.getByLabelText("待整理原文"), "format me");
    await user.click(screen.getByRole("button", { name: "AI 整理并预览" }));

    await waitFor(() => expect(streamMock).toHaveBeenCalledTimes(2));
    expect(streamMock.mock.calls[1]?.[2]).toBeUndefined();
    expect(await screen.findByRole("heading", { name: "Formatted" })).toBeInTheDocument();
    expect(screen.queryByText("Unexpected proposal")).not.toBeInTheDocument();
    expect(screen.getByText("会话 project-")).toBeInTheDocument();
  });

  it("prevents AI formatting while project chat is streaming", async () => {
    const streamMock = vi.fn().mockReturnValue(new Promise(() => {}));
    const mockApi = api({ chatStream: streamMock });
    const user = await login(mockApi);
    await user.type(screen.getByLabelText("待整理原文"), "format me");
    await user.type(screen.getByLabelText("给 Agent 的消息"), "project question");
    await user.click(screen.getByRole("button", { name: "发送" }));

    expect(screen.getByRole("button", { name: "AI 整理并预览" })).toBeDisabled();
  });

  it("prevents applying partial formatting or starting chat while formatting streams", async () => {
    const streamMock = vi.fn().mockImplementation(async (_projectId, _message, _conversationId, callbacks) => {
      callbacks.onDelta("# Partial");
      return new Promise(() => {});
    });
    const user = await login(api({ chatStream: streamMock }));
    await user.type(screen.getByLabelText("给 Agent 的消息"), "project question");
    await user.type(screen.getByLabelText("待整理原文"), "format me");
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
    await user.type(screen.getByLabelText("待整理原文"), "format me");
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
    await user.type(screen.getByLabelText("待整理原文"), "project one notes");
    await user.click(screen.getByRole("button", { name: "AI 整理并预览" }));
    expect(await screen.findByText("# Project one")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /Second Project/ }));
    expect(formatSignal?.aborted).toBe(true);
    expect(screen.getByLabelText("待整理原文")).toHaveValue("");
    expect(screen.queryByText("# Project one")).not.toBeInTheDocument();
  });
});
