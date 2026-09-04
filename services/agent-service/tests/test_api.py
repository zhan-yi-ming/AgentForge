from uuid import uuid4

from fastapi.testclient import TestClient

from agentforge_agent.main import app


client = TestClient(app)
TOKEN = "test-only-internal-token"


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
    assert body["answer"] == "Agent service received: explain the architecture"
    assert body["requestId"] == "request-123"
    assert body["conversationId"]


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


def chat_request(message: str = "hello") -> dict[str, object]:
    return {
        "projectId": str(uuid4()),
        "userId": str(uuid4()),
        "message": message,
        "requestId": "request-123",
    }
