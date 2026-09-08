from uuid import uuid4

import pytest

from agentforge_agent.context import (
    ConversationLease,
    ConversationContext,
    ConversationMemory,
    ContextManager,
    MemoryNamespace,
    TokenCounter,
)
from agentforge_agent.graph import build_chat_graph
from agentforge_agent.retrieval import RetrievalResult
from agentforge_agent.schemas import ChatSource, ToolProposal


def namespace(project_id=None, user_id=None, thread_id=None) -> MemoryNamespace:
    return MemoryNamespace(
        tenant_id="agentforge",
        workspace_id="default",
        project_id=project_id or uuid4(),
        user_id=user_id or uuid4(),
        thread_id=thread_id or uuid4(),
    )


def commit(memory, scoped, user_message, assistant_message) -> None:
    memory.commit_exchange(
        memory.load(scoped).lease,
        user_message,
        assistant_message,
    )


def test_context_manager_builds_and_updates_one_explicit_bundle() -> None:
    project_id = uuid4()
    user_id = uuid4()
    conversation_id = uuid4()
    scoped = namespace(project_id, user_id, conversation_id)
    source = ChatSource(
        source_type="WIKI",
        source_id=uuid4(),
        title="Architecture",
        excerpt="Java owns writes.",
    )
    proposal = ToolProposal(
        action_type="CREATE_TASK",
        title="Review context",
        description="Review ContextBundle boundaries.",
        status="TODO",
        priority="HIGH",
    )

    initial = ContextManager.build(
        namespace=scoped,
        actor_admin=False,
        message="  explain the architecture  ",
        request_id="request-context",
    )
    retrieved = ContextManager.with_retrieval(
        initial,
        RetrievalResult(context="Java owns writes.", sources=[source]),
    )
    completed = ContextManager.with_tool(retrieved, proposal)

    assert completed.working.message == "explain the architecture"
    assert completed.conversation.summary is None
    assert completed.conversation.conversation_id == conversation_id
    assert completed.project.project_id == project_id
    assert completed.project.user_id == user_id
    assert completed.project.actor_admin is False
    assert completed.project.request_id == "request-context"
    assert completed.retrieved.content == "Java owns writes."
    assert completed.retrieved.sources == (source,)
    assert completed.tool.proposal == proposal
    assert initial.retrieved.content == ""
    assert initial.tool.proposal is None


def test_context_manager_rejects_mismatched_conversation_snapshot() -> None:
    scoped = namespace()
    other = namespace()
    with pytest.raises(ValueError, match="namespace"):
        ContextManager.build(
            namespace=scoped,
            actor_admin=False,
            message="hello",
            request_id="request-mismatch",
            conversation=ConversationContext(
                namespace=other,
                lease=ConversationLease(other, uuid4()),
            ),
        )


def test_chat_graph_nodes_share_context_bundle_across_project_boundaries() -> None:
    project_id = uuid4()
    user_id = uuid4()
    conversation_id = uuid4()
    scoped = namespace(project_id, user_id, conversation_id)
    captured = {}

    def retriever(received_project_id, received_user_id, actor_admin, query, request_id):
        captured["retrieval"] = (
            received_project_id,
            received_user_id,
            actor_admin,
            query,
            request_id,
        )
        return RetrievalResult(context="Project-scoped context", sources=[])

    def responder(state):
        captured["bundle"] = state["context_bundle"]
        return "Context-aware answer"

    result = build_chat_graph(retriever, responder).invoke(
        {
            "namespace": scoped,
            "actor_admin": False,
            "message": "  explain context  ",
            "request_id": "request-graph-context",
        }
    )

    bundle = captured["bundle"]
    assert captured["retrieval"] == (
        project_id,
        user_id,
        False,
        "explain context",
        "request-graph-context",
    )
    assert bundle is result["context_bundle"]
    assert bundle.working.message == "explain context"
    assert bundle.retrieved.content == "Project-scoped context"
    assert bundle.tool.proposal is None
    assert result["answer"] == "Context-aware answer"


