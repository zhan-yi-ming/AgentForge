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
export type AgentStreamCallbacks = {
  onMetadata?: (metadata: Pick<AgentChat, "conversationId" | "requestId" | "sources">) => void;
  onDelta?: (text: string) => void;
};

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
  chatStream(projectId: string, message: string, conversationId: string | undefined, callbacks: AgentStreamCallbacks, signal?: AbortSignal): Promise<AgentChat>;
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

  async function chatStream(
    projectId: string,
    message: string,
    conversationId: string | undefined,
    callbacks: AgentStreamCallbacks,
    signal?: AbortSignal,
  ): Promise<AgentChat> {
    const headers = new Headers({ Accept: "text/event-stream", "Content-Type": "application/json" });
    const token = getToken();
    if (token) headers.set("Authorization", `Bearer ${token}`);
    const response = await fetch(`/api/v1/projects/${projectId}/agent/chat/stream`, {
      method: "POST", headers, body: JSON.stringify({ message, conversationId }), signal,
    });
    if (!response.ok) {
      let problem: Record<string, unknown> = {};
      if ((response.headers.get("Content-Type") ?? "").includes("json")) {
        problem = await response.json() as Record<string, unknown>;
      }
      throw new ApiProblem(
        response.status,
        String(problem.detail ?? "The request could not be completed."),
        String(problem.requestId ?? response.headers.get("X-Request-Id") ?? "") || undefined,
        String(problem.title ?? response.statusText ?? "Request failed"),
      );
    }
    if (!response.body) throw new ApiProblem(503, "AI response stream is unavailable.");

    let metadata: Pick<AgentChat, "conversationId" | "requestId" | "sources"> | undefined;
    let answer = "";
    let pendingAction: AgentAction | undefined;
    let completed = false;
    let buffer = "";
    const decoder = new TextDecoder();
    const reader = response.body.getReader();

    const processFrame = (frame: string) => {
      let eventName = "message";
      const dataLines: string[] = [];
      for (const line of frame.split("\n")) {
        if (line.startsWith("event:")) eventName = line.slice(6).trim();
        if (line.startsWith("data:")) dataLines.push(line.slice(5).trimStart());
      }
      if (!dataLines.length) return;
      const data = JSON.parse(dataLines.join("\n")) as Record<string, unknown>;
      if (eventName === "metadata") {
        metadata = {
          conversationId: String(data.conversationId),
          requestId: String(data.requestId),
          sources: (data.sources ?? []) as AgentSource[],
        };
        callbacks.onMetadata?.(metadata);
      } else if (eventName === "delta") {
        const text = String(data.text ?? "");
        answer += text;
        if (text) callbacks.onDelta?.(text);
      } else if (eventName === "complete") {
        pendingAction = (data.pendingAction ?? undefined) as AgentAction | undefined;
        completed = true;
      } else if (eventName === "error") {
        throw new ApiProblem(503, String(data.message ?? "AI service is temporarily unavailable."));
      }
    };

    while (true) {
      const { value, done } = await reader.read();
      buffer += decoder.decode(value, { stream: !done }).replace(/\r\n/g, "\n");
      let boundary = buffer.indexOf("\n\n");
      while (boundary >= 0) {
        processFrame(buffer.slice(0, boundary));
        buffer = buffer.slice(boundary + 2);
        boundary = buffer.indexOf("\n\n");
      }
      if (done) break;
    }
    if (buffer.trim()) processFrame(buffer);
    if (!metadata || !completed) throw new ApiProblem(503, "AI response stream ended unexpectedly.");
    return { ...metadata, answer, pendingAction };
  }

  return {
    login: (email, password) => request("/api/v1/auth/login", { method: "POST", body: JSON.stringify({ email, password }) }),
    listProjects: () => request("/api/v1/projects"),
    listWikiPages: (projectId) => request(`/api/v1/projects/${projectId}/wiki-pages`),
    createWikiPage: (projectId, title, content) => request(`/api/v1/projects/${projectId}/wiki-pages`, { method: "POST", body: JSON.stringify({ title, content }) }),
    updateWikiPage: (projectId, pageId, title, content, version) => request(`/api/v1/projects/${projectId}/wiki-pages/${pageId}`, { method: "PUT", body: JSON.stringify({ title, content, version }) }),
    listTasks: (projectId) => request(`/api/v1/projects/${projectId}/tasks`),
    chat: (projectId, message, conversationId) => request(`/api/v1/projects/${projectId}/agent/chat`, { method: "POST", body: JSON.stringify({ message, conversationId }) }),
    chatStream,
    confirmAction: (projectId, actionId) => request(`/api/v1/projects/${projectId}/agent/actions/${actionId}/confirm`, { method: "POST" }),
    rejectAction: (projectId, actionId) => request(`/api/v1/projects/${projectId}/agent/actions/${actionId}/reject`, { method: "POST" }),
  };
}
