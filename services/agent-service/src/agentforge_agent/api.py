from functools import lru_cache
import hmac
import json
from uuid import uuid4

from fastapi import APIRouter, Depends, Header, HTTPException, status
from fastapi.responses import StreamingResponse

from .config import Settings, get_settings
from .errors import LlmDependencyError, RagDependencyError
from .graph import build_chat_context_graph, build_chat_graph
from .llm import build_responder
from .observability import build_observability
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


@lru_cache
def get_observability():
    return build_observability(get_settings())


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
    observability=Depends(get_observability),
) -> ChatResponse:
    thread_id = request.conversation_id or uuid4()
    request_observation = observability.start_request(
        request.request_id, thread_id, request.project_id
    )
    agent_observation = request_observation.child("agent", "agent")
    try:
        try:
            chat_graph = build_chat_graph(
                retrieval_service.retrieve,
                responder,
                observation=agent_observation,
            )
            state = chat_graph.invoke(
                {
                    "project_id": request.project_id,
                    "user_id": request.user_id,
                    "actor_admin": request.actor_admin,
                    "message": request.message,
                    "conversation_id": thread_id,
                    "request_id": request.request_id,
                }
            )
            agent_observation.update(output={"status": "completed"})
            request_observation.update(output={"status": "completed"})
        except Exception as exception:
            agent_observation.fail(exception)
            request_observation.fail(exception)
            raise
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
    finally:
        agent_observation.end()
        request_observation.end()
    return ChatResponse(
        conversation_id=state["conversation_id"],
        answer=state["answer"],
        request_id=state["request_id"],
        sources=state.get("sources", []),
        tool_proposal=state.get("tool_proposal"),
    )


@router.post(
    "/internal/v1/chat/stream",
    dependencies=[Depends(require_internal_token)],
)
def chat_stream(
    request: ChatRequest,
    retrieval_service: RetrievalService | DisabledRetrievalService = Depends(get_retrieval_service),
    responder=Depends(get_responder),
    observability=Depends(get_observability),
) -> StreamingResponse:
    thread_id = request.conversation_id or uuid4()
    request_observation = observability.start_request(
        request.request_id, thread_id, request.project_id
    )
    agent_observation = request_observation.child("agent", "agent")
    try:
        state = build_chat_context_graph(
            retrieval_service.retrieve,
            observation=agent_observation,
        ).invoke(
            {
                "project_id": request.project_id,
                "user_id": request.user_id,
                "actor_admin": request.actor_admin,
                "message": request.message,
                "conversation_id": thread_id,
                "request_id": request.request_id,
            }
        )
    except ValueError as exception:
        _fail_and_end(request_observation, agent_observation, exception)
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exception)) from exception
    except RagDependencyError as exception:
        _fail_and_end(request_observation, agent_observation, exception)
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="RAG dependencies are unavailable.",
        ) from exception
    except Exception as exception:
        _fail_and_end(request_observation, agent_observation, exception)
        raise

    def encode(event: dict[str, object]) -> str:
        return json.dumps(event, ensure_ascii=False, separators=(",", ":")) + "\n"

    def events():
        generation_observation = agent_observation.child("llm", "generation")
        try:
            yield encode(
                {
                    "type": "metadata",
                    "conversationId": str(state["conversation_id"]),
                    "requestId": state["request_id"],
                    "sources": [
                        source.model_dump(mode="json", by_alias=True)
                        for source in state.get("sources", [])
                    ],
                }
            )
            observed_stream = getattr(responder, "stream_observed", None)
            stream = getattr(responder, "stream", None)
            if callable(observed_stream):
                chunks = observed_stream(state, generation_observation)
            else:
                chunks = stream(state) if callable(stream) else (responder(state),)
            for chunk in chunks:
                if isinstance(chunk, str) and chunk:
                    yield encode({"type": "delta", "text": chunk})
            proposal = state.get("tool_proposal")
            yield encode(
                {
                    "type": "complete",
                    "toolProposal": proposal.model_dump(mode="json", by_alias=True)
                    if proposal is not None
                    else None,
                }
            )
            generation_observation.update(output={"status": "completed"})
            agent_observation.update(output={"status": "completed"})
            request_observation.update(output={"status": "completed"})
        except LlmDependencyError as exception:
            generation_observation.fail(exception)
            agent_observation.fail(exception)
            request_observation.fail(exception)
            yield encode({"type": "error", "message": "LLM provider is unavailable."})
        except Exception as exception:
            generation_observation.fail(exception)
            agent_observation.fail(exception)
            request_observation.fail(exception)
            raise
        finally:
            generation_observation.end()
            agent_observation.end()
            request_observation.end()

    return StreamingResponse(events(), media_type="application/x-ndjson")


def _fail_and_end(request_observation, agent_observation, exception: Exception) -> None:
    agent_observation.fail(exception)
    request_observation.fail(exception)
    agent_observation.end()
    request_observation.end()
