import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { App } from "../src/App";
import type { ApiClient, AgentAction, AgentChat, Project, Task, WikiPage } from "../src/api";

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
    chat: vi.fn(), confirmAction: vi.fn(), rejectAction: vi.fn(),
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
  it("logs in and loads the selected project resources", async () => {
    const mockApi = api();
    await login(mockApi);
    expect(mockApi.listProjects).toHaveBeenCalled();
    expect(mockApi.listWikiPages).toHaveBeenCalledWith(project.id);
    expect(mockApi.listTasks).toHaveBeenCalledWith(project.id);
    expect(await screen.findByText("Ship UI")).toBeInTheDocument();
  });

  it("confirms a pending action through Java before refreshing tasks", async () => {
    const pending: AgentAction = {
      id: "action-1", projectId: project.id, conversationId: "conversation-1", actionType: "CREATE_TASK",
      status: "PENDING", title: "Review auth", priority: "HIGH", createdAt: "2026-09-05T00:00:00Z",
    };
    const chat: AgentChat = { conversationId: "conversation-1", answer: "Please confirm", requestId: "r1", sources: [], pendingAction: pending };
    const mockApi = api({ chat: vi.fn().mockResolvedValue(chat),
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
      chat: vi.fn().mockResolvedValue({ conversationId: "conversation-1", answer: "Review", requestId: "r3", sources: [], pendingAction: pending }),
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

  it("keeps the wiki draft until AI text is explicitly applied", async () => {
    const mockApi = api({ chat: vi.fn().mockResolvedValue({
      conversationId: "conversation-2", answer: "# Structured\n\nKeep this.", requestId: "r2", sources: [],
    }) });
    const user = await login(mockApi);
    const editor = screen.getByLabelText("Wiki Markdown 草稿");
    await user.type(editor, "Original draft");
    await user.type(screen.getByLabelText("待整理原文"), "messy notes");
    await user.click(screen.getByRole("button", { name: "AI 整理并预览" }));
    expect(await screen.findByRole("heading", { name: "Structured" })).toBeInTheDocument();
    expect(editor).toHaveValue("Original draft");
    await user.click(screen.getByRole("button", { name: "应用到 Wiki 草稿" }));
    expect(editor).toHaveValue("# Structured\n\nKeep this.");
  });

  it("keeps formatting isolated from chat and exposes any proposed action", async () => {
    const pending: AgentAction = {
      id: "action-format", projectId: project.id, conversationId: "format-conversation",
      actionType: "CREATE_TASK", status: "PENDING", title: "Unexpected proposal",
      createdAt: "2026-09-05T00:00:00Z",
    };
    const chatMock = vi.fn()
      .mockResolvedValueOnce({ conversationId: "project-conversation", answer: "Chat answer", requestId: "r4", sources: [] })
      .mockResolvedValueOnce({ conversationId: "format-conversation", answer: "# Formatted", requestId: "r5", sources: [], pendingAction: pending });
    const mockApi = api({ chat: chatMock });
    const user = await login(mockApi);
    await user.type(screen.getByLabelText("给 Agent 的消息"), "project question");
    await user.click(screen.getByRole("button", { name: "发送" }));
    await user.type(screen.getByLabelText("待整理原文"), "format me");
    await user.click(screen.getByRole("button", { name: "AI 整理并预览" }));

    await waitFor(() => expect(chatMock).toHaveBeenCalledTimes(2));
    expect(chatMock.mock.calls[1]?.[2]).toBeUndefined();
    expect(await screen.findByText("Unexpected proposal")).toBeInTheDocument();
    expect(screen.getByText("会话 project-")).toBeInTheDocument();
  });

  it("clears formatting drafts when the selected project changes", async () => {
    const mockApi = api({
      listProjects: vi.fn().mockResolvedValue([project, secondProject]),
      chat: vi.fn().mockResolvedValue({ conversationId: "format-only", answer: "# Project one", requestId: "r6", sources: [] }),
    });
    const user = await login(mockApi);
    await user.type(screen.getByLabelText("待整理原文"), "project one notes");
    await user.click(screen.getByRole("button", { name: "AI 整理并预览" }));
    expect(await screen.findByRole("heading", { name: "Project one" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /Second Project/ }));
    expect(screen.getByLabelText("待整理原文")).toHaveValue("");
    expect(screen.queryByRole("heading", { name: "Project one" })).not.toBeInTheDocument();
  });
});
