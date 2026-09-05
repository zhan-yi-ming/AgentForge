import re
from uuid import UUID

from .schemas import ToolProposal


_UPDATE_PATTERN = re.compile(
    r"^update\s+task\s+(?P<task_id>[0-9a-f-]{36})\s+version\s+(?P<version>\d+)\s*[:：]\s*(?P<patch>.+)$",
    re.IGNORECASE,
)
_CREATE_PATTERN = re.compile(r"^create\s+task\s*[:：]\s*(?P<body>.+)$", re.IGNORECASE)
_CHINESE_CREATE_PATTERN = re.compile(r"^把(?P<title>.+?)整理成任务(?:[，,].*)?[。.]?$")


def plan_tool(message: str) -> ToolProposal | None:
    normalized = message.strip()
    update_match = _UPDATE_PATTERN.fullmatch(normalized)
    if update_match:
        patch = _parse_patch(update_match.group("patch"))
        if not patch:
            return None
        try:
            task_id = UUID(update_match.group("task_id"))
        except ValueError:
            return None
        return ToolProposal(
            action_type="UPDATE_TASK",
            task_id=task_id,
            expected_version=int(update_match.group("version")),
            **patch,
        )

    chinese_create = _CHINESE_CREATE_PATTERN.fullmatch(normalized)
    if chinese_create:
        title = chinese_create.group("title").strip()
        if not title:
            return None
        return ToolProposal(
            action_type="CREATE_TASK",
            title=title,
            description=normalized,
            status="TODO",
            priority=_priority_from_text(normalized),
        )

    create_match = _CREATE_PATTERN.fullmatch(normalized)
    if create_match:
        fields = _parse_create_body(create_match.group("body"))
        title = fields.pop("title", None)
        if not title:
            return None
        return ToolProposal(
            action_type="CREATE_TASK",
            title=title,
            status=fields.pop("status", "TODO"),
            priority=fields.pop("priority", "MEDIUM"),
            **fields,
        )
    return None


def _parse_create_body(body: str) -> dict[str, object]:
    segments = [segment.strip() for segment in body.split(";") if segment.strip()]
    if not segments:
        return {}
    result: dict[str, object] = {"title": segments[0]}
    assignments = _parse_assignments(segments[1:])
    if assignments is None:
        return {}
    result.update(assignments)
    return result


def _parse_patch(body: str) -> dict[str, object]:
    segments = [segment.strip() for segment in body.split(";") if segment.strip()]
    return _parse_assignments(segments) or {}


def _parse_assignments(segments: list[str]) -> dict[str, object] | None:
    allowed = {"title", "description", "status", "priority"}
    result: dict[str, object] = {}
    for segment in segments:
        if "=" not in segment:
            return None
        key, value = (part.strip() for part in segment.split("=", 1))
        key = key.lower()
        if key not in allowed or not value:
            return None
        if key in {"status", "priority"}:
            value = value.upper()
        if key == "status" and value not in {"TODO", "IN_PROGRESS", "DONE"}:
            return None
        if key == "priority" and value not in {"LOW", "MEDIUM", "HIGH"}:
            return None
        result[key] = value
    return result


def _priority_from_text(message: str) -> str:
    if "优先级设为高" in message or "优先级为高" in message:
        return "HIGH"
    if "优先级设为低" in message or "优先级为低" in message:
        return "LOW"
    return "MEDIUM"
