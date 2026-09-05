from functools import lru_cache
import hmac

from fastapi import APIRouter, Depends, Header, HTTPException, status

from .config import Settings, get_settings
from .errors import LlmDependencyError, RagDependencyError
from .graph import build_chat_graph
from .llm import build_responder
from .retrieval import DisabledRetrievalService, RetrievalService
from .schemas import ChatRequest, ChatResponse, HealthResponse

router = APIRouter()


@lru_cache
def get_retrieval_service() -> RetrievalService | DisabledRetrievalService:
    settings = get_settings()
    if not settings.rag_enabled:
        return DisabledRetrievalService()
    return RetrievalService.from_settings(settings)


@lru_cache
def get_responder():
    try:
        return build_responder(get_settings())
    except LlmDependencyError as exception:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="LLM provider is unavailable.",
        ) from exception


def require_internal_token(
    token: str | None = Header(default=None, alias="X-AgentForge-Internal-Token"),
    settings: Settings = Depends(get_settings),
) -> None:
    expected = settings.internal_token.get_secret_value()
    if token is None or not hmac.compare_digest(token, expected):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid internal credentials.")


@router.get("/health", response_model=HealthResponse)
def health(settings: Settings = Depends(get_settings)) -> HealthResponse:
    return HealthResponse(status="UP", service=settings.service_name)


@router.post(
    "/internal/v1/chat",
    response_model=ChatResponse,
    dependencies=[Depends(require_internal_token)],
)
def chat(
    request: ChatRequest,
    retrieval_service: RetrievalService | DisabledRetrievalService = Depends(get_retrieval_service),
    responder=Depends(get_responder),
) -> ChatResponse:
    try:
        chat_graph = build_chat_graph(retrieval_service.retrieve, responder)
        state = chat_graph.invoke(
            {
                "project_id": request.project_id,
                "user_id": request.user_id,
                "actor_admin": request.actor_admin,
                "message": request.message,
                "conversation_id": request.conversation_id,
                "request_id": request.request_id,
            }
        )
    except ValueError as exception:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exception)) from exception
    except RagDependencyError as exception:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="RAG dependencies are unavailable.",
        ) from exception
    except LlmDependencyError as exception:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="LLM provider is unavailable.",
        ) from exception
    return ChatResponse(
        conversation_id=state["conversation_id"],
        answer=state["answer"],
        request_id=state["request_id"],
        sources=state.get("sources", []),
        tool_proposal=state.get("tool_proposal"),
    )
