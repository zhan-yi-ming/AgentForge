from collections.abc import Callable
from typing import TypedDict
from uuid import UUID, uuid4

from langgraph.graph import END, START, StateGraph

from .observability import NullObservation
from .retrieval import RetrievalResult
from .schemas import ChatSource, ToolProposal
from .tool_planner import plan_tool


class ChatState(TypedDict, total=False):
    project_id: UUID
    user_id: UUID
    actor_admin: bool
    message: str
    normalized_message: str
    conversation_id: UUID
    request_id: str
    retrieved_context: str
    sources: list[ChatSource]
    tool_proposal: ToolProposal | None
    answer: str


Responder = Callable[[ChatState], str]


def deterministic_responder(state: ChatState) -> str:
    context = state.get("retrieved_context", "").strip()
    if not context:
        return f"No relevant project context was found for: {state['normalized_message']}"
    return f"Relevant project context for '{state['normalized_message']}':\n\n{context}"


Retriever = Callable[[UUID, UUID, bool, str, str], RetrievalResult]


def build_chat_graph(
    retriever: Retriever,
    responder: Responder = deterministic_responder,
    observation=None,
):
    parent = observation or NullObservation()

    def prepare(state: ChatState) -> dict[str, object]:
        def operation() -> dict[str, object]:
            message = state["message"].strip()
            if not message:
                raise ValueError("message must contain non-whitespace characters")
            return {
                "normalized_message": message,
                "conversation_id": state.get("conversation_id") or uuid4(),
            }

        return _observe(parent, "prepare", "chain", operation)

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

    def retrieve(state: ChatState) -> dict[str, object]:
        def operation() -> dict[str, object]:
            result = retriever(
                state["project_id"],
                state["user_id"],
                state["actor_admin"],
                state["normalized_message"],
                state["request_id"],
            )
            return {"retrieved_context": result.context, "sources": result.sources}

        return _observe(
            parent,
            "retrieval",
            "retriever",
            operation,
            lambda result: {
                "status": "completed",
                "source_count": len(result["sources"]),
            },
        )

    def plan(state: ChatState) -> dict[str, object]:
        def operation() -> dict[str, object]:
            return {"tool_proposal": plan_tool(state["normalized_message"])}

        return _observe(
            parent,
            "tool",
            "tool",
            operation,
            lambda result: {
                "status": "completed",
                "proposed": result["tool_proposal"] is not None,
                "tool": result["tool_proposal"].action_type
                if result["tool_proposal"] is not None
                else None,
            },
        )

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


def build_chat_context_graph(retriever: Retriever, observation=None):
    """Run deterministic preparation, retrieval and tool planning before streaming."""

    parent = observation or NullObservation()

    def prepare(state: ChatState) -> dict[str, object]:
        def operation() -> dict[str, object]:
            message = state["message"].strip()
            if not message:
                raise ValueError("message must contain non-whitespace characters")
            return {
                "normalized_message": message,
                "conversation_id": state.get("conversation_id") or uuid4(),
            }

        return _observe(parent, "prepare", "chain", operation)

    def retrieve(state: ChatState) -> dict[str, object]:
        def operation() -> dict[str, object]:
            result = retriever(
                state["project_id"],
                state["user_id"],
                state["actor_admin"],
                state["normalized_message"],
                state["request_id"],
            )
            return {"retrieved_context": result.context, "sources": result.sources}

        return _observe(
            parent,
            "retrieval",
            "retriever",
            operation,
            lambda result: {
                "status": "completed",
                "source_count": len(result["sources"]),
            },
        )

    def plan(state: ChatState) -> dict[str, object]:
        def operation() -> dict[str, object]:
            return {"tool_proposal": plan_tool(state["normalized_message"])}

        return _observe(
            parent,
            "tool",
            "tool",
            operation,
            lambda result: {
                "status": "completed",
                "proposed": result["tool_proposal"] is not None,
                "tool": result["tool_proposal"].action_type
                if result["tool_proposal"] is not None
                else None,
            },
        )

    builder = StateGraph(ChatState)
    builder.add_node("prepare", prepare)
    builder.add_node("retrieve", retrieve)
    builder.add_node("plan", plan)
    builder.add_edge(START, "prepare")
    builder.add_edge("prepare", "retrieve")
    builder.add_edge("retrieve", "plan")
    builder.add_edge("plan", END)
    return builder.compile()


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
