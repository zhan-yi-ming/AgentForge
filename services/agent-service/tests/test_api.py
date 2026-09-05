from uuid import uuid4

from fastapi.testclient import TestClient

from agentforge_agent.api import get_responder, get_retrieval_service
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
    return f"AI answer for: {state['normalized_message']}"


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


def chat_request(message: str = "hello") -> dict[str, object]:
    return {
        "projectId": str(uuid4()),
        "userId": str(uuid4()),
        "actorAdmin": False,
        "message": message,
        "requestId": "request-123",
    }
