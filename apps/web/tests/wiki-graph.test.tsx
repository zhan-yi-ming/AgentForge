import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, it, vi } from "vitest";
import WikiGraphPage from "../src/pages/WikiGraphPage";
import { parseRoute } from "../src/route";

it("opens the Wiki link graph at its own route", () => {
  expect(parseRoute("/wiki/graph")).toEqual({ page: "wiki-graph" });
});

const page = (id: string, title: string, content: string, projectId = "project-1") => ({
  id, title, content, projectId, version: 1, createdAt: "2026-09-20T00:00:00Z", updatedAt: "2026-09-20T00:00:00Z",
});

it("shows only sourced links in the current project and opens a selected Wiki page", async () => {
  const open = vi.fn();
  render(<WikiGraphPage projectId="project-1" pages={[
    page("one", "首页", "参见 [[架构|系统架构]]、[[缺失]] 和 [决策](wiki:two)。"),
    page("two", "架构", ""),
    page("other", "外部", "[[首页]]", "project-2"),
  ]} onBack={vi.fn()} onOpenPage={open} />);
  expect(screen.getByText("2 个页面")).toBeInTheDocument();
  expect(screen.getByText("1 条连接")).toBeInTheDocument();
  expect(screen.queryByText("缺失")).not.toBeInTheDocument();
  expect(screen.queryByText("外部")).not.toBeInTheDocument();
  const user = userEvent.setup();
  await user.click(screen.getByRole("button", { name: "查看 架构" }));
  expect(screen.getByText(/系统架构/)).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: /打开 Wiki 页面/ }));
  expect(open).toHaveBeenCalledWith(expect.objectContaining({ id: "two" }));
});
