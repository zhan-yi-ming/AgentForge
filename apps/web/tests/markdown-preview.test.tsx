import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { MarkdownPreview } from "../src/MarkdownPreview";

describe("MarkdownPreview", () => {
  it("renders markdown without turning raw HTML into executable DOM", () => {
    const { container } = render(
      <MarkdownPreview content={'# Preview\n\n<script data-testid="unsafe">alert(1)</script>\n\n- safe'} />,
    );
    expect(screen.getByRole("heading", { name: "Preview" })).toBeInTheDocument();
    expect(screen.getByText("safe")).toBeInTheDocument();
    expect(container.querySelector("script")).toBeNull();
    expect(screen.queryByTestId("unsafe")).not.toBeInTheDocument();
  });
});
