import { useEffect, useRef } from "react";
import { createPortal } from "react-dom";
import type { AgentAction } from "../api";

type Props = {
  action: AgentAction;
  busy: boolean;
  onClose: () => void;
  onDecision: (decision: "confirm" | "reject") => void;
};

export function ActionApprovalDialog({ action, busy, onClose, onDecision }: Props) {
  const dialogRef = useRef<HTMLElement>(null);
  const laterRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    laterRef.current?.focus();
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        onClose();
      } else if (event.key === "Tab") {
        const buttons = Array.from(dialogRef.current?.querySelectorAll<HTMLButtonElement>("button:not(:disabled)") ?? []);
        if (buttons.length === 0) return;
        const first = buttons[0];
        const last = buttons[buttons.length - 1];
        if (event.shiftKey && document.activeElement === first) {
          event.preventDefault();
          last.focus();
        } else if (!event.shiftKey && document.activeElement === last) {
          event.preventDefault();
          first.focus();
        }
      }
    };
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [onClose]);

  return createPortal(<div className="approval-backdrop">
    <section className="approval-dialog" role="dialog" aria-modal="true" aria-labelledby="approval-dialog-title" ref={dialogRef}>
      <span className="eyebrow">ACTION REVIEW</span>
      <h2 id="approval-dialog-title">待确认操作</h2>
      <p className="approval-intro">请核对以下提案。只有点击“确认执行”后，Java 才会重新校验并尝试写入。</p>
      <dl className="approval-fields">
        <div><dt>操作</dt><dd>{action.actionType === "CREATE_TASK" ? "新建任务" : "更新任务"}</dd></div>
        <div><dt>标题</dt><dd>{action.title || "未提供"}</dd></div>
        {action.description && <div><dt>说明</dt><dd>{action.description}</dd></div>}
        {action.taskId && <div><dt>任务 ID</dt><dd>{action.taskId}</dd></div>}
        {action.taskStatus && <div><dt>状态</dt><dd>{action.taskStatus}</dd></div>}
        {action.priority && <div><dt>优先级</dt><dd>{action.priority}</dd></div>}
        {action.expectedVersion !== undefined && <div><dt>预期版本</dt><dd>v{action.expectedVersion}</dd></div>}
      </dl>
      <div className="approval-actions">
        <button type="button" className="ghost" onClick={onClose} ref={laterRef}>稍后处理</button>
        <button type="button" className="danger" onClick={() => onDecision("reject")} disabled={busy}>拒绝</button>
        <button type="button" onClick={() => onDecision("confirm")} disabled={busy}>确认执行</button>
      </div>
    </section>
  </div>, document.body);
}
