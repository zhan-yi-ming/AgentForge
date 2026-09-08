from uuid import UUID, uuid4

from fastapi.testclient import TestClient

from agentforge_agent.api import (
    get_conversation_memory,
    get_responder,
    get_retrieval_service,
)
from agentforge_agent.context import ConversationMemory, TokenCounter
from agentforge_agent.errors import LlmDependencyError
from agentforge_agent.main import app
from agentforge_agent.retrieval import RetrievalResult
from agentforge_agent.schemas import ChatSource


client = TestClient(app)
TOKEN = "test-only-internal-token"


class FakeRetrievalService:
    def retrieve(self, project_id, user_id, actor_admin, query, request_id) -> RetrievalResult:
        if query == "unrelated":
            return RetrievalResult(context="", sources=[])
        source = ChatSource(
            source_type="WIKI",
            source_id=uuid4(),
            title="Architecture",
            excerpt="Java owns authentication and writes.",
        )
        return RetrievalResult(
            context="[WIKI:test] Architecture\nJava owns authentication and writes.",
            sources=[source],
        )


app.dependency_overrides[get_retrieval_service] = lambda: FakeRetrievalService()


def fake_llm_responder(state) -> str:
    return f"AI answer for: {state['context_bundle'].working.message}"


class FakeStreamingResponder:
    def __call__(self, state) -> str:
        return "".join(self.stream(state))

    def stream(self, state):
        yield "第一段"
        yield "，第二段"


def test_health_does_not_require_internal_token() -> None:
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "UP", "service": "agentforge-agent-service"}


def test_chat_rejects_missing_internal_token() -> None:
    response = client.post("/internal/v1/chat", json=chat_request())
    assert response.status_code == 401


def test_chat_rejects_wrong_internal_token() -> None:
    response = client.post(
        "/internal/v1/chat",
        headers={"X-AgentForge-Internal-Token": "wrong-internal-token"},
        json=chat_request(),
    )
    assert response.status_code == 401


def test_chat_runs_graph_and_creates_conversation_id() -> None:
    response = client.post(
        "/internal/v1/chat",
        headers={"X-AgentForge-Internal-Token": TOKEN},
        json=chat_request(message="  explain the architecture  "),
    )
    assert response.status_code == 200
    body = response.json()
    assert body["answer"].startswith("Relevant project context for 'explain the architecture':")
    assert body["sources"][0]["sourceType"] == "WIKI"
    assert body["requestId"] == "request-123"
    assert body["conversationId"]


def test_chat_uses_configured_llm_responder() -> None:
    app.dependency_overrides[get_responder] = lambda: fake_llm_responder
    try:
        response = client.post(
            "/internal/v1/chat",
            headers={"X-AgentForge-Internal-Token": TOKEN},
            json=chat_request(message="  explain the architecture  "),
        )
    finally:
        app.dependency_overrides.pop(get_responder, None)

    assert response.status_code == 200
    assert response.json()["answer"] == "AI answer for: explain the architecture"


def test_chat_stream_emits_metadata_deltas_and_complete_in_order() -> None:
    app.dependency_overrides[get_responder] = lambda: FakeStreamingResponder()
    try:
        response = client.post(
            "/internal/v1/chat/stream",
            headers={"X-AgentForge-Internal-Token": TOKEN},
            json=chat_request(message="  explain the architecture  "),
        )
    finally:
        app.dependency_overrides.pop(get_responder, None)

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("application/x-ndjson")
    events = [__import__("json").loads(line) for line in response.text.splitlines()]
    assert [event["type"] for event in events] == [
        "metadata",
        "delta",
        "delta",
        "complete",
    ]
    assert events[0]["sources"][0]["sourceType"] == "WIKI"
    assert [event["text"] for event in events[1:3]] == ["第一段", "，第二段"]
    assert events[-1]["toolProposal"] is None


def test_chat_sanitizes_llm_provider_failure() -> None:
    def failing_responder(state):
        raise LlmDependencyError("upstream body containing secret details")

    app.dependency_overrides[get_responder] = lambda: failing_responder
    try:
        response = client.post(
            "/internal/v1/chat",
            headers={"X-AgentForge-Internal-Token": TOKEN},
            json=chat_request(),
        )
    finally:
        app.dependency_overrides.pop(get_responder, None)

    assert response.status_code == 503
    assert response.json() == {"detail": "LLM provider is unavailable."}
    assert "secret details" not in response.text


