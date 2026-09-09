from dataclasses import dataclass
import json
from pathlib import Path
from typing import Any

from agentforge_agent.schemas import ToolProposal


@dataclass(frozen=True)
class CorpusSource:
    id: str
    source_type: str
    title: str
    content: str


@dataclass(frozen=True)
class RagCase:
    id: str
    query: str
    relevant_source_ids: frozenset[str]
    k: int


@dataclass(frozen=True)
class AnswerCase:
    id: str
    answer: str
    context: str


@dataclass(frozen=True)
class ToolCase:
    id: str
    input: str
    expected_proposal: dict[str, object] | None


@dataclass(frozen=True)
class EvaluationDataset:
    schema_version: int
    name: str
    corpus: tuple[CorpusSource, ...]
    rag_cases: tuple[RagCase, ...]
    answer_cases: tuple[AnswerCase, ...]
    tool_cases: tuple[ToolCase, ...]


def load_dataset(path: str | Path) -> EvaluationDataset:
    raw = json.loads(Path(path).read_text(encoding="utf-8"))
    if raw.get("schemaVersion") != 1:
        raise ValueError("unsupported dataset schemaVersion")

    corpus = tuple(
        CorpusSource(
            id=_required_text(item, "id"),
            source_type=_source_type(item.get("sourceType")),
            title=_required_text(item, "title"),
            content=_required_text(item, "content"),
        )
        for item in _required_list(raw, "corpus")
    )
    _require_unique([source.id for source in corpus], "corpus source")
    corpus_ids = {source.id for source in corpus}

    rag_cases = tuple(_rag_case(item) for item in _required_list(raw, "ragCases"))
    answer_cases = tuple(
        AnswerCase(
            id=_required_text(item, "id"),
            answer=_required_text(item, "answer"),
            context=_required_text(item, "context"),
        )
        for item in _required_list(raw, "answerCases")
    )
    tool_cases = tuple(_tool_case(item) for item in _required_list(raw, "toolCases"))
    _require_unique([case.id for case in rag_cases], "RAG case")
    _require_unique([case.id for case in answer_cases], "answer case")
    _require_unique([case.id for case in tool_cases], "tool case")

    for case in rag_cases:
        unknown_ids = case.relevant_source_ids.difference(corpus_ids)
        if unknown_ids:
            raise ValueError(
                f"RAG case {case.id} references unknown corpus source: "
                f"{', '.join(sorted(unknown_ids))}"
            )

    return EvaluationDataset(
        schema_version=1,
        name=_required_text(raw, "name"),
        corpus=corpus,
        rag_cases=rag_cases,
        answer_cases=answer_cases,
        tool_cases=tool_cases,
    )


def _rag_case(item: dict[str, Any]) -> RagCase:
    relevant = item.get("relevantSourceIds")
    if not isinstance(relevant, list) or not relevant or not all(
        isinstance(value, str) and value.strip() for value in relevant
    ):
        raise ValueError("relevantSourceIds must be a non-empty string list")
    k = item.get("k")
    if not isinstance(k, int) or isinstance(k, bool) or k < 1:
        raise ValueError("RAG case k must be at least 1")
    return RagCase(
        id=_required_text(item, "id"),
        query=_required_text(item, "query"),
        relevant_source_ids=frozenset(relevant),
        k=k,
    )


def _tool_case(item: dict[str, Any]) -> ToolCase:
    expected = item.get("expectedProposal")
    if expected is not None:
        if not isinstance(expected, dict):
            raise ValueError("expectedProposal must be an object or null")
        allowed_keys = {
            "actionType",
            "taskId",
            "expectedVersion",
            "title",
            "description",
            "status",
            "priority",
        }
        unknown_keys = set(expected).difference(allowed_keys)
        if unknown_keys:
            raise ValueError(
                "expectedProposal contains unknown parameter: "
                + ", ".join(sorted(unknown_keys))
            )
        expected = ToolProposal.model_validate(expected).model_dump(
            mode="json",
            by_alias=True,
            exclude_none=True,
        )
        _validate_tool_gold(expected)
    return ToolCase(
        id=_required_text(item, "id"),
        input=_required_text(item, "input"),
        expected_proposal=expected,
    )


def _required_list(item: dict[str, Any], key: str) -> list[dict[str, Any]]:
    value = item.get(key)
    if not isinstance(value, list) or not all(isinstance(entry, dict) for entry in value):
        raise ValueError(f"{key} must be a list of objects")
    return value


def _required_text(item: dict[str, Any], key: str) -> str:
    value = item.get(key)
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{key} must be non-empty text")
    return value.strip()


def _source_type(value: object) -> str:
    if value not in {"WIKI", "TASK"}:
        raise ValueError("sourceType must be WIKI or TASK")
    return str(value)


def _require_unique(values: list[str], label: str) -> None:
    if len(values) != len(set(values)):
        raise ValueError(f"duplicate {label} id")


def _validate_tool_gold(expected: dict[str, object]) -> None:
    action_type = expected["actionType"]
    if action_type == "CREATE_TASK":
        if not expected.get("title"):
            raise ValueError("CREATE_TASK gold requires title")
        if "taskId" in expected or "expectedVersion" in expected:
            raise ValueError("CREATE_TASK gold cannot include update identity")
        return

    if "taskId" not in expected or "expectedVersion" not in expected:
        raise ValueError("UPDATE_TASK gold requires taskId and expectedVersion")
    if not any(key in expected for key in ("title", "description", "status", "priority")):
        raise ValueError("UPDATE_TASK gold requires at least one changed field")
