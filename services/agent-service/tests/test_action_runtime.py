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
        action_id=action_id,
        decision="APPROVE",
        idempotency_key="decision-key-1",
        request_id="request-resume",
    )
    replayed = runtime.resume(
        scope,
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
    runtime.interrupt(scope, proposal(), "request-first")
    runtime.resume(
        scope,
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
                action_id=action_id,
                decision="APPROVE",
                idempotency_key="restart-key",
                request_id="request-after-restart",
            )

        assert resumed.status == "RESUMED"
        assert resumed.action_id == action_id


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
                            action_id=item[1],
                            decision="APPROVE",
                            idempotency_key=f"key-{item[1]}",
                            request_id=f"resume-{item[1]}",
                        ),
                        zip(scopes, action_ids, strict=True),
                    )
                )

        assert {view.status for view in waiting} == {"WAITING"}
        assert {view.status for view in resumed} == {"RESUMED"}
        assert {view.action_id for view in resumed} == set(action_ids)
