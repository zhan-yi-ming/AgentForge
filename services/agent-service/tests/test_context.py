from uuid import uuid4

from agentforge_agent.context import ContextManager
from agentforge_agent.graph import build_chat_graph
from agentforge_agent.retrieval import RetrievalResult
from agentforge_agent.schemas import ChatSource, ToolProposal


def test_context_manager_builds_and_updates_one_explicit_bundle() -> None:
    project_id = uuid4()
    user_id = uuid4()
    conversation_id = uuid4()
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
        project_id=project_id,
        user_id=user_id,
        actor_admin=False,
        message="  explain the architecture  ",
        conversation_id=conversation_id,
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


def test_chat_graph_nodes_share_context_bundle_across_project_boundaries() -> None:
    project_id = uuid4()
    user_id = uuid4()
    conversation_id = uuid4()
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
            "project_id": project_id,
            "user_id": user_id,
            "actor_admin": False,
            "message": "  explain context  ",
            "conversation_id": conversation_id,
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
