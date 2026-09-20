import { act, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ActionApprovalDialog } from "../src/pages/ActionApprovalDialog";
import type { AgentAction } from "../src/api";

const createdAt = "2026-09-20T00:00:00Z";
function action(actionType: AgentAction["actionType"]): AgentAction {
  return { id: "action-1", projectId: "project-1", conversationId: "conversation-1",
    actionType, status: "PENDING", title: "Review", createdAt };
}

afterEach(() => vi.useRealTimers());

describe("approval timeout", () => {
  it("requests automatic approval once after 60 visible seconds for a low-risk create", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(createdAt));
    const auto = vi.fn();
    render(<ActionApprovalDialog action={action("CREATE_TASK")} busy={false}
      onClose={vi.fn()} onDecision={vi.fn()} onAutoDecision={auto} />);
    expect(screen.getByRole("timer")).toHaveTextContent("60 秒");
    act(() => vi.advanceTimersByTime(59_000));
    expect(auto).not.toHaveBeenCalled();
    act(() => vi.advanceTimersByTime(1_000));
    expect(auto).toHaveBeenCalledTimes(1);
    act(() => vi.advanceTimersByTime(10_000));
    expect(auto).toHaveBeenCalledTimes(1);
  });

  it("never automatically approves a medium-risk update", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(createdAt));
    const auto = vi.fn();
    render(<ActionApprovalDialog action={action("UPDATE_TASK")} busy={false}
      onClose={vi.fn()} onDecision={vi.fn()} onAutoDecision={auto} />);
    act(() => vi.advanceTimersByTime(70_000));
    expect(auto).not.toHaveBeenCalled();
    expect(screen.getByText(/必须手动确认/)).toBeInTheDocument();
  });

  it("does not auto-confirm after the dialog closes", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(createdAt));
    const auto = vi.fn();
    const view = render(<ActionApprovalDialog action={action("CREATE_TASK")} busy={false}
      onClose={vi.fn()} onDecision={vi.fn()} onAutoDecision={auto} />);
    view.unmount();
    act(() => vi.advanceTimersByTime(70_000));
    expect(auto).not.toHaveBeenCalled();
  });
});
