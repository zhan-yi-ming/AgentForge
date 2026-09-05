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
});
