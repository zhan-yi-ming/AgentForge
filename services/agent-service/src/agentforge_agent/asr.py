"""Short-lived server-side Qwen realtime speech sessions."""

from base64 import b64encode
from dataclasses import dataclass, field
import json
from threading import Event, RLock, Thread
from time import monotonic
from uuid import UUID, uuid4

from pydantic import BaseModel, Field
from websockets.sync.client import connect

from .config import Settings


class AsrScope(BaseModel):
    project_id: UUID = Field(alias="projectId")
    user_id: UUID = Field(alias="userId")


class AsrUnavailable(Exception):
    pass


class AsrNotFound(Exception):
    pass


class AsrInvalidAudio(Exception):
    pass


@dataclass
class _Session:
    project_id: UUID
    user_id: UUID
    socket: object
    created_at: float = field(default_factory=monotonic)
    lock: RLock = field(default_factory=RLock)
    done: Event = field(default_factory=Event)
    ready: Event = field(default_factory=Event)
    items: dict[str, str] = field(default_factory=dict)
    bytes_sent: int = 0
    finished: bool = False
    failed: bool = False

    def snapshot(self, session_id: UUID) -> dict:
        with self.lock:
            return {
                "sessionId": str(session_id),
                "text": " ".join(text for text in self.items.values() if text).strip(),
                "finished": self.finished,
            }


class AsrService:
    MAX_CHUNK = 64_000
    MAX_TOTAL = 3_840_000  # two minutes of 16 kHz mono PCM16
    MAX_SESSIONS = 32
    MAX_AGE_SECONDS = 180
    READY_TIMEOUT = 5

    def __init__(self, settings: Settings, connector=connect):
        self.settings = settings
        self.connector = connector
        self.sessions: dict[UUID, _Session] = {}
        self.pending_users: set[UUID] = set()
        self.lock = RLock()

    def start(self, project_id: UUID, user_id: UUID) -> UUID:
        key = self.settings.asr_api_key
        workspace = self.settings.asr_workspace_id
        if key is None or not key.get_secret_value() or not workspace:
            raise AsrUnavailable()
        self._evict()
        with self.lock:
            if len(self.sessions) + len(self.pending_users) >= self.MAX_SESSIONS or user_id in self.pending_users or any(
                session.user_id == user_id for session in self.sessions.values()
            ):
                raise AsrUnavailable()
            self.pending_users.add(user_id)
        region = self.settings.asr_region
        host = "cn-beijing" if region == "cn-beijing" else "ap-southeast-1"
        url = f"wss://{workspace}.{host}.maas.aliyuncs.com/api-ws/v1/realtime?model={self.settings.asr_model}"
        socket = None
        try:
            socket = self.connector(url, additional_headers={"Authorization": f"Bearer {key.get_secret_value()}"},
                                    open_timeout=10, close_timeout=3)
            socket.send(json.dumps({
                "event_id": str(uuid4()), "type": "session.update",
                "session": {"input_audio_format": "pcm", "sample_rate": 16000,
                            "turn_detection": {"type": "server_vad", "threshold": 0.0,
                                               "silence_duration_ms": 400}},
            }))
        except Exception as exception:
            with self.lock:
                self.pending_users.discard(user_id)
            if socket is not None:
                try:
                    socket.close()
                except Exception:
                    pass
            raise AsrUnavailable() from exception
        session_id = uuid4()
        session = _Session(project_id, user_id, socket)
        with self.lock:
            self.sessions[session_id] = session
            self.pending_users.discard(user_id)
        Thread(target=self._receive, args=(session,), daemon=True).start()
        if not session.ready.wait(self.READY_TIMEOUT) or session.failed:
            self.cancel(session_id, project_id, user_id)
            raise AsrUnavailable()
        return session_id

    def append(self, session_id: UUID, project_id: UUID, user_id: UUID, audio: bytes) -> None:
        session = self._get(session_id, project_id, user_id)
        if not audio or len(audio) > self.MAX_CHUNK or len(audio) % 2:
            raise AsrInvalidAudio()
        with session.lock:
            if session.failed or session.finished or session.bytes_sent + len(audio) > self.MAX_TOTAL:
                raise AsrUnavailable()
            try:
                session.socket.send(json.dumps({"event_id": str(uuid4()), "type": "input_audio_buffer.append",
                                               "audio": b64encode(audio).decode("ascii")}))
            except Exception as exception:
                session.failed = True
                raise AsrUnavailable() from exception
            session.bytes_sent += len(audio)

    def status(self, session_id: UUID, project_id: UUID, user_id: UUID) -> dict:
        session = self._get(session_id, project_id, user_id)
        if session.failed:
            raise AsrUnavailable()
        return session.snapshot(session_id)

    def finish(self, session_id: UUID, project_id: UUID, user_id: UUID) -> dict:
        session = self._get(session_id, project_id, user_id)
        with session.lock:
            if session.failed:
                raise AsrUnavailable()
            if not session.finished:
                try:
                    session.socket.send(json.dumps({"event_id": str(uuid4()), "type": "session.finish"}))
                except Exception as exception:
                    session.failed = True
                    raise AsrUnavailable() from exception
        if not session.done.wait(15):
            raise AsrUnavailable()
        if session.failed:
            raise AsrUnavailable()
        snapshot = session.snapshot(session_id)
        with self.lock:
            self.sessions.pop(session_id, None)
        return snapshot

    def cancel(self, session_id: UUID, project_id: UUID, user_id: UUID) -> None:
        session = self._get(session_id, project_id, user_id)
        with self.lock:
            self.sessions.pop(session_id, None)
        try:
            session.socket.close()
        except Exception:
            pass

    def _get(self, session_id: UUID, project_id: UUID, user_id: UUID) -> _Session:
        with self.lock:
            session = self.sessions.get(session_id)
        if session is None or session.project_id != project_id or session.user_id != user_id:
            raise AsrNotFound()
        if monotonic() - session.created_at > self.MAX_AGE_SECONDS:
            with self.lock:
                self.sessions.pop(session_id, None)
            try:
                session.socket.close()
            except Exception:
                pass
            raise AsrNotFound()
        return session

    def _evict(self) -> None:
        with self.lock:
            expired = [(session_id, session) for session_id, session in self.sessions.items()
                       if monotonic() - session.created_at > self.MAX_AGE_SECONDS]
            for session_id, _ in expired:
                self.sessions.pop(session_id, None)
        for _, session in expired:
            try:
                session.socket.close()
            except Exception:
                pass

    @staticmethod
    def _receive(session: _Session) -> None:
        try:
            for message in session.socket:
                event = json.loads(message)
                kind = event.get("type")
                if kind == "session.updated":
                    session.ready.set()
                elif kind in ("conversation.item.input_audio_transcription.text",
                            "conversation.item.input_audio_transcription.completed"):
                    item_id = event.get("item_id")
                    if not isinstance(item_id, str):
                        continue
                    text = (event.get("transcript") if kind.endswith(".completed") else
                            str(event.get("text") or "") + str(event.get("stash") or ""))
                    if isinstance(text, str):
                        with session.lock:
                            session.items[item_id] = text[:8000]
                elif kind == "session.finished":
                    with session.lock:
                        session.finished = True
                    break
                elif kind in ("error", "conversation.item.input_audio_transcription.failed"):
                    with session.lock:
                        session.failed = True
                    break
        except Exception:
            with session.lock:
                session.failed = True
        finally:
            with session.lock:
                if not session.finished:
                    session.failed = True
            session.done.set()
            try:
                session.socket.close()
            except Exception:
                pass
