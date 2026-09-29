from uuid import uuid4

from fastapi.testclient import TestClient

from agentforge_agent.main import app


client = TestClient(app)
TOKEN = {"X-AgentForge-Internal-Token": "test-only-internal-token"}


def request():
    return {"entityId": str(uuid4()), "displayName": "Billing Service", "entityType": "SERVICE",
            "candidates": [{"entityId": str(uuid4()), "displayName": "Billing Service"},
                           {"entityId": str(uuid4()), "displayName": "Payments API"}]}


def test_resolution_requires_internal_token():
    assert client.post("/internal/v1/graph/resolution/suggest", json=request()).status_code == 401


def test_resolution_returns_only_bounded_candidate_ids_for_review():
    payload = request()
    response = client.post("/internal/v1/graph/resolution/suggest", headers=TOKEN, json=payload)
    assert response.status_code == 200
    result = response.json()
    assert result["reviewRequired"] is True
    assert result["recommendedCandidateId"] == payload["candidates"][0]["entityId"]
    assert 0 <= result["confidence"] <= 1
    assert set(c["entityId"] for c in result["candidates"]) == set(c["entityId"] for c in payload["candidates"])

class FakeModel:
    def __init__(self, content):
        self.content = content

    def invoke(self, messages):
        from types import SimpleNamespace
        return SimpleNamespace(content=self.content)


def test_model_cannot_introduce_candidate_outside_java_allowlist():
    from agentforge_agent.entity_resolution import resolution_model
    app.dependency_overrides[resolution_model] = lambda: FakeModel(
        '{"candidateId":"' + str(uuid4()) + '","confidence":1,"reason":"merge"}')
    try:
        response = client.post("/internal/v1/graph/resolution/suggest", headers=TOKEN, json=request())
        assert response.status_code == 200
        assert response.json()["recommendedCandidateId"] is None
        assert response.json()["confidence"] == 0
    finally:
        app.dependency_overrides.pop(resolution_model, None)


def test_model_failure_abstains_without_blocking_review():
    from agentforge_agent.entity_resolution import resolution_model
    app.dependency_overrides[resolution_model] = lambda: FakeModel("broken json")
    try:
        response = client.post("/internal/v1/graph/resolution/suggest", headers=TOKEN, json=request())
        assert response.status_code == 200
        assert response.json()["recommendedCandidateId"] is None
        assert response.json()["reviewRequired"] is True
    finally:
        app.dependency_overrides.pop(resolution_model, None)


def test_oversized_or_duplicate_candidates_are_rejected():
    payload = request()
    payload["candidates"] = payload["candidates"] * 11
    assert client.post("/internal/v1/graph/resolution/suggest", headers=TOKEN, json=payload).status_code == 422
    payload["candidates"] = [payload["candidates"][0]] * 2
    assert client.post("/internal/v1/graph/resolution/suggest", headers=TOKEN, json=payload).status_code == 422
