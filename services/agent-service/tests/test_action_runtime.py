from concurrent.futures import ThreadPoolExecutor
from uuid import uuid4

import pytest
from langgraph.checkpoint.memory import InMemorySaver
from testcontainers.community.postgres import PostgresContainer

from agentforge_agent.action_runtime import (
    ActionWorkflowConflict,
    ActionWorkflowNotFound,
    ActionWorkflowRuntime,
    open_postgres_action_runtime,
)
from agentforge_agent.context import MemoryNamespace
from agentforge_agent.schemas import ToolProposal


def namespace():
    return MemoryNamespace(
        tenant_id="agentforge",
        workspace_id="default",
        project_id=uuid4(),
        user_id=uuid4(),
        thread_id=uuid4(),
    )


def proposal():
    return ToolProposal(
        action_type="CREATE_TASK",
        title="Recover after restart",
        status="TODO",
        priority="HIGH",
    )


def test_action_workflow_interrupts_resumes_and_replays_the_same_decision():
    runtime = ActionWorkflowRuntime(InMemorySaver())
    scope = namespace()

    waiting = runtime.interrupt(scope, proposal(), "request-start")

    assert waiting.status == "WAITING"
    assert waiting.conversation_id == scope.thread_id

    action_id = uuid4()
    resumed = runtime.resume(
        scope,
        workflow_id=waiting.workflow_id,
        action_id=action_id,
        decision="APPROVE",
        idempotency_key="decision-key-1",
        request_id="request-resume",
    )
    replayed = runtime.resume(
        scope,
        workflow_id=waiting.workflow_id,
        action_id=action_id,
        decision="APPROVE",
        idempotency_key="decision-key-1",
        request_id="request-replay",
    )

    assert resumed.status == "RESUMED"
    assert resumed.action_id == action_id
    assert resumed.decision == "APPROVE"
    assert replayed == resumed

    with pytest.raises(ActionWorkflowConflict):
        runtime.resume(
            scope,
            workflow_id=waiting.workflow_id,
            action_id=action_id,
            decision="APPROVE",
            idempotency_key="another-key",
            request_id="request-conflict",
        )


def test_same_conversation_id_is_isolated_by_project_and_user_namespace():
    runtime = ActionWorkflowRuntime(InMemorySaver())
    first = namespace()
    second = MemoryNamespace(
        tenant_id=first.tenant_id,
        workspace_id=first.workspace_id,
        project_id=uuid4(),
        user_id=uuid4(),
        thread_id=first.thread_id,
    )

    runtime.interrupt(first, proposal(), "request-first")
    second_waiting = runtime.interrupt(second, proposal(), "request-second")

    assert second_waiting.status == "WAITING"
    with pytest.raises(ActionWorkflowNotFound):
        runtime.resume(
            MemoryNamespace(
                tenant_id=first.tenant_id,
                workspace_id=first.workspace_id,
                project_id=uuid4(),
                user_id=first.user_id,
                thread_id=first.thread_id,
            ),
            action_id=uuid4(),
            decision="APPROVE",
            idempotency_key="wrong-scope-key",
            request_id="wrong-scope-request",
        )


def test_new_proposal_after_resume_starts_another_waiting_round():
    runtime = ActionWorkflowRuntime(InMemorySaver())
    scope = namespace()
    first_waiting = runtime.interrupt(scope, proposal(), "request-first")
    runtime.resume(
        scope,
        workflow_id=first_waiting.workflow_id,
        action_id=uuid4(),
        decision="APPROVE",
        idempotency_key="first-key",
        request_id="resume-first",
    )
    next_proposal = ToolProposal(
        action_type="CREATE_TASK",
        title="Second action",
        status="TODO",
        priority="MEDIUM",
    )

    waiting = runtime.interrupt(scope, next_proposal, "request-second")

    assert waiting.status == "WAITING"
    assert waiting.action_id is None
    assert waiting.request_id == "request-second"


