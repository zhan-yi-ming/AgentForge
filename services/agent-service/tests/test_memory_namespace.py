from dataclasses import replace
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier
from uuid import uuid4

import pytest

from agentforge_agent.context import (
    ConversationMemory,
    ContextManager,
    MemoryNamespace,
    TokenCounter,
)


def namespace(**changes) -> MemoryNamespace:
    current = MemoryNamespace(
        tenant_id="tenant-a",
        workspace_id="workspace-a",
        project_id=uuid4(),
        user_id=uuid4(),
        thread_id=uuid4(),
    )
    return replace(current, **changes)


def memory(max_sessions: int = 10) -> ConversationMemory:
    return ConversationMemory(
        recent_turns=2,
        summary_token_budget=200,
        max_sessions=max_sessions,
        token_counter=TokenCounter(),
    )


def test_memory_namespace_rejects_blank_server_scopes() -> None:
    with pytest.raises(ValueError, match="tenant"):
        namespace(tenant_id="   ")
    with pytest.raises(ValueError, match="workspace"):
        namespace(workspace_id="")


def test_memory_namespace_rejects_none_server_scopes_with_clear_error() -> None:
    with pytest.raises(ValueError, match="tenant"):
        namespace(tenant_id=None)
    with pytest.raises(ValueError, match="workspace"):
        namespace(workspace_id=None)


@pytest.mark.parametrize(
    "changed_field,changed_value",
    [
        ("tenant_id", "tenant-b"),
        ("workspace_id", "workspace-b"),
        ("project_id", uuid4()),
        ("user_id", uuid4()),
        ("thread_id", uuid4()),
    ],
)
def test_conversation_memory_isolates_every_namespace_level(
    changed_field: str,
    changed_value,
) -> None:
    store = memory()
    original = namespace()
    loaded = store.load(original)
    store.commit_exchange(loaded.lease, "private question", "private answer")

    isolated = store.load(replace(original, **{changed_field: changed_value}))

    assert isolated.recent_messages == ()
    assert [item.content for item in store.load(original).recent_messages] == [
        "private question",
        "private answer",
    ]


def test_conversation_memory_rejects_stale_lease_after_lru_eviction() -> None:
    store = memory(max_sessions=1)
    original = namespace()
    stale = store.load(original)
    store.load(namespace())

    with pytest.raises(ValueError, match="changed before completion"):
        store.commit_exchange(stale.lease, "question", "stale answer")

    assert store.load(original).recent_messages == ()


def test_same_namespace_concurrent_snapshots_allow_only_one_commit() -> None:
    store = memory()
    scoped = namespace()
    snapshots = (store.load(scoped), store.load(scoped))
    ready = Barrier(2)

    def finish(index: int) -> str:
        ready.wait()
        try:
            store.commit_exchange(
                snapshots[index].lease,
                f"question-{index}",
                f"answer-{index}",
            )
            return "committed"
        except ValueError:
            return "conflict"

    with ThreadPoolExecutor(max_workers=2) as executor:
        outcomes = list(executor.map(finish, range(2)))

    assert sorted(outcomes) == ["committed", "conflict"]
    completed = store.load(scoped).recent_messages
    assert len(completed) == 2
    assert completed[0].content in {"question-0", "question-1"}


def test_different_namespaces_can_hold_exchange_claims_together() -> None:
    store = memory()
    first = store.load(namespace())
    second = store.load(namespace())

    store.claim_exchange(first.lease, "first question", "first answer")
    store.claim_exchange(second.lease, "second question", "second answer")
    store.commit_exchange(first.lease, "first question", "first answer")
    store.commit_exchange(second.lease, "second question", "second answer")

    assert [item.content for item in store.load(first.namespace).recent_messages] == [
        "first question",
        "first answer",
    ]
    assert [item.content for item in store.load(second.namespace).recent_messages] == [
        "second question",
        "second answer",
    ]


def test_releasing_cancelled_exchange_allows_an_existing_peer_lease_to_finish() -> None:
    store = memory()
    scoped = namespace()
    cancelled = store.load(scoped)
    peer = store.load(scoped)

    store.claim_exchange(cancelled.lease, "cancelled", "unused")
    store.release_exchange(cancelled.lease)
    store.claim_exchange(peer.lease, "kept", "answer")
    store.commit_exchange(peer.lease, "kept", "answer")

    assert [item.content for item in store.load(scoped).recent_messages] == [
        "kept",
        "answer",
    ]


def test_claimed_session_is_not_evicted_by_lru_capacity_pressure() -> None:
    store = memory(max_sessions=1)
    active = store.load(namespace())
    store.claim_exchange(active.lease, "question", "answer")

    with pytest.raises(ValueError, match="capacity"):
        store.load(namespace())

    store.release_exchange(active.lease)
    assert store.load(namespace()).recent_messages == ()


def test_lease_from_a_previous_memory_instance_cannot_commit_after_restart() -> None:
    scoped = namespace()
    previous = memory().load(scoped)
    restarted = memory()
    restarted.load(scoped)

    with pytest.raises(ValueError, match="changed before completion"):
        restarted.commit_exchange(previous.lease, "question", "stale answer")


def test_context_bundle_uses_one_namespace_for_conversation_and_project() -> None:
    scoped = namespace()
    loaded = memory().load(scoped)

    bundle = ContextManager.build(
        namespace=scoped,
        actor_admin=False,
        message=" explain isolation ",
        request_id="request-namespace",
        conversation=loaded,
    )

    assert bundle.conversation.namespace == scoped
    assert bundle.project.namespace == scoped
    assert bundle.conversation.lease.namespace == scoped
    assert bundle.working.message == "explain isolation"


def test_context_manager_rejects_snapshot_from_another_namespace() -> None:
    scoped = namespace()
    other = replace(scoped, user_id=uuid4())

    with pytest.raises(ValueError, match="namespace"):
        ContextManager.build(
            namespace=scoped,
            actor_admin=False,
            message="hello",
            request_id="request-mismatch",
            conversation=memory().load(other),
        )
