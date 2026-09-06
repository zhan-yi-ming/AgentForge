import { describe, expect, it, vi } from "vitest";
import { createApiClient } from "../src/api";

describe("API client", () => {
  it("adds the bearer token and parses JSON", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify([]), {
      status: 200, headers: { "Content-Type": "application/json", "X-Request-Id": "request-1" },
    }));
    vi.stubGlobal("fetch", fetchMock);
    await createApiClient(() => "token-123").listProjects();
    expect(fetchMock).toHaveBeenCalledWith("/api/v1/projects", expect.any(Object));
    const headers = fetchMock.mock.calls[0][1].headers as Headers;
    expect(headers.get("Authorization")).toBe("Bearer token-123");
  });

  it("exposes safe Problem Details and request id", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({
      title: "Conflict", status: 409, detail: "The Wiki version is stale.", requestId: "request-409",
    }), { status: 409, headers: { "Content-Type": "application/problem+json" } })));
    await expect(createApiClient(() => "token-123").listProjects()).rejects.toMatchObject({
      status: 409, detail: "The Wiki version is stale.", requestId: "request-409",
    });
  });

  it("parses fragmented UTF-8 SSE frames and reports deltas immediately", async () => {
    const encoder = new TextEncoder();
    const payload = [
      'event: metadata\ndata: {"conversationId":"conversation-1","requestId":"r1","sources":[]}\n\n',
      'event: delta\ndata: {"text":"你"}\n\n',
      'event: delta\ndata: {"text":"好"}\n\n',
      'event: complete\ndata: {"pendingAction":null}\n\n',
    ].join("");
    const bytes = encoder.encode(payload);
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(bytes.slice(0, 101));
        controller.enqueue(bytes.slice(101, 127));
        controller.enqueue(bytes.slice(127));
        controller.close();
      },
    });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(body, {
      status: 200, headers: { "Content-Type": "text/event-stream" },
    })));
    const deltas: string[] = [];

    const result = await createApiClient(() => "token-123").chatStream(
      "project-1", "hello", undefined, { onDelta: (text) => deltas.push(text) },
    );

    expect(deltas).toEqual(["你", "好"]);
    expect(result).toMatchObject({ conversationId: "conversation-1", answer: "你好", requestId: "r1" });
  });
});