def test_conversation_memory_moves_only_completed_raw_exchange_into_summary() -> None:
    memory = ConversationMemory(
        recent_turns=1,
        summary_token_budget=200,
        max_sessions=10,
        token_counter=TokenCounter(),
    )
    project_id = uuid4()
    user_id = uuid4()
    conversation_id = uuid4()
    scoped = namespace(project_id, user_id, conversation_id)

    assert memory.load(scoped).summary is None
    commit(
        memory,
        scoped,
        "必须保留 Java 负责业务写入",
        "已记录该约束",
    )
    commit(
        memory,
        scoped,
        "最近的问题",
        "最近的回答",
    )

    context = memory.load(scoped)
    assert [(message.role, message.content) for message in context.recent_messages] == [
        ("user", "最近的问题"),
        ("assistant", "最近的回答"),
    ]
    assert context.summary is not None
    assert "Java 负责业务写入" in context.summary
    assert "已记录该约束" in context.summary
    assert "最近的问题" not in context.summary


def test_conversation_memory_separates_cross_scope_conversation_reuse() -> None:
    memory = ConversationMemory(
        recent_turns=2,
        summary_token_budget=100,
        max_sessions=10,
        token_counter=TokenCounter(),
    )
    project_id = uuid4()
    user_id = uuid4()
    conversation_id = uuid4()
    scoped = namespace(project_id, user_id, conversation_id)

    commit(memory, scoped, "private", "answer")

    assert (
        memory.load(namespace(uuid4(), user_id, conversation_id)).recent_messages
        == ()
    )
    assert (
        memory.load(namespace(project_id, uuid4(), conversation_id)).recent_messages
        == ()
    )
    assert memory.load(scoped).recent_messages


def test_conversation_memory_evicts_least_recently_used_session() -> None:
    memory = ConversationMemory(
        recent_turns=1,
        summary_token_budget=100,
        max_sessions=2,
        token_counter=TokenCounter(),
    )
    project_id = uuid4()
    user_id = uuid4()
    first = uuid4()
    second = uuid4()
    third = uuid4()
    first_scope = namespace(project_id, user_id, first)
    second_scope = namespace(project_id, user_id, second)
    third_scope = namespace(project_id, user_id, third)
    commit(memory, first_scope, "first", "answer-first")
    commit(memory, second_scope, "second", "answer-second")
    memory.load(first_scope)

    commit(memory, third_scope, "third", "answer-third")

    assert memory.load(first_scope).recent_messages
    assert memory.load(second_scope).recent_messages == ()


def test_conversation_summary_respects_its_own_budget() -> None:
    counter = TokenCounter()
    memory = ConversationMemory(
        recent_turns=1,
        summary_token_budget=32,
        max_sessions=2,
        token_counter=counter,
    )
    project_id = uuid4()
    user_id = uuid4()
    conversation_id = uuid4()
    scoped = namespace(project_id, user_id, conversation_id)
    for index in range(5):
        commit(
            memory,
            scoped,
            f"constraint-{index}",
            f"answer-{index}",
        )

    summary = memory.load(scoped).summary

    assert summary is not None
    assert counter.count_text(summary) <= 32


def test_conversation_memory_caps_each_stored_message() -> None:
    counter = TokenCounter()
    memory = ConversationMemory(
        recent_turns=1,
        summary_token_budget=64,
        max_sessions=2,
        token_counter=counter,
        message_token_budget=16,
    )
    project_id = uuid4()
    user_id = uuid4()
    conversation_id = uuid4()
    scoped = namespace(project_id, user_id, conversation_id)

    commit(
        memory,
        scoped,
        "user-content-that-is-too-long",
        "assistant-content-that-is-too-long",
    )

    context = memory.load(scoped)
    assert len(context.recent_messages) == 2
    assert all(counter.count_text(item.content) <= 16 for item in context.recent_messages)
