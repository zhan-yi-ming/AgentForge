from queue import Queue
from time import sleep
from uuid import uuid4
import json

from fastapi.testclient import TestClient

from agentforge_agent.api import get_asr_service
from agentforge_agent.asr import AsrService
from agentforge_agent.config import Settings
from agentforge_agent.main import app


class FakeSocket:
    def __init__(self):
        self.events = Queue()
        self.sent = []

    def send(self, message):
        event = json.loads(message)
        self.sent.append(event["type"])
        if event["type"] == "session.update":
            self.events.put(json.dumps({"type": "session.updated"}))
        if event["type"] == "input_audio_buffer.append":
            self.events.put(json.dumps({"type": "conversation.item.input_audio_transcription.text",
                                        "item_id": "item-1", "text": "记录", "stash": "回归任务"}))
        if event["type"] == "session.finish":
            self.events.put(json.dumps({"type": "conversation.item.input_audio_transcription.completed",
                                        "item_id": "item-1", "transcript": "记录回归任务"}))
            self.events.put(json.dumps({"type": "session.finished"}))

    def __iter__(self):
        while True:
            event = self.events.get(timeout=2)
            if event is None:
                break
            yield event

    def close(self):
        self.events.put(None)


def test_realtime_asr_scope_preview_and_final_text_without_key_leak():
    socket = FakeSocket()
    service = AsrService(Settings(asr_api_key="test-only-asr-key", asr_workspace_id="workspace"),
                         connector=lambda *args, **kwargs: socket)
    app.dependency_overrides[get_asr_service] = lambda: service
    client = TestClient(app)
    headers = {"X-AgentForge-Internal-Token": "test-only-internal-token"}
    scope = {"projectId": str(uuid4()), "userId": str(uuid4())}
    try:
        opened = client.post("/internal/v1/asr/sessions", json=scope, headers=headers)
        assert opened.status_code == 200
        session_id = opened.json()["sessionId"]
        assert client.post("/internal/v1/asr/sessions", json=scope, headers=headers).status_code == 503
        path = f"/internal/v1/asr/sessions/{session_id}"
        assert client.post(f"{path}/audio", params=scope, content=b"\x01\x02", headers=headers).status_code == 204
        for _ in range(100):
            preview = client.get(path, params=scope, headers=headers)
            if preview.json()["text"] == "记录回归任务":
                break
            sleep(0.01)
        assert preview.json() == {"sessionId": session_id, "text": "记录回归任务", "finished": False}
        assert client.get(path, params={**scope, "userId": str(uuid4())}, headers=headers).status_code == 404
        assert client.post(f"{path}/audio", params=scope, content=b"\x01", headers=headers).status_code == 400
        final = client.post(f"{path}/finish", json=scope, headers=headers)
        assert final.json() == {"sessionId": session_id, "text": "记录回归任务", "finished": True}
        assert "test-only-asr-key" not in final.text
        assert socket.sent == ["session.update", "input_audio_buffer.append", "session.finish"]
        assert client.delete(path, params=scope, headers=headers).status_code == 404
    finally:
        app.dependency_overrides.pop(get_asr_service, None)


def test_asr_does_not_open_browser_session_before_provider_accepts_configuration():
    class NoAckSocket(FakeSocket):
        def send(self, message):
            self.sent.append(json.loads(message)["type"])

    service = AsrService(Settings(asr_api_key="test-only-asr-key", asr_workspace_id="workspace"),
                         connector=lambda *args, **kwargs: NoAckSocket())
    service.READY_TIMEOUT = 0.02
    app.dependency_overrides[get_asr_service] = lambda: service
    client = TestClient(app)
    try:
        response = client.post("/internal/v1/asr/sessions", json={"projectId": str(uuid4()),
                               "userId": str(uuid4())},
                               headers={"X-AgentForge-Internal-Token": "test-only-internal-token"})
        assert response.status_code == 503
        assert response.json()["detail"] == "Voice recognition is unavailable."
    finally:
        app.dependency_overrides.pop(get_asr_service, None)


def test_expired_voice_session_returns_not_found_and_closes_socket(monkeypatch):
    from time import monotonic
    from agentforge_agent import asr

    socket = FakeSocket()
    service = AsrService(Settings(asr_api_key="test-only-asr-key", asr_workspace_id="workspace"),
                         connector=lambda *args, **kwargs: socket)
    app.dependency_overrides[get_asr_service] = lambda: service
    client = TestClient(app)
    headers = {"X-AgentForge-Internal-Token": "test-only-internal-token"}
    scope = {"projectId": str(uuid4()), "userId": str(uuid4())}
    try:
        opened = client.post("/internal/v1/asr/sessions", json=scope, headers=headers)
        session_id = opened.json()["sessionId"]
        monkeypatch.setattr(asr, "monotonic", lambda: monotonic() + 181)
        expired = client.get(f"/internal/v1/asr/sessions/{session_id}", params=scope, headers=headers)
        assert expired.status_code == 404
        assert service.sessions == {}
    finally:
        app.dependency_overrides.pop(get_asr_service, None)


def test_finished_voice_session_allows_immediate_new_recording_without_delete():
    sockets = [FakeSocket(), FakeSocket()]
    service = AsrService(Settings(asr_api_key="test-only-asr-key", asr_workspace_id="workspace"),
                         connector=lambda *args, **kwargs: sockets.pop(0))
    app.dependency_overrides[get_asr_service] = lambda: service
    client = TestClient(app)
    headers = {"X-AgentForge-Internal-Token": "test-only-internal-token"}
    scope = {"projectId": str(uuid4()), "userId": str(uuid4())}
    try:
        first = client.post("/internal/v1/asr/sessions", json=scope, headers=headers).json()["sessionId"]
        finished = client.post(f"/internal/v1/asr/sessions/{first}/finish", json=scope, headers=headers)
        assert finished.status_code == 200
        second = client.post("/internal/v1/asr/sessions", json=scope, headers=headers)
        assert second.status_code == 200
        assert second.json()["sessionId"] != first
        client.delete(f"/internal/v1/asr/sessions/{second.json()['sessionId']}", params=scope, headers=headers)
    finally:
        app.dependency_overrides.pop(get_asr_service, None)
