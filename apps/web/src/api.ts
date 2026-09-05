export type User = { id: string; email: string; displayName: string; role: string };
export type AuthResponse = { accessToken: string; tokenType: string; expiresIn: number; user: User };
export type Project = { id: string; ownerId: string; name: string; description?: string; createdAt: string; updatedAt: string };
export type WikiPage = { id: string; projectId: string; title: string; content: string; version: number; createdAt: string; updatedAt: string };
export type Task = { id: string; projectId: string; title: string; description?: string; status: "TODO" | "IN_PROGRESS" | "DONE"; priority: "LOW" | "MEDIUM" | "HIGH"; version: number; createdAt: string; updatedAt: string };
export type AgentSource = { sourceType: string; sourceId: string; title: string; excerpt: string };
export type AgentAction = {
  id: string; projectId: string; conversationId: string; actionType: "CREATE_TASK" | "UPDATE_TASK";
  status: "PENDING" | "EXECUTED" | "REJECTED"; taskId?: string; expectedVersion?: number;
  title?: string; description?: string; taskStatus?: string; priority?: string;
  resultTask?: Task; createdAt: string; decidedAt?: string;
};
export type AgentChat = { conversationId: string; answer: string; requestId: string; sources: AgentSource[]; pendingAction?: AgentAction };

export class ApiProblem extends Error {
  constructor(
    public readonly status: number,
    public readonly detail: string,
    public readonly requestId?: string,
    public readonly title = "Request failed",
  ) {
    super(detail);
    this.name = "ApiProblem";
  }
}

export interface ApiClient {
  login(email: string, password: string): Promise<AuthResponse>;
  listProjects(): Promise<Project[]>;
  listWikiPages(projectId: string): Promise<WikiPage[]>;
  createWikiPage(projectId: string, title: string, content: string): Promise<WikiPage>;
  updateWikiPage(projectId: string, pageId: string, title: string, content: string, version: number): Promise<WikiPage>;
  listTasks(projectId: string): Promise<Task[]>;
  chat(projectId: string, message: string, conversationId?: string): Promise<AgentChat>;
  confirmAction(projectId: string, actionId: string): Promise<AgentAction>;
  rejectAction(projectId: string, actionId: string): Promise<AgentAction>;
}

type Json = Record<string, unknown> | unknown[];

export function createApiClient(getToken: () => string | null): ApiClient {
  async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const headers = new Headers(init.headers);
    headers.set("Accept", "application/json");
    const token = getToken();
    if (token) headers.set("Authorization", `Bearer ${token}`);
    if (init.body) headers.set("Content-Type", "application/json");
    const response = await fetch(path, { ...init, headers });
    const contentType = response.headers.get("Content-Type") ?? "";
    const payload = contentType.includes("json") ? await response.json() as Json : undefined;
    if (!response.ok) {
      const problem = (payload ?? {}) as Record<string, unknown>;
      throw new ApiProblem(
        response.status,
        String(problem.detail ?? "The request could not be completed."),
        String(problem.requestId ?? response.headers.get("X-Request-Id") ?? "") || undefined,
        String(problem.title ?? response.statusText ?? "Request failed"),
      );
    }
    return payload as T;
  }

  return {
    login: (email, password) => request("/api/v1/auth/login", { method: "POST", body: JSON.stringify({ email, password }) }),
    listProjects: () => request("/api/v1/projects"),
    listWikiPages: (projectId) => request(`/api/v1/projects/${projectId}/wiki-pages`),
    createWikiPage: (projectId, title, content) => request(`/api/v1/projects/${projectId}/wiki-pages`, { method: "POST", body: JSON.stringify({ title, content }) }),
    updateWikiPage: (projectId, pageId, title, content, version) => request(`/api/v1/projects/${projectId}/wiki-pages/${pageId}`, { method: "PUT", body: JSON.stringify({ title, content, version }) }),
    listTasks: (projectId) => request(`/api/v1/projects/${projectId}/tasks`),
    chat: (projectId, message, conversationId) => request(`/api/v1/projects/${projectId}/agent/chat`, { method: "POST", body: JSON.stringify({ message, conversationId }) }),
    confirmAction: (projectId, actionId) => request(`/api/v1/projects/${projectId}/agent/actions/${actionId}/confirm`, { method: "POST" }),
    rejectAction: (projectId, actionId) => request(`/api/v1/projects/${projectId}/agent/actions/${actionId}/reject`, { method: "POST" }),
  };
}
