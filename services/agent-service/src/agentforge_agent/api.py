from functools import lru_cache
import hmac
import json
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, Header, HTTPException, Request, status
from fastapi.responses import StreamingResponse

from .config import Settings, get_settings
from .action_runtime import (
    ActionWorkflowConflict,
    ActionWorkflowNotFound,
    ActionWorkflowRuntime,
)
from .context import ConversationMemory, MemoryNamespace, TokenCounter
from .errors import LlmDependencyError, RagDependencyError
from .graph import build_chat_context_graph, build_chat_graph
from .llm import build_responder
from .observability import build_observability
from .retrieval import DisabledRetrievalService, RetrievalService
from .schemas import (
    ChatRequest,
    ChatResponse,
    HealthResponse,
    ResumeRequest,
    ResumeResponse,
)

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


@lru_cache
def get_conversation_memory() -> ConversationMemory:
    settings = get_settings()
    return ConversationMemory(
        recent_turns=settings.context_recent_turns,
        summary_token_budget=settings.context_summary_token_budget,
        max_sessions=settings.context_max_sessions,
        token_counter=TokenCounter(),
        message_token_budget=settings.context_token_budget,
    )


def get_action_runtime(request: Request) -> ActionWorkflowRuntime:
    return request.app.state.action_runtime


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
    conversation_memory: ConversationMemory = Depends(get_conversation_memory),
    action_runtime: ActionWorkflowRuntime = Depends(get_action_runtime),
    settings: Settings = Depends(get_settings),
) -> ChatResponse:
    thread_id = request.conversation_id or uuid4()
    namespace = _memory_namespace(settings, request, thread_id)
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
                conversation_memory=conversation_memory,
            )
            state = chat_graph.invoke(
                {
                    "namespace": namespace,
                    "actor_admin": request.actor_admin,
                    "message": request.message,
                    "request_id": request.request_id,
                }
            )
            bundle = state["context_bundle"]
            if bundle.tool.proposal is not None:
                action_runtime.interrupt(
                    namespace,
                    bundle.tool.proposal,
                    bundle.project.request_id,
                )
            conversation_memory.commit_exchange(
                bundle.conversation.lease,
                bundle.working.message,
                state["answer"],
            )
            agent_observation.update(output={"status": "completed"})
            request_observation.update(output={"status": "completed"})
        except Exception as exception:
            agent_observation.fail(exception)
            request_observation.fail(exception)
            raise
    except ActionWorkflowConflict as exception:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exception)) from exception
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
        conversation_id=bundle.conversation.conversation_id,
        answer=state["answer"],
        request_id=bundle.project.request_id,
        sources=list(bundle.retrieved.sources),
        tool_proposal=bundle.tool.proposal,
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
    conversation_memory: ConversationMemory = Depends(get_conversation_memory),
    action_runtime: ActionWorkflowRuntime = Depends(get_action_runtime),
    settings: Settings = Depends(get_settings),
) -> StreamingResponse:
    thread_id = request.conversation_id or uuid4()
    namespace = _memory_namespace(settings, request, thread_id)
    request_observation = observability.start_request(
        request.request_id, thread_id, request.project_id
    )
    agent_observation = request_observation.child("agent", "agent")
    try:
        state = build_chat_context_graph(
            retrieval_service.retrieve,
            observation=agent_observation,
            conversation_memory=conversation_memory,
        ).invoke(
            {
                "namespace": namespace,
                "actor_admin": request.actor_admin,
                "message": request.message,
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
        answer_parts: list[str] = []
        try:
            yield encode(
                {
                    "type": "metadata",
                    "conversationId": str(
                        state["context_bundle"].conversation.conversation_id
                    ),
                    "requestId": state["context_bundle"].project.request_id,
                    "sources": [
                        source.model_dump(mode="json", by_alias=True)
                        for source in state["context_bundle"].retrieved.sources
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
                    answer_parts.append(chunk)
                    yield encode({"type": "delta", "text": chunk})
            bundle = state["context_bundle"]
            proposal = bundle.tool.proposal
            if proposal is not None:
                action_runtime.interrupt(
                    namespace,
                    proposal,
                    bundle.project.request_id,
                )
            conversation_memory.commit_exchange(
                bundle.conversation.lease,
                bundle.working.message,
                "".join(answer_parts),
            )
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
        except ActionWorkflowConflict as exception:
            generation_observation.fail(exception)
            agent_observation.fail(exception)
            request_observation.fail(exception)
            yield encode(
                {
                    "type": "error",
                    "message": "Another tool action is already waiting for this conversation.",
                }
            )
        except ValueError as exception:
            generation_observation.fail(exception)
            agent_observation.fail(exception)
            request_observation.fail(exception)
            yield encode(
                {
                    "type": "error",
                    "message": "Conversation context changed before completion.",
                }
            )
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


@router.post(
    "/internal/v1/agent/resume",
    response_model=ResumeResponse,
    dependencies=[Depends(require_internal_token)],
)
def resume_action(
    request: ResumeRequest,
    action_runtime: ActionWorkflowRuntime = Depends(get_action_runtime),
    settings: Settings = Depends(get_settings),
) -> ResumeResponse:
    namespace = MemoryNamespace(
        tenant_id=settings.namespace_tenant,
        workspace_id=settings.namespace_workspace,
        project_id=request.project_id,
        user_id=request.user_id,
        thread_id=request.conversation_id,
    )
    try:
        resumed = action_runtime.resume(
            namespace,
            action_id=request.action_id,
            decision=request.decision,
            idempotency_key=request.idempotency_key,
            request_id=request.request_id,
        )
    except ActionWorkflowNotFound as exception:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exception)) from exception
    except ActionWorkflowConflict as exception:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exception)) from exception
    return ResumeResponse(
        conversation_id=resumed.conversation_id,
        action_id=resumed.action_id,
        decision=resumed.decision,
        status="RESUMED",
        request_id=resumed.request_id,
    )


def _fail_and_end(request_observation, agent_observation, exception: Exception) -> None:
    agent_observation.fail(exception)
    request_observation.fail(exception)
    agent_observation.end()
    request_observation.end()


def _memory_namespace(
    settings: Settings,
    request: ChatRequest,
    thread_id: UUID,
) -> MemoryNamespace:
    return MemoryNamespace(
        tenant_id=settings.namespace_tenant,
        workspace_id=settings.namespace_workspace,
        project_id=request.project_id,
        user_id=request.user_id,
        thread_id=thread_id,
    )
