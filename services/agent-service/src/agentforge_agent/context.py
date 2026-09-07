from dataclasses import dataclass, replace
from uuid import UUID

from .retrieval import RetrievalResult
from .schemas import ChatSource, ToolProposal


@dataclass(frozen=True)
class WorkingContext:
    message: str


@dataclass(frozen=True)
class ConversationContext:
    conversation_id: UUID
    summary: str | None = None


@dataclass(frozen=True)
class ProjectContext:
    project_id: UUID
    user_id: UUID
    actor_admin: bool
    request_id: str


@dataclass(frozen=True)
class RetrievedContext:
    content: str = ""
    sources: tuple[ChatSource, ...] = ()


@dataclass(frozen=True)
class ToolContext:
    proposal: ToolProposal | None = None


@dataclass(frozen=True)
class ContextBundle:
    working: WorkingContext
    conversation: ConversationContext
    project: ProjectContext
    retrieved: RetrievedContext = RetrievedContext()
    tool: ToolContext = ToolContext()


class ContextManager:
    @staticmethod
    def build(
        *,
        project_id: UUID,
        user_id: UUID,
        actor_admin: bool,
        message: str,
        conversation_id: UUID,
        request_id: str,
    ) -> ContextBundle:
        normalized_message = message.strip()
        if not normalized_message:
            raise ValueError("message must contain non-whitespace characters")
        return ContextBundle(
            working=WorkingContext(message=normalized_message),
            conversation=ConversationContext(conversation_id=conversation_id),
            project=ProjectContext(
                project_id=project_id,
                user_id=user_id,
                actor_admin=actor_admin,
                request_id=request_id,
            ),
        )

    @staticmethod
    def with_retrieval(
        bundle: ContextBundle, result: RetrievalResult
    ) -> ContextBundle:
        return replace(
            bundle,
            retrieved=RetrievedContext(
                content=result.context,
                sources=tuple(result.sources),
            ),
        )

    @staticmethod
    def with_tool(
        bundle: ContextBundle, proposal: ToolProposal | None
    ) -> ContextBundle:
        return replace(bundle, tool=ToolContext(proposal=proposal))