def test_old_decision_cannot_resume_a_new_waiting_round():
    runtime = ActionWorkflowRuntime(InMemorySaver())
    scope = namespace()
    first_waiting = runtime.interrupt(scope, proposal(), "request-first")
    first_action = uuid4()
    runtime.resume(
        scope,
        workflow_id=first_waiting.workflow_id,
        action_id=first_action,
        decision="REJECT",
        idempotency_key="first-key",
        request_id="resume-first",
    )

    second_waiting = runtime.interrupt(scope, proposal(), "request-second")

    assert second_waiting.workflow_id != first_waiting.workflow_id
    with pytest.raises(ActionWorkflowConflict):
        runtime.resume(
            scope,
            workflow_id=first_waiting.workflow_id,
            action_id=first_action,
            decision="REJECT",
            idempotency_key="first-key",
            request_id="retry-old-reject",
        )


def test_waiting_round_replays_only_the_same_request_and_proposal():
    runtime = ActionWorkflowRuntime(InMemorySaver())
    scope = namespace()

    waiting = runtime.interrupt(scope, proposal(), "same-request")
    replayed = runtime.interrupt(scope, proposal(), "same-request")

    assert replayed.workflow_id == waiting.workflow_id
    with pytest.raises(ActionWorkflowConflict):
        runtime.interrupt(scope, proposal(), "new-request")


def test_aborted_waiting_round_is_replayable_and_allows_a_new_round():
    runtime = ActionWorkflowRuntime(InMemorySaver())
    scope = namespace()
    waiting = runtime.interrupt(scope, proposal(), "request-invalid")

    aborted = runtime.abort(
        scope,
        workflow_id=waiting.workflow_id,
        request_id="request-invalid",
    )
    replayed = runtime.abort(
        scope,
        workflow_id=waiting.workflow_id,
        request_id="request-invalid",
    )
    next_waiting = runtime.interrupt(scope, proposal(), "request-corrected")

    assert aborted.status == "ABORTED"
    assert replayed == aborted
    assert next_waiting.status == "WAITING"
    assert next_waiting.workflow_id != waiting.workflow_id


def test_same_request_retry_after_abort_starts_a_new_waiting_round():
    runtime = ActionWorkflowRuntime(InMemorySaver())
    scope = namespace()
    waiting = runtime.interrupt(scope, proposal(), "same-request")
    runtime.abort(
        scope,
        workflow_id=waiting.workflow_id,
        request_id="same-request",
    )

    retried = runtime.interrupt(scope, proposal(), "same-request")

    assert retried.status == "WAITING"
    assert retried.request_id == "same-request"
    assert retried.workflow_id != waiting.workflow_id


def test_abort_without_workflow_id_still_requires_the_exact_request():
    runtime = ActionWorkflowRuntime(InMemorySaver())
    scope = namespace()
    waiting = runtime.interrupt(scope, proposal(), "request-invalid")

    with pytest.raises(ActionWorkflowConflict):
        runtime.abort(scope, workflow_id=None, request_id="another-request")
    with pytest.raises(ActionWorkflowConflict):
        runtime.abort(scope, workflow_id=uuid4(), request_id="request-invalid")

    replayed_waiting = runtime.interrupt(scope, proposal(), "request-invalid")
    aborted = runtime.abort(
        scope,
        workflow_id=None,
        request_id="request-invalid",
    )

    assert replayed_waiting.workflow_id == waiting.workflow_id
    assert aborted.workflow_id == waiting.workflow_id
    assert aborted.status == "ABORTED"


def test_resumed_round_cannot_be_aborted():
    runtime = ActionWorkflowRuntime(InMemorySaver())
    scope = namespace()
    waiting = runtime.interrupt(scope, proposal(), "request-start")
    runtime.resume(
        scope,
        workflow_id=waiting.workflow_id,
        action_id=uuid4(),
        decision="APPROVE",
        idempotency_key="resume-key",
        request_id="resume-request",
    )

    with pytest.raises(ActionWorkflowConflict):
        runtime.abort(
            scope,
            workflow_id=waiting.workflow_id,
            request_id="resume-request",
        )


