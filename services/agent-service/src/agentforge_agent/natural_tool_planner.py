"""Validate untrusted model Action Intent against authorized retrieval scope."""

import json
from uuid import UUID

from .context import ContextBundle
from .schemas import ToolProposal


ALLOWED_FIELDS = {
    "actionType", "taskId", "expectedVersion", "title", "description", "status", "priority"
}


def parse_tool_intent(content: str | None, bundle: ContextBundle) -> ToolProposal | None:
    if not isinstance(content, str) or len(content) > 8192:
        return None
    try:
        raw = json.loads(content)
    except (TypeError, ValueError):
        return None
    if not isinstance(raw, dict) or not set(raw) <= ALLOWED_FIELDS:
        return None
    action_type = raw.get("actionType")
    if action_type == "NONE":
        return None
    if not isinstance(action_type, str) or action_type not in {"CREATE_TASK", "UPDATE_TASK"}:
        return None

    title = raw.get("title")
    description = raw.get("description")
    status = raw.get("status")
    priority = raw.get("priority")
    if title is not None and (not isinstance(title, str) or not 1 <= len(title.strip()) <= 200):
        return None
    if description is not None and (not isinstance(description, str) or len(description) > 2000):
        return None
    if status is not None and (not isinstance(status, str) or status not in {"TODO", "IN_PROGRESS", "DONE"}):
        return None
    if priority is not None and (not isinstance(priority, str) or priority not in {"LOW", "MEDIUM", "HIGH"}):
        return None

    if action_type == "CREATE_TASK":
        if raw.get("taskId") is not None or raw.get("expectedVersion") is not None or not title:
            return None
        return ToolProposal(
            action_type="CREATE_TASK", title=title.strip(), description=description,
            status=status or "TODO", priority=priority or "MEDIUM",
        )

    task_id = raw.get("taskId")
    version = raw.get("expectedVersion")
    if not isinstance(task_id, str) or not isinstance(version, int) or isinstance(version, bool):
        return None
    try:
        parsed_id = UUID(task_id)
    except ValueError:
        return None
    if not any(
        target.task_id == parsed_id and target.version == version
        for target in bundle.retrieved.task_targets
    ):
        return None
    if not any(value is not None for value in (title, description, status, priority)):
        return None
    return ToolProposal(
        action_type="UPDATE_TASK", task_id=parsed_id, expected_version=version,
        title=title.strip() if title else None, description=description,
        status=status, priority=priority,
    )