def test_chat_preserves_conversation_id() -> None:
    conversation_id = str(uuid4())
    payload = chat_request()
    payload["conversationId"] = conversation_id
    response = client.post(
        "/internal/v1/chat",
        headers={"X-AgentForge-Internal-Token": TOKEN},
        json=payload,
    )
    assert response.status_code == 200
    assert response.json()["conversationId"] == conversation_id


def test_chat_reuses_only_completed_exchange_for_same_conversation() -> None:
    memory = ConversationMemory(
        recent_turns=1,
        summary_token_budget=200,
        max_sessions=10,
        token_counter=TokenCounter(),
    )
    seen = []

    def recording_responder(state):
        seen.append(state["context_bundle"].conversation)
        return f"answer-{len(seen)}"

    project_id = str(uuid4())
    user_id = str(uuid4())
    conversation_id = str(uuid4())
    app.dependency_overrides[get_conversation_memory] = lambda: memory
    app.dependency_overrides[get_responder] = lambda: recording_responder
    try:
        first = client.post(
            "/internal/v1/chat",
            headers={"X-AgentForge-Internal-Token": TOKEN},
            json=chat_request(
                message="first",
                project_id=project_id,
                user_id=user_id,
                conversation_id=conversation_id,
            ),
        )
        second = client.post(
            "/internal/v1/chat",
            headers={"X-AgentForge-Internal-Token": TOKEN},
            json=chat_request(
                message="second",
                project_id=project_id,
                user_id=user_id,
                conversation_id=conversation_id,
            ),
        )
        third = client.post(
            "/internal/v1/chat",
            headers={"X-AgentForge-Internal-Token": TOKEN},
            json=chat_request(
                message="third",
                project_id=project_id,
                user_id=user_id,
                conversation_id=conversation_id,
            ),
        )
    finally:
        app.dependency_overrides.pop(get_responder, None)
        app.dependency_overrides.pop(get_conversation_memory, None)

    assert first.status_code == 200
    assert second.status_code == 200
    assert third.status_code == 200
    assert seen[0].recent_messages == ()
    assert [(item.role, item.content) for item in seen[1].recent_messages] == [
        ("user", "first"),
        ("assistant", "answer-1"),
    ]
    assert seen[2].summary is not None
    assert "用户：first" in seen[2].summary
    assert "助手：answer-1" in seen[2].summary
    assert [(item.role, item.content) for item in seen[2].recent_messages] == [
        ("user", "second"),
        ("assistant", "answer-2"),
    ]


def test_chat_failure_does_not_commit_partial_exchange() -> None:
    memory = ConversationMemory(
        recent_turns=2,
        summary_token_budget=200,
        max_sessions=10,
        token_counter=TokenCounter(),
    )
    project_id = uuid4()
    user_id = uuid4()
    conversation_id = uuid4()

    def failing_responder(state):
        raise LlmDependencyError("sanitized test failure")

    app.dependency_overrides[get_conversation_memory] = lambda: memory
    app.dependency_overrides[get_responder] = lambda: failing_responder
    try:
        response = client.post(
            "/internal/v1/chat",
            headers={"X-AgentForge-Internal-Token": TOKEN},
            json=chat_request(
                project_id=str(project_id),
                user_id=str(user_id),
                conversation_id=str(conversation_id),
            ),
        )
    finally:
        app.dependency_overrides.pop(get_responder, None)
        app.dependency_overrides.pop(get_conversation_memory, None)

    assert response.status_code == 503
    assert memory.load(conversation_id, project_id, user_id).recent_messages == ()


def test_chat_rejects_conversation_id_reused_by_another_project() -> None:
    memory = ConversationMemory(
        recent_turns=2,
        summary_token_budget=200,
        max_sessions=10,
        token_counter=TokenCounter(),
    )
    conversation_id = str(uuid4())
    user_id = str(uuid4())
    app.dependency_overrides[get_conversation_memory] = lambda: memory
    app.dependency_overrides[get_responder] = lambda: fake_llm_responder
    try:
        first = client.post(
            "/internal/v1/chat",
            headers={"X-AgentForge-Internal-Token": TOKEN},
            json=chat_request(
                project_id=str(uuid4()),
                user_id=user_id,
                conversation_id=conversation_id,
            ),
        )
        second = client.post(
            "/internal/v1/chat",
            headers={"X-AgentForge-Internal-Token": TOKEN},
            json=chat_request(
                project_id=str(uuid4()),
                user_id=user_id,
                conversation_id=conversation_id,
            ),
        )
    finally:
        app.dependency_overrides.pop(get_responder, None)
        app.dependency_overrides.pop(get_conversation_memory, None)

    assert first.status_code == 200
    assert second.status_code == 422
    assert second.json() == {
        "detail": "conversation scope does not match project and user"
    }


