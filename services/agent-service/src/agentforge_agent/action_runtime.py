from dataclasses import dataclass
from contextlib import contextmanager
from hashlib import sha256
import json
from typing import Literal, TypedDict
from uuid import UUID

from langgraph.graph import END, START, StateGraph
from langgraph.types import Command, interrupt as graph_interrupt
from langgraph.checkpoint.postgres import PostgresSaver
from psycopg_pool import ConnectionPool

from .context import MemoryNamespace
from .schemas import ToolProposal


ACTION_STATE_SCHEMA_VERSION = 1
Decision = Literal["APPROVE", "REJECT"]


class ActionWorkflowError(ValueError):
    pass


class ActionWorkflowNotFound(ActionWorkflowError):
    pass


class ActionWorkflowConflict(ActionWorkflowError):
    pass


class ActionWorkflowState(TypedDict):
    schema_version: int
    namespace: dict[str, str]
    proposal_fingerprint: str
    status: Literal["WAITING", "RESUMED"]
    action_id: str | None
    decision: Decision | None
    idempotency_key: str | None
    request_id: str


@dataclass(frozen=True)
class ActionWorkflowView:
    conversation_id: UUID
    status: Literal["WAITING", "RESUMED"]
    action_id: UUID | None
    decision: Decision | None
    idempotency_key: str | None
    request_id: str


class ActionWorkflowRuntime:
    def __init__(self, checkpointer) -> None:
        builder = StateGraph(ActionWorkflowState)
        builder.add_node("await_decision", _await_decision)
        builder.add_edge(START, "await_decision")
        builder.add_edge("await_decision", END)
        self._graph = builder.compile(checkpointer=checkpointer)

    def interrupt(
        self,
        namespace: MemoryNamespace,
        proposal: ToolProposal,
        request_id: str,
    ) -> ActionWorkflowView:
        config = _config(namespace)
        fingerprint = _proposal_fingerprint(proposal)
        snapshot = self._graph.get_state(config)
        if snapshot.values and snapshot.next:
            self._require_supported(snapshot.values, namespace)
            if snapshot.values.get("proposal_fingerprint") != fingerprint:
                raise ActionWorkflowConflict(
                    "conversation is already waiting for another action"
                )
            return _view(snapshot.values, namespace.thread_id)

        result = self._graph.invoke(
            {
                "schema_version": ACTION_STATE_SCHEMA_VERSION,
                "namespace": _serialized_namespace(namespace),
                "proposal_fingerprint": fingerprint,
                "status": "WAITING",
                "action_id": None,
                "decision": None,
                "idempotency_key": None,
                "request_id": request_id,
            },
            config=config,
        )
        if "__interrupt__" not in result:
            raise ActionWorkflowConflict("action workflow did not interrupt")
        return _view(result, namespace.thread_id)

    def resume(
        self,
        namespace: MemoryNamespace,
        *,
        action_id: UUID,
        decision: Decision,
        idempotency_key: str,
        request_id: str,
    ) -> ActionWorkflowView:
        config = _config(namespace)
        snapshot = self._graph.get_state(config)
        if not snapshot.values:
            raise ActionWorkflowNotFound("action workflow was not found")
        self._require_supported(snapshot.values, namespace)

        if snapshot.values.get("status") == "RESUMED":
            self._require_replay(snapshot.values, action_id, decision, idempotency_key)
            return _view(snapshot.values, namespace.thread_id)
        if not snapshot.next or snapshot.values.get("status") != "WAITING":
            raise ActionWorkflowConflict("action workflow is not waiting")

        result = self._graph.invoke(
            Command(
                resume={
                    "action_id": str(action_id),
                    "decision": decision,
                    "idempotency_key": idempotency_key,
                    "request_id": request_id,
                }
            ),
            config=config,
        )
        return _view(result, namespace.thread_id)

    @staticmethod
    def _require_supported(values, namespace: MemoryNamespace) -> None:
        if values.get("schema_version") != ACTION_STATE_SCHEMA_VERSION:
            raise ActionWorkflowConflict("action workflow schema is not supported")
        if values.get("namespace") != _serialized_namespace(namespace):
            raise ActionWorkflowConflict("action workflow namespace does not match")

    @staticmethod
    def _require_replay(
        values,
        action_id: UUID,
        decision: Decision,
        idempotency_key: str,
    ) -> None:
        if (
            values.get("action_id") != str(action_id)
            or values.get("decision") != decision
            or values.get("idempotency_key") != idempotency_key
        ):
            raise ActionWorkflowConflict(
                "action workflow was resumed with another decision"
            )


@contextmanager
def open_postgres_action_runtime(dsn: str):
    pool = ConnectionPool(
        conninfo=dsn,
        min_size=1,
        max_size=10,
        open=False,
        kwargs={
            "autocommit": True,
            "prepare_threshold": 0,
            "options": "-c search_path=agent_checkpoint",
        },
    )
    pool.open()
    try:
        pool.wait()
        checkpointer = PostgresSaver(pool)
        checkpointer.setup()
        yield ActionWorkflowRuntime(checkpointer)
    finally:
        pool.close()


def _await_decision(state: ActionWorkflowState) -> dict[str, object]:
    resumed = graph_interrupt(
        {
            "schema_version": state["schema_version"],
            "proposal_fingerprint": state["proposal_fingerprint"],
            "status": "WAITING",
        }
    )
    if not isinstance(resumed, dict):
        raise ActionWorkflowConflict("resume payload is invalid")
    try:
        action_id = str(UUID(str(resumed["action_id"])))
        decision = resumed["decision"]
        idempotency_key = str(resumed["idempotency_key"])
        request_id = str(resumed["request_id"])
    except (KeyError, TypeError, ValueError) as exception:
        raise ActionWorkflowConflict("resume payload is invalid") from exception
    if decision not in ("APPROVE", "REJECT"):
        raise ActionWorkflowConflict("resume decision is invalid")
    if not idempotency_key or not request_id:
        raise ActionWorkflowConflict("resume metadata must not be blank")
    return {
        "status": "RESUMED",
        "action_id": action_id,
        "decision": decision,
        "idempotency_key": idempotency_key,
        "request_id": request_id,
    }


def _config(namespace: MemoryNamespace) -> dict[str, dict[str, str]]:
    canonical = json.dumps(
        _serialized_namespace(namespace),
        sort_keys=True,
        separators=(",", ":"),
    )
    physical_thread_id = sha256(canonical.encode("utf-8")).hexdigest()
    return {"configurable": {"thread_id": physical_thread_id}}


def _serialized_namespace(namespace: MemoryNamespace) -> dict[str, str]:
    return {
        "tenant_id": namespace.tenant_id,
        "workspace_id": namespace.workspace_id,
        "project_id": str(namespace.project_id),
        "user_id": str(namespace.user_id),
        "thread_id": str(namespace.thread_id),
    }


def _proposal_fingerprint(proposal: ToolProposal) -> str:
    payload = proposal.model_dump(mode="json", by_alias=True)
    canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"))
    return sha256(canonical.encode("utf-8")).hexdigest()


def _view(values, conversation_id: UUID) -> ActionWorkflowView:
    action_id = values.get("action_id")
    return ActionWorkflowView(
        conversation_id=conversation_id,
        status=values["status"],
        action_id=UUID(action_id) if action_id else None,
        decision=values.get("decision"),
        idempotency_key=values.get("idempotency_key"),
        request_id=values["request_id"],
    )
