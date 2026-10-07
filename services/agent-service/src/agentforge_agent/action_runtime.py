from dataclasses import dataclass
from contextlib import contextmanager
from hashlib import sha256
import json
from threading import RLock
from typing import Literal, TypedDict
from uuid import UUID, uuid4

from langgraph.graph import END, START, StateGraph
from langgraph.types import Command, interrupt as graph_interrupt
from langgraph.checkpoint.postgres import PostgresSaver
from psycopg_pool import ConnectionPool

from .context import MemoryNamespace
from .schemas import ToolProposal


ACTION_STATE_SCHEMA_VERSION = 2
LEGACY_ACTION_STATE_SCHEMA_VERSION = 1
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
    workflow_id: str | None
    proposal_fingerprint: str
    status: Literal["WAITING", "RESUMED", "ABORTED"]
    action_id: str | None
    decision: Decision | None
    idempotency_key: str | None
    request_id: str


@dataclass(frozen=True)
class ActionWorkflowView:
    conversation_id: UUID
    workflow_id: UUID | None
    status: Literal["WAITING", "RESUMED", "ABORTED"]
    action_id: UUID | None
    decision: Decision | None
    idempotency_key: str | None
    request_id: str


class ActionWorkflowRuntime:
    def __init__(self, checkpointer, workflow_lock=None) -> None:
        builder = StateGraph(ActionWorkflowState)
        builder.add_node("await_decision", _await_decision)
        builder.add_edge(START, "await_decision")
        builder.add_edge("await_decision", END)
        self._graph = builder.compile(checkpointer=checkpointer)
        self._local_lock = RLock()
        self._workflow_lock = workflow_lock or (
            lambda _: _held_lock(self._local_lock)
        )

    def interrupt(
        self,
        namespace: MemoryNamespace,
        proposal: ToolProposal,
        request_id: str,
    ) -> ActionWorkflowView:
        config = _config(namespace)
        thread_key = config["configurable"]["thread_id"]
        with self._workflow_lock(thread_key):
            fingerprint = _proposal_fingerprint(proposal)
            snapshot = self._graph.get_state(config)
            if snapshot.values and snapshot.next:
                self._require_supported(snapshot.values, namespace)
                if snapshot.values.get("proposal_fingerprint") != fingerprint:
                    raise ActionWorkflowConflict(
                        "conversation is already waiting for another action"
                    )
                if (
                    snapshot.values.get("schema_version") == ACTION_STATE_SCHEMA_VERSION
                    and snapshot.values.get("request_id") != request_id
                ):
                    raise ActionWorkflowConflict(
                        "conversation is already waiting for another action round"
                    )
                return _view(snapshot.values, namespace.thread_id)

            result = self._graph.invoke(
                {
                    "schema_version": ACTION_STATE_SCHEMA_VERSION,
                    "namespace": _serialized_namespace(namespace),
                    "workflow_id": str(uuid4()),
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
        workflow_id: UUID | None = None,
        action_id: UUID,
        decision: Decision,
        idempotency_key: str,
        request_id: str,
    ) -> ActionWorkflowView:
        config = _config(namespace)
        thread_key = config["configurable"]["thread_id"]
        with self._workflow_lock(thread_key):
            snapshot = self._graph.get_state(config)
            if not snapshot.values:
                raise ActionWorkflowNotFound("action workflow was not found")
            self._require_supported(snapshot.values, namespace)
            if workflow_id is not None and snapshot.values.get("workflow_id") != str(workflow_id):
                return self._replay_historical_resume(
                    config, namespace, workflow_id, action_id, decision, idempotency_key
                )
            self._require_workflow(snapshot.values, workflow_id)

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

    def _replay_historical_resume(
        self,
        config,
        namespace: MemoryNamespace,
        workflow_id: UUID,
        action_id: UUID,
        decision: Decision,
        idempotency_key: str,
    ) -> ActionWorkflowView:
        # get_state_history eagerly loads its page. Bound memory while retaining
        # compatibility with v2 checkpoints written before this replay path existed.
        before = None
        while True:
            page = list(self._graph.get_state_history(config, before=before, limit=64))
            if not page:
                raise ActionWorkflowConflict("action workflow identity does not match")
            for snapshot in page:
                values = snapshot.values
                if values.get("workflow_id") != str(workflow_id):
                    continue
                self._require_supported(values, namespace)
                self._require_workflow(values, workflow_id)
                if snapshot.next or values.get("status") != "RESUMED":
                    continue
                self._require_replay(values, action_id, decision, idempotency_key)
                # Never invoke an old checkpoint: Java only needs the committed
                # receipt to retry its independently authorized business write.
                return _view(values, namespace.thread_id)
            before = page[-1].config

    def abort(
        self,
        namespace: MemoryNamespace,
        *,
        workflow_id: UUID | None,
        request_id: str,
    ) -> ActionWorkflowView:
        config = _config(namespace)
        thread_key = config["configurable"]["thread_id"]
        with self._workflow_lock(thread_key):
            snapshot = self._graph.get_state(config)
            if not snapshot.values:
                raise ActionWorkflowNotFound("action workflow was not found")
            self._require_supported(snapshot.values, namespace)
            if snapshot.values.get("schema_version") != ACTION_STATE_SCHEMA_VERSION:
                raise ActionWorkflowConflict("legacy action workflow cannot be aborted")
            if snapshot.values.get("request_id") != request_id:
                raise ActionWorkflowConflict("action workflow request does not match")
            if workflow_id is not None:
                self._require_workflow(snapshot.values, workflow_id)

            if snapshot.values.get("status") == "ABORTED":
                return _view(snapshot.values, namespace.thread_id)
            if not snapshot.next or snapshot.values.get("status") != "WAITING":
                raise ActionWorkflowConflict("action workflow is not waiting")

            result = self._graph.invoke(
                Command(resume={"decision": "ABORT", "request_id": request_id}),
                config=config,
            )
            return _view(result, namespace.thread_id)

    @staticmethod
    def _require_supported(values, namespace: MemoryNamespace) -> None:
        if values.get("schema_version") not in (
            LEGACY_ACTION_STATE_SCHEMA_VERSION,
            ACTION_STATE_SCHEMA_VERSION,
        ):
            raise ActionWorkflowConflict("action workflow schema is not supported")
        if values.get("namespace") != _serialized_namespace(namespace):
            raise ActionWorkflowConflict("action workflow namespace does not match")
        if (
            values.get("schema_version") == ACTION_STATE_SCHEMA_VERSION
            and not values.get("workflow_id")
        ):
            raise ActionWorkflowConflict("action workflow identity is missing")

    @staticmethod
    def _require_workflow(values, workflow_id: UUID | None) -> None:
        state_workflow_id = values.get("workflow_id")
        if values.get("schema_version") == LEGACY_ACTION_STATE_SCHEMA_VERSION:
            if workflow_id is not None:
                raise ActionWorkflowConflict("legacy action workflow has no identity")
            return
        if workflow_id is None or state_workflow_id != str(workflow_id):
            raise ActionWorkflowConflict("action workflow identity does not match")

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
        yield ActionWorkflowRuntime(
            checkpointer,
            workflow_lock=lambda thread_key: _postgres_workflow_lock(pool, thread_key),
        )
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
    if resumed.get("decision") == "ABORT":
        request_id = str(resumed.get("request_id", ""))
        if not request_id:
            raise ActionWorkflowConflict("abort metadata must not be blank")
        return {
            "status": "ABORTED",
            "action_id": None,
            "decision": None,
            "idempotency_key": None,
            "request_id": request_id,
        }
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


@contextmanager
def _held_lock(lock):
    with lock:
        yield


@contextmanager
def _postgres_workflow_lock(pool: ConnectionPool, thread_key: str):
    with pool.connection() as connection:
        with connection.transaction():
            connection.execute(
                "SELECT pg_advisory_xact_lock(hashtextextended(%s, 0))",
                (thread_key,),
            )
            yield


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
    workflow_id = values.get("workflow_id")
    return ActionWorkflowView(
        conversation_id=conversation_id,
        workflow_id=UUID(workflow_id) if workflow_id else None,
        status=values["status"],
        action_id=UUID(action_id) if action_id else None,
        decision=values.get("decision"),
        idempotency_key=values.get("idempotency_key"),
        request_id=values["request_id"],
    )
