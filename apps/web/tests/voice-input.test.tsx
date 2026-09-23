import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import VoiceInput from "../src/pages/VoiceInput";
import type { ApiClient } from "../src/api";

describe("chat voice input", () => {
  it("puts final ASR text into the editable draft only after stopping", async () => {
    const track = { stop: vi.fn() };
    Object.defineProperty(navigator, "mediaDevices", { configurable: true, value: {
      getUserMedia: vi.fn().mockResolvedValue({ getTracks: () => [track] }),
    } });
    const processor = { connect: vi.fn(), disconnect: vi.fn(), onaudioprocess: null };
    const source = { connect: vi.fn(), disconnect: vi.fn() };
    vi.stubGlobal("AudioContext", class {
      sampleRate = 48000;
      destination = {};
      createMediaStreamSource() { return source; }
      createScriptProcessor() { return processor; }
      close() { return Promise.resolve(); }
    });
    const api = {
      startVoice: vi.fn().mockResolvedValue({ sessionId: "session-1" }),
      appendVoiceAudio: vi.fn().mockResolvedValue(undefined),
      getVoice: vi.fn().mockResolvedValue({ sessionId: "session-1", text: "语音草稿", finished: false }),
      finishVoice: vi.fn().mockResolvedValue({ sessionId: "session-1", text: "最终语音草稿", finished: true }),
      cancelVoice: vi.fn().mockResolvedValue(undefined),
    } as unknown as ApiClient;
    const onTranscript = vi.fn();
    const user = userEvent.setup();
    render(<VoiceInput api={api} projectId="project-1" onTranscript={onTranscript} />);
    await user.click(screen.getByRole("button", { name: /语音输入/ }));
    await waitFor(() => expect(screen.getByRole("button", { name: "停止录音" })).toBeInTheDocument());
    expect(onTranscript).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "停止录音" }));
    await waitFor(() => expect(onTranscript).toHaveBeenCalledWith("最终语音草稿"));
    expect(api.finishVoice).toHaveBeenCalledWith("project-1", "session-1");
    expect(api.cancelVoice).not.toHaveBeenCalled();
    expect(track.stop).toHaveBeenCalled();
  });
  it("explains an empty final transcript without erasing the draft", async () => {
    const track = { stop: vi.fn() };
    Object.defineProperty(navigator, "mediaDevices", { configurable: true, value: {
      getUserMedia: vi.fn().mockResolvedValue({ getTracks: () => [track] }),
    } });
    const processor = { connect: vi.fn(), disconnect: vi.fn(), onaudioprocess: null };
    const source = { connect: vi.fn(), disconnect: vi.fn() };
    vi.stubGlobal("AudioContext", class {
      sampleRate = 48000;
      destination = {};
      createMediaStreamSource() { return source; }
      createScriptProcessor() { return processor; }
      close() { return Promise.resolve(); }
    });
    const api = {
      startVoice: vi.fn().mockResolvedValue({ sessionId: "session-1" }),
      appendVoiceAudio: vi.fn().mockResolvedValue(undefined),
      getVoice: vi.fn().mockResolvedValue({ sessionId: "session-1", text: "", finished: false }),
      finishVoice: vi.fn().mockResolvedValue({ sessionId: "session-1", text: "", finished: true }),
      cancelVoice: vi.fn().mockResolvedValue(undefined),
    } as unknown as ApiClient;
    const onTranscript = vi.fn();
    render(<VoiceInput api={api} projectId="project-1" onTranscript={onTranscript} />);
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: /语音输入/ }));
    await waitFor(() => expect(screen.getByRole("button", { name: "停止录音" })).toBeInTheDocument());
    await user.click(screen.getByRole("button", { name: "停止录音" }));
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("未识别到语音"));
    expect(onTranscript).not.toHaveBeenCalled();
    expect(api.cancelVoice).not.toHaveBeenCalled();
  });

  it("resets the recorder when the selected project changes", async () => {
    const track = { stop: vi.fn() };
    Object.defineProperty(navigator, "mediaDevices", { configurable: true, value: {
      getUserMedia: vi.fn().mockResolvedValue({ getTracks: () => [track] }),
    } });
    const processor = { connect: vi.fn(), disconnect: vi.fn(), onaudioprocess: null };
    const source = { connect: vi.fn(), disconnect: vi.fn() };
    vi.stubGlobal("AudioContext", class {
      sampleRate = 48000;
      destination = {};
      createMediaStreamSource() { return source; }
      createScriptProcessor() { return processor; }
      close() { return Promise.resolve(); }
    });
    const api = {
      startVoice: vi.fn().mockResolvedValue({ sessionId: "session-1" }),
      cancelVoice: vi.fn().mockResolvedValue(undefined),
    } as unknown as ApiClient;
    const view = render(<VoiceInput api={api} projectId="project-1" onTranscript={vi.fn()} />);
    await userEvent.setup().click(screen.getByRole("button", { name: /语音输入/ }));
    await waitFor(() => expect(screen.getByRole("button", { name: "停止录音" })).toBeInTheDocument());
    view.rerender(<VoiceInput api={api} projectId="project-2" onTranscript={vi.fn()} />);
    await waitFor(() => expect(screen.getByRole("button", { name: /语音输入/ })).toBeEnabled());
    expect(track.stop).toHaveBeenCalled();
    expect(api.cancelVoice).toHaveBeenCalledWith("project-1", "session-1");
  });

  it("keeps final transcript when an in-flight preview request fails during stop", async () => {
    const track = { stop: vi.fn() };
    Object.defineProperty(navigator, "mediaDevices", { configurable: true, value: {
      getUserMedia: vi.fn().mockResolvedValue({ getTracks: () => [track] }),
    } });
    const processor = { connect: vi.fn(), disconnect: vi.fn(), onaudioprocess: null };
    const source = { connect: vi.fn(), disconnect: vi.fn() };
    vi.stubGlobal("AudioContext", class {
      sampleRate = 48000;
      destination = {};
      createMediaStreamSource() { return source; }
      createScriptProcessor() { return processor; }
      close() { return Promise.resolve(); }
    });
    let rejectPreview!: (error: Error) => void;
    let finish!: (value: { sessionId: string; text: string; finished: boolean }) => void;
    const api = {
      startVoice: vi.fn().mockResolvedValue({ sessionId: "session-1" }),
      appendVoiceAudio: vi.fn().mockResolvedValue(undefined),
      getVoice: vi.fn().mockReturnValue(new Promise((_, reject) => { rejectPreview = reject; })),
      finishVoice: vi.fn().mockReturnValue(new Promise((resolve) => { finish = resolve; })),
      cancelVoice: vi.fn().mockResolvedValue(undefined),
    } as unknown as ApiClient;
    const onTranscript = vi.fn();
    render(<VoiceInput api={api} projectId="project-1" onTranscript={onTranscript} />);
    await userEvent.setup().click(screen.getByRole("button", { name: /语音输入/ }));
    await waitFor(() => expect(api.getVoice).toHaveBeenCalled(), { timeout: 2000 });
    await userEvent.setup().click(screen.getByRole("button", { name: "停止录音" }));
    await waitFor(() => expect(api.finishVoice).toHaveBeenCalled());
    rejectPreview(new Error("session ended"));
    await new Promise((resolve) => window.setTimeout(resolve, 20));
    finish({ sessionId: "session-1", text: "最终文字", finished: true });
    await waitFor(() => expect(onTranscript).toHaveBeenCalledWith("最终文字"));
  });

  it("stops microphone permission granted after leaving chat", async () => {
    const track = { stop: vi.fn() };
    let grant!: (stream: MediaStream) => void;
    Object.defineProperty(navigator, "mediaDevices", { configurable: true, value: {
      getUserMedia: vi.fn().mockReturnValue(new Promise<MediaStream>((resolve) => { grant = resolve; })),
    } });
    const api = { startVoice: vi.fn(), cancelVoice: vi.fn().mockResolvedValue(undefined) } as unknown as ApiClient;
    const user = userEvent.setup();
    const view = render(<VoiceInput api={api} projectId="project-1" onTranscript={vi.fn()} />);
    await user.click(screen.getByRole("button", { name: /语音输入/ }));
    view.unmount();
    grant({ getTracks: () => [track] } as unknown as MediaStream);
    await waitFor(() => expect(track.stop).toHaveBeenCalled());
    expect(api.startVoice).not.toHaveBeenCalled();
  });

});
