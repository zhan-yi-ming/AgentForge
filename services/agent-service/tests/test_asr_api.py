from uuid import uuid4

from fastapi.testclient import TestClient

from agentforge_agent.api import get_asr_service
from agentforge_agent.main import app


class FakeAsrService:
    def __init__(self):
        self.scope = None
        self.audio = None

    def start(self, project_id, user_id):
        self.scope = (project_id, user_id)
        return uuid4()

    def append(self, session_id, project_id, user_id, audio):
        self.audio = audio

    def status(self, session_id, project_id, user_id):
        return {"sessionId": str(session_id), "text": "记录回归任务", "finished": False}

    def finish(self, session_id, project_id, user_id):
        return {"sessionId": str(session_id), "text": "记录回归任务", "finished": True}

    def cancel(self, session_id, project_id, user_id):
        return None


def test_asr_audio_and_transcript_use_internal_auth_and_scope():
    fake = FakeAsrService()
    app.dependency_overrides[get_asr_service] = lambda: fake
    client = TestClient(app)
    project_id, user_id = uuid4(), uuid4()
    scope = {"projectId": str(project_id), "userId": str(user_id)}
    try:
        assert client.post("/internal/v1/asr/sessions", json=scope).status_code == 401
        headers = {"X-AgentForge-Internal-Token": "test-only-internal-token"}
        opened = client.post("/internal/v1/asr/sessions", json=scope, headers=headers)
        assert opened.status_code == 200
        session_id = opened.json()["sessionId"]
        audio = client.post(
            f"/internal/v1/asr/sessions/{session_id}/audio",
            params=scope, content=b"\x01\x02", headers={**headers, "Content-Type": "application/octet-stream"},
        )
        assert audio.status_code == 204
        assert fake.scope == (project_id, user_id)
        assert fake.audio == b"\x01\x02"
        current = client.get(f"/internal/v1/asr/sessions/{session_id}", params=scope, headers=headers)
        assert current.json() == {"sessionId": session_id, "text": "记录回归任务", "finished": False}
        finished = client.post(f"/internal/v1/asr/sessions/{session_id}/finish", json=scope, headers=headers)
        assert finished.json()["finished"] is True
    finally:
        app.dependency_overrides.pop(get_asr_service, None)


def test_asr_audio_rejects_oversized_request_before_appending():
    fake = FakeAsrService()
    app.dependency_overrides[get_asr_service] = lambda: fake
    client = TestClient(app)
    try:
        response = client.post(
            f"/internal/v1/asr/sessions/{uuid4()}/audio",
            params={"projectId": str(uuid4()), "userId": str(uuid4())},
            content=b"x" * 64_002,
            headers={"X-AgentForge-Internal-Token": "test-only-internal-token",
                     "Content-Type": "application/octet-stream"},
        )
        assert response.status_code == 400
        assert fake.audio is None
    finally:
        app.dependency_overrides.pop(get_asr_service, None)


def test_asr_audio_runs_blocking_transport_outside_event_loop():
    import asyncio

    class ThreadCheckedService(FakeAsrService):
        on_event_loop = None

        def append(self, session_id, project_id, user_id, audio):
            try:
                asyncio.get_running_loop()
            except RuntimeError:
                self.on_event_loop = False
            else:
                self.on_event_loop = True

    fake = ThreadCheckedService()
    app.dependency_overrides[get_asr_service] = lambda: fake
    client = TestClient(app)
    try:
        response = client.post(
            f"/internal/v1/asr/sessions/{uuid4()}/audio",
            params={"projectId": str(uuid4()), "userId": str(uuid4())},
            content=b"\x01\x02",
            headers={"X-AgentForge-Internal-Token": "test-only-internal-token",
                     "Content-Type": "application/octet-stream"},
        )
        assert response.status_code == 204
        assert fake.on_event_loop is False
    finally:
        app.dependency_overrides.pop(get_asr_service, None)
