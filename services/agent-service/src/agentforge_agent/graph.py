from collections.abc import Callable
from typing import TypedDict
from uuid import UUID, uuid4

from langgraph.graph import END, START, StateGraph


class ChatState(TypedDict, total=False):
    project_id: UUID
    user_id: UUID
    message: str
    normalized_message: str
    conversation_id: UUID
    request_id: str
    answer: str


Responder = Callable[[ChatState], str]


def deterministic_responder(state: ChatState) -> str:
    return f"Agent service received: {state['normalized_message']}"


def build_chat_graph(responder: Responder = deterministic_responder):
    def prepare(state: ChatState) -> dict[str, object]:
        message = state["message"].strip()
        if not message:
            raise ValueError("message must contain non-whitespace characters")
        return {
            "normalized_message": message,
            "conversation_id": state.get("conversation_id") or uuid4(),
        }

    def respond(state: ChatState) -> dict[str, str]:
        return {"answer": responder(state)}

    builder = StateGraph(ChatState)
    builder.add_node("prepare", prepare)
    builder.add_node("respond", respond)
    builder.add_edge(START, "prepare")
    builder.add_edge("prepare", "respond")
    builder.add_edge("respond", END)
    return builder.compile()