def test_postgres_action_workflow_resumes_after_runtime_is_recreated():
    scope = namespace()
    action_id = uuid4()
    with PostgresContainer("pgvector/pgvector:pg17") as postgres:
        dsn = postgres.get_connection_url().replace(
            "postgresql+psycopg2", "postgresql"
        )
        import psycopg

        with psycopg.connect(dsn, autocommit=True) as connection:
            connection.execute("CREATE SCHEMA agent_checkpoint")

        with open_postgres_action_runtime(dsn) as first_runtime:
            waiting = first_runtime.interrupt(scope, proposal(), "request-start")
            assert waiting.status == "WAITING"

        with open_postgres_action_runtime(dsn) as restarted_runtime:
            resumed = restarted_runtime.resume(
                scope,
                workflow_id=waiting.workflow_id,
                action_id=action_id,
                decision="APPROVE",
                idempotency_key="restart-key",
                request_id="request-after-restart",
            )

        assert resumed.status == "RESUMED"
        assert resumed.action_id == action_id


def test_postgres_aborted_round_survives_restart_and_allows_the_next_round():
    scope = namespace()
    with PostgresContainer("pgvector/pgvector:pg17") as postgres:
        dsn = postgres.get_connection_url().replace(
            "postgresql+psycopg2", "postgresql"
        )
        import psycopg

        with psycopg.connect(dsn, autocommit=True) as connection:
            connection.execute("CREATE SCHEMA agent_checkpoint")

        with open_postgres_action_runtime(dsn) as first_runtime:
            waiting = first_runtime.interrupt(scope, proposal(), "request-invalid")
            aborted = first_runtime.abort(
                scope,
                workflow_id=waiting.workflow_id,
                request_id="request-invalid",
            )
            assert aborted.status == "ABORTED"

        with open_postgres_action_runtime(dsn) as restarted_runtime:
            replayed = restarted_runtime.abort(
                scope,
                workflow_id=waiting.workflow_id,
                request_id="request-invalid",
            )
            next_waiting = restarted_runtime.interrupt(
                scope, proposal(), "request-corrected"
            )

        assert replayed == aborted
        assert next_waiting.status == "WAITING"
        assert next_waiting.workflow_id != waiting.workflow_id


def test_postgres_action_runtime_handles_concurrent_interrupts_and_resumes():
    scopes = [namespace() for _ in range(8)]
    action_ids = [uuid4() for _ in scopes]
    with PostgresContainer("pgvector/pgvector:pg17") as postgres:
        dsn = postgres.get_connection_url().replace(
            "postgresql+psycopg2", "postgresql"
        )
        import psycopg

        with psycopg.connect(dsn, autocommit=True) as connection:
            connection.execute("CREATE SCHEMA agent_checkpoint")

        with open_postgres_action_runtime(dsn) as runtime:
            with ThreadPoolExecutor(max_workers=8) as executor:
                waiting = list(
                    executor.map(
                        lambda scope: runtime.interrupt(
                            scope, proposal(), f"interrupt-{scope.thread_id}"
                        ),
                        scopes,
                    )
                )
                resumed = list(
                    executor.map(
                        lambda item: runtime.resume(
                            item[0],
                            workflow_id=item[2].workflow_id,
                            action_id=item[1],
                            decision="APPROVE",
                            idempotency_key=f"key-{item[1]}",
                            request_id=f"resume-{item[1]}",
                        ),
                        zip(scopes, action_ids, waiting, strict=True),
                    )
                )

        assert {view.status for view in waiting} == {"WAITING"}
        assert {view.status for view in resumed} == {"RESUMED"}
        assert {view.action_id for view in resumed} == set(action_ids)


def test_postgres_concurrent_replay_of_one_round_returns_one_workflow_id():
    scope = namespace()
    with PostgresContainer("pgvector/pgvector:pg17") as postgres:
        dsn = postgres.get_connection_url().replace(
            "postgresql+psycopg2", "postgresql"
        )
        import psycopg

        with psycopg.connect(dsn, autocommit=True) as connection:
            connection.execute("CREATE SCHEMA agent_checkpoint")

        with open_postgres_action_runtime(dsn) as runtime:
            with ThreadPoolExecutor(max_workers=2) as executor:
                waiting = list(
                    executor.map(
                        lambda _: runtime.interrupt(
                            scope, proposal(), "same-round-request"
                        ),
                        range(2),
                    )
                )

        assert {view.status for view in waiting} == {"WAITING"}
        assert len({view.workflow_id for view in waiting}) == 1