def test_chat_returns_422_when_session_is_evicted_and_rebound_during_generation() -> None:
    memory = ConversationMemory(
        recent_turns=1,
        summary_token_budget=100,
        max_sessions=1,
        token_counter=TokenCounter(),
    )
    project_id = uuid4()
    other_project_id = uuid4()
    user_id = uuid4()
    conversation_id = uuid4()

    def rebinding_responder(state):
        memory.load(uuid4(), project_id, user_id)
        memory.load(conversation_id, other_project_id, user_id)
        return "answer generated from stale snapshot"

    app.dependency_overrides[get_conversation_memory] = lambda: memory
    app.dependency_overrides[get_responder] = lambda: rebinding_responder
    try:
        response = client.post(
            "/internal/v1/chat",
            headers={"X-AgentForge-Internal-Token": TOKEN},
            json=chat_request(
                project_id=str(project_id),
                user_id=str(user_id),
                conversation_id=str(conversation_id),
            ),
        )
    finally:
        app.dependency_overrides.pop(get_responder, None)
        app.dependency_overrides.pop(get_conversation_memory, None)

    assert response.status_code == 422
    assert "conversation" in response.json()["detail"]
    rebound = memory.load(conversation_id, other_project_id, user_id)
    assert rebound.recent_messages == ()


def test_chat_stream_commits_only_after_complete_generation() -> None:
    memory = ConversationMemory(
        recent_turns=2,
        summary_token_budget=200,
        max_sessions=10,
        token_counter=TokenCounter(),
    )
    project_id = str(uuid4())
    user_id = str(uuid4())
    conversation_id = str(uuid4())
    app.dependency_overrides[get_conversation_memory] = lambda: memory
    app.dependency_overrides[get_responder] = lambda: FakeStreamingResponder()
    try:
        response = client.post(
            "/internal/v1/chat/stream",
            headers={"X-AgentForge-Internal-Token": TOKEN},
            json=chat_request(
                message="stream question",
                project_id=project_id,
                user_id=user_id,
                conversation_id=conversation_id,
            ),
        )
    finally:
        app.dependency_overrides.pop(get_responder, None)
        app.dependency_overrides.pop(get_conversation_memory, None)

    assert response.status_code == 200
    assert '"type":"complete"' in response.text
    context = memory.load(
        UUID(conversation_id),
        UUID(project_id),
        UUID(user_id),
    )
    assert [(item.role, item.content) for item in context.recent_messages] == [
        ("user", "stream question"),
        ("assistant", "第一段，第二段"),
    ]


def test_chat_stream_failure_does_not_commit_partial_delta() -> None:
    class FailingStreamingResponder:
        def stream(self, state):
            yield "partial"
            raise LlmDependencyError("sanitized failure")

    memory = ConversationMemory(
        recent_turns=2,
        summary_token_budget=200,
        max_sessions=10,
        token_counter=TokenCounter(),
    )
    project_id = uuid4()
    user_id = uuid4()
    conversation_id = uuid4()
    app.dependency_overrides[get_conversation_memory] = lambda: memory
    app.dependency_overrides[get_responder] = lambda: FailingStreamingResponder()
    try:
        response = client.post(
            "/internal/v1/chat/stream",
            headers={"X-AgentForge-Internal-Token": TOKEN},
            json=chat_request(
                project_id=str(project_id),
                user_id=str(user_id),
                conversation_id=str(conversation_id),
            ),
        )
    finally:
        app.dependency_overrides.pop(get_responder, None)
        app.dependency_overrides.pop(get_conversation_memory, None)

    assert response.status_code == 200
    assert '"type":"error"' in response.text
    assert '"type":"complete"' not in response.text
    assert memory.load(conversation_id, project_id, user_id).recent_messages == ()


