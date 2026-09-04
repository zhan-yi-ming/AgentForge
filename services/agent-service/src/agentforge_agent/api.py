import hmac

from fastapi import APIRouter, Depends, Header, HTTPException, status

from .config import Settings, get_settings
from .graph import build_chat_graph
from .schemas import ChatRequest, ChatResponse, HealthResponse

router = APIRouter()
chat_graph = build_chat_graph()


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
def chat(request: ChatRequest) -> ChatResponse:
    try:
        state = chat_graph.invoke(
            {
                "project_id": request.project_id,
                "user_id": request.user_id,
                "message": request.message,
                "conversation_id": request.conversation_id,
                "request_id": request.request_id,
            }
        )
    except ValueError as exception:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exception)) from exception
    return ChatResponse(
        conversation_id=state["conversation_id"],
        answer=state["answer"],
        request_id=state["request_id"],
    )
