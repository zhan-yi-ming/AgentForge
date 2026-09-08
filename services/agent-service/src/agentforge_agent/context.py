from collections import OrderedDict
from collections.abc import Iterable
from dataclasses import dataclass, field, replace
from threading import RLock
from typing import Literal
from uuid import UUID, uuid4

from .retrieval import RetrievalResult
from .schemas import ChatSource, ToolProposal


@dataclass(frozen=True)
class WorkingContext:
    message: str


@dataclass(frozen=True)
class ConversationMessage:
    role: Literal["user", "assistant"]
    content: str


@dataclass(frozen=True)
class ConversationContext:
    conversation_id: UUID
    summary: str | None = None
    recent_messages: tuple[ConversationMessage, ...] = ()
    session_generation: UUID | None = field(
        default=None,
        repr=False,
        compare=False,
    )


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


class TokenCounter:
    """UTF-8 byte upper bound for provider-independent hard prompt budgets."""

    def count_text(self, value: str) -> int:
        return len(value.encode("utf-8"))

    def truncate_text(self, value: str, budget: int) -> str:
        if budget <= 0:
            return ""
        if self.count_text(value) <= budget:
            return value
        low, high = 0, len(value)
        while low < high:
            middle = (low + high + 1) // 2
            if self.count_text(value[:middle]) <= budget:
                low = middle
            else:
                high = middle - 1
        return value[:low].rstrip()

    def count_messages(self, contents: Iterable[str]) -> int:
        return sum(self.count_text(content) + 16 for content in contents)


@dataclass
class _ConversationSession:
    project_id: UUID
    user_id: UUID
    generation: UUID = field(default_factory=uuid4)
    recent_messages: list[ConversationMessage] = field(default_factory=list)
    summary_messages: list[ConversationMessage] = field(default_factory=list)


class ConversationMemory:
    def __init__(
        self,
        *,
        recent_turns: int,
        summary_token_budget: int,
        max_sessions: int,
        token_counter: TokenCounter,
        message_token_budget: int = 8192,
    ) -> None:
        if (
            recent_turns < 1
            or summary_token_budget < 1
            or max_sessions < 1
            or message_token_budget < 1
        ):
            raise ValueError("conversation memory limits must be positive")
        self._recent_message_limit = recent_turns * 2
        self._summary_token_budget = summary_token_budget
        self._max_sessions = max_sessions
        self._token_counter = token_counter
        self._message_token_budget = message_token_budget
        self._sessions: OrderedDict[UUID, _ConversationSession] = OrderedDict()
        self._lock = RLock()

    def load(
        self,
        conversation_id: UUID,
        project_id: UUID,
        user_id: UUID,
    ) -> ConversationContext:
        with self._lock:
            session = self._session(conversation_id, project_id, user_id)
            return ConversationContext(
                conversation_id=conversation_id,
                summary=self._render_summary(session.summary_messages),
                recent_messages=tuple(session.recent_messages),
                session_generation=session.generation,
            )

    def commit_exchange(
        self,
        conversation_id: UUID,
        project_id: UUID,
        user_id: UUID,
        user_message: str,
        assistant_message: str,
        expected_session_generation: UUID | None = None,
    ) -> None:
        normalized_user = user_message.strip()
        normalized_assistant = assistant_message.strip()
        if not normalized_user or not normalized_assistant:
            raise ValueError("completed conversation messages must not be blank")
        normalized_user = self._token_counter.truncate_text(
            normalized_user,
            self._message_token_budget,
        )
        normalized_assistant = self._token_counter.truncate_text(
            normalized_assistant,
            self._message_token_budget,
        )
        with self._lock:
            session = self._session_for_commit(
                conversation_id,
                project_id,
                user_id,
                expected_session_generation,
            )
            session.recent_messages.extend(
                (
                    ConversationMessage("user", normalized_user),
                    ConversationMessage("assistant", normalized_assistant),
                )
            )
            overflow = len(session.recent_messages) - self._recent_message_limit
            if overflow > 0:
                session.summary_messages.extend(session.recent_messages[:overflow])
                del session.recent_messages[:overflow]
                self._trim_summary(session)

    def _session_for_commit(
        self,
        conversation_id: UUID,
        project_id: UUID,
        user_id: UUID,
        expected_generation: UUID | None,
    ) -> _ConversationSession:
        if expected_generation is None:
            return self._session(conversation_id, project_id, user_id)
        session = self._sessions.get(conversation_id)
        if (
            session is None
            or session.project_id != project_id
            or session.user_id != user_id
            or session.generation != expected_generation
        ):
            raise ValueError("conversation changed before completion")
        self._sessions.move_to_end(conversation_id)
        return session

    def _session(
        self,
        conversation_id: UUID,
        project_id: UUID,
        user_id: UUID,
    ) -> _ConversationSession:
        session = self._sessions.get(conversation_id)
        if session is None:
            if len(self._sessions) >= self._max_sessions:
                self._sessions.popitem(last=False)
            session = _ConversationSession(project_id=project_id, user_id=user_id)
            self._sessions[conversation_id] = session
        elif session.project_id != project_id or session.user_id != user_id:
            raise ValueError("conversation scope does not match project and user")
        else:
            self._sessions.move_to_end(conversation_id)
        return session

    def _trim_summary(self, session: _ConversationSession) -> None:
        while (
            len(session.summary_messages) > 1
            and self._token_counter.count_text(
                self._render_summary(session.summary_messages) or ""
            )
            > self._summary_token_budget
        ):
            session.summary_messages.pop(0)
        rendered = self._render_summary(session.summary_messages)
        if rendered and self._token_counter.count_text(rendered) > self._summary_token_budget:
            message = session.summary_messages[-1]
            prefix = self._summary_prefix(message)
            content_budget = max(
                1,
                self._summary_token_budget - self._token_counter.count_text(prefix),
            )
            session.summary_messages[:] = [
                replace(
                    message,
                    content=self._token_counter.truncate_text(
                        message.content, content_budget
                    ),
                )
            ]

    @staticmethod
    def _summary_prefix(message: ConversationMessage) -> str:
        return "用户：" if message.role == "user" else "助手："

    def _render_summary(
        self, messages: list[ConversationMessage]
    ) -> str | None:
        if not messages:
            return None
        return "\n".join(
            f"{self._summary_prefix(message)}{message.content}" for message in messages
        )


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
        conversation: ConversationContext | None = None,
    ) -> ContextBundle:
        normalized_message = message.strip()
        if not normalized_message:
            raise ValueError("message must contain non-whitespace characters")
        if (
            conversation is not None
            and conversation.conversation_id != conversation_id
        ):
            raise ValueError("conversation id does not match loaded context")
        return ContextBundle(
            working=WorkingContext(message=normalized_message),
            conversation=conversation
            or ConversationContext(conversation_id=conversation_id),
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
