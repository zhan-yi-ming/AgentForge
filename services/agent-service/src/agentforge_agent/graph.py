from collections.abc import Callable
from typing import TypedDict
from uuid import UUID, uuid4

from langgraph.graph import END, START, StateGraph

from .context import ContextBundle, ContextManager, ConversationMemory
from .observability import NullObservation
from .retrieval import RetrievalResult
from .tool_planner import plan_tool


class ChatState(TypedDict, total=False):
    project_id: UUID
    user_id: UUID
    actor_admin: bool
    message: str
    conversation_id: UUID
    request_id: str
    context_bundle: ContextBundle
    answer: str


Responder = Callable[[ChatState], str]


def deterministic_responder(state: ChatState) -> str:
    bundle = state["context_bundle"]
    context = bundle.retrieved.content.strip()
    if not context:
        return f"No relevant project context was found for: {bundle.working.message}"
    return f"Relevant project context for '{bundle.working.message}':\n\n{context}"


Retriever = Callable[[UUID, UUID, bool, str, str], RetrievalResult]


def build_chat_graph(
    retriever: Retriever,
    responder: Responder = deterministic_responder,
    observation=None,
    conversation_memory: ConversationMemory | None = None,
):
    parent = observation or NullObservation()
    prepare, retrieve, plan = _context_nodes(retriever, parent, conversation_memory)

    def respond(state: ChatState) -> dict[str, str]:
        observation = parent.child("llm", "generation")
        try:
            observed = getattr(responder, "respond_observed", None)
            answer = observed(state, observation) if callable(observed) else responder(state)
            observation.update(output={"status": "completed"})
            return {"answer": answer}
        except Exception as exception:
            observation.fail(exception)
            raise
        finally:
            observation.end()

    builder = StateGraph(ChatState)
    builder.add_node("prepare", prepare)
    builder.add_node("retrieve", retrieve)
    builder.add_node("plan", plan)
    builder.add_node("respond", respond)
    builder.add_edge(START, "prepare")
    builder.add_edge("prepare", "retrieve")
    builder.add_edge("retrieve", "plan")
    builder.add_edge("plan", "respond")
    builder.add_edge("respond", END)
    return builder.compile()


def build_chat_context_graph(
    retriever: Retriever,
    observation=None,
    conversation_memory: ConversationMemory | None = None,
):
    """Run deterministic preparation, retrieval and tool planning before streaming."""

    parent = observation or NullObservation()
    prepare, retrieve, plan = _context_nodes(retriever, parent, conversation_memory)

    builder = StateGraph(ChatState)
    builder.add_node("prepare", prepare)
    builder.add_node("retrieve", retrieve)
    builder.add_node("plan", plan)
    builder.add_edge(START, "prepare")
    builder.add_edge("prepare", "retrieve")
    builder.add_edge("retrieve", "plan")
    builder.add_edge("plan", END)
    return builder.compile()


def _context_nodes(
    retriever: Retriever,
    parent,
    conversation_memory: ConversationMemory | None,
):
    def prepare(state: ChatState) -> dict[str, object]:
        def operation() -> dict[str, object]:
            conversation_id = state.get("conversation_id") or uuid4()
            conversation = (
                conversation_memory.load(
                    conversation_id,
                    state["project_id"],
                    state["user_id"],
                )
                if conversation_memory is not None
                else None
            )
            return {
                "context_bundle": ContextManager.build(
                    project_id=state["project_id"],
                    user_id=state["user_id"],
                    actor_admin=state["actor_admin"],
                    message=state["message"],
                    conversation_id=conversation_id,
                    request_id=state["request_id"],
                    conversation=conversation,
                )
            }

        return _observe(parent, "prepare", "chain", operation)

    def retrieve_context(state: ChatState) -> dict[str, object]:
        def operation() -> dict[str, object]:
            bundle = state["context_bundle"]
            project = bundle.project
            result = retriever(
                project.project_id,
                project.user_id,
                project.actor_admin,
                bundle.working.message,
                project.request_id,
            )
            return {
                "context_bundle": ContextManager.with_retrieval(bundle, result)
            }

        return _observe(
            parent,
            "retrieval",
            "retriever",
            operation,
            lambda result: {
                "status": "completed",
                "source_count": len(result["context_bundle"].retrieved.sources),
            },
        )

    def plan(state: ChatState) -> dict[str, object]:
        def operation() -> dict[str, object]:
            bundle = state["context_bundle"]
            return {
                "context_bundle": ContextManager.with_tool(
                    bundle, plan_tool(bundle.working.message)
                )
            }

        return _observe(
            parent,
            "tool",
            "tool",
            operation,
            lambda result: {
                "status": "completed",
                "proposed": result["context_bundle"].tool.proposal is not None,
                "tool": result["context_bundle"].tool.proposal.action_type
                if result["context_bundle"].tool.proposal is not None
                else None,
            },
        )

    return prepare, retrieve_context, plan


def _observe(parent, name, as_type, operation, summarize=None):
    observation = parent.child(name, as_type)
    try:
        result = operation()
        output = summarize(result) if summarize is not None else {"status": "completed"}
        observation.update(output=output)
        return result
    except Exception as exception:
        observation.fail(exception)
        raise
    finally:
        observation.end()