def test_chat_stream_emits_error_when_session_is_rebound_before_commit() -> None:
    memory = ConversationMemory(
        recent_turns=1,
        summary_token_budget=100,
        max_sessions=1,
        token_counter=TokenCounter(),
    )
    project_id = uuid4()
    other_project_id = uuid4()
    user_id = uuid4()
    conversation_id = uuid4()

    class RebindingStream:
        def stream(self, state):
            memory.load(uuid4(), project_id, user_id)
            memory.load(conversation_id, other_project_id, user_id)
            yield "stale answer"

    app.dependency_overrides[get_conversation_memory] = lambda: memory
    app.dependency_overrides[get_responder] = lambda: RebindingStream()
    try:
        response = client.post(
            "/internal/v1/chat/stream",
            headers={"X-AgentForge-Internal-Token": TOKEN},
            json=chat_request(
                project_id=str(project_id),
                user_id=str(user_id),
                conversation_id=str(conversation_id),
            ),
        )
    finally:
        app.dependency_overrides.pop(get_responder, None)
        app.dependency_overrides.pop(get_conversation_memory, None)

    assert response.status_code == 200
    assert '"type":"error"' in response.text
    assert '"type":"complete"' not in response.text
    assert memory.load(
        conversation_id, other_project_id, user_id
    ).recent_messages == ()


def test_chat_rejects_blank_message() -> None:
    response = client.post(
        "/internal/v1/chat",
        headers={"X-AgentForge-Internal-Token": TOKEN},
        json=chat_request(message="   "),
    )
    assert response.status_code == 422


def test_chat_returns_no_fabricated_sources_when_retrieval_is_empty() -> None:
    response = client.post(
        "/internal/v1/chat",
        headers={"X-AgentForge-Internal-Token": TOKEN},
        json=chat_request(message="unrelated"),
    )
    assert response.status_code == 200
    assert response.json()["sources"] == []
    assert response.json()["answer"].startswith("No relevant project context was found")


def test_chat_proposes_create_task_without_executing_it() -> None:
    response = client.post(
        "/internal/v1/chat",
        headers={"X-AgentForge-Internal-Token": TOKEN},
        json=chat_request(message="把登录模块的改造需求整理成任务，优先级设为高。"),
    )

    assert response.status_code == 200
    assert response.json()["toolProposal"] == {
        "actionType": "CREATE_TASK",
        "taskId": None,
        "expectedVersion": None,
        "title": "登录模块的改造需求",
        "description": "把登录模块的改造需求整理成任务，优先级设为高。",
        "status": "TODO",
        "priority": "HIGH",
    }


def test_chat_proposes_explicit_task_update() -> None:
    task_id = uuid4()
    response = client.post(
        "/internal/v1/chat",
        headers={"X-AgentForge-Internal-Token": TOKEN},
        json=chat_request(message=f"update task {task_id} version 3: status=DONE; priority=HIGH"),
    )

    assert response.status_code == 200
    assert response.json()["toolProposal"] == {
        "actionType": "UPDATE_TASK",
        "taskId": str(task_id),
        "expectedVersion": 3,
        "title": None,
        "description": None,
        "status": "DONE",
        "priority": "HIGH",
    }


def test_chat_does_not_propose_ambiguous_update() -> None:
    response = client.post(
        "/internal/v1/chat",
        headers={"X-AgentForge-Internal-Token": TOKEN},
        json=chat_request(message="update the login task to done"),
    )

    assert response.status_code == 200
    assert response.json()["toolProposal"] is None


def test_chat_does_not_fail_for_malformed_task_uuid() -> None:
    response = client.post(
        "/internal/v1/chat",
        headers={"X-AgentForge-Internal-Token": TOKEN},
        json=chat_request(
            message="update task 123456789012345678901234567890123456 version 3: status=DONE"
        ),
    )

    assert response.status_code == 200
    assert response.json()["toolProposal"] is None


def test_chat_does_not_fail_for_unsupported_tool_enum() -> None:
    response = client.post(
        "/internal/v1/chat",
        headers={"X-AgentForge-Internal-Token": TOKEN},
        json=chat_request(message="create task: Unsupported priority; priority=URGENT"),
    )

    assert response.status_code == 200
    assert response.json()["toolProposal"] is None


def chat_request(
    message: str = "hello",
    project_id: str | None = None,
    user_id: str | None = None,
    conversation_id: str | None = None,
) -> dict[str, object]:
    return {
        "projectId": project_id or str(uuid4()),
        "userId": user_id or str(uuid4()),
        "actorAdmin": False,
        "message": message,
        **({"conversationId": conversation_id} if conversation_id else {}),
        "requestId": "request-123",
    }
