from __future__ import annotations

from collections.abc import Callable
from typing import Any
from uuid import UUID


class Observation:
    def __init__(self, client: Any, span: Any) -> None:
        self._client = client
        self._span = span
        self.trace_id = span.trace_id
        self.observation_id = span.id
        self._ended = False

    def child(
        self,
        name: str,
        as_type: str = "span",
        metadata: dict[str, object] | None = None,
    ) -> "Observation":
        values: dict[str, object] = {
            "name": name,
            "as_type": as_type,
            "trace_context": {
                "trace_id": self.trace_id,
                "parent_span_id": self.observation_id,
            },
        }
        if metadata:
            values["metadata"] = metadata
        try:
            return Observation(self._client, self._client.start_observation(**values))
        except Exception:
            return NullObservation()

    def update(self, **values: object) -> None:
        if not self._ended and values:
            try:
                self._span.update(**values)
            except Exception:
                return

    def fail(self, exception: Exception) -> None:
        self.update(level="ERROR", status_message=type(exception).__name__)

    def end(self) -> None:
        if not self._ended:
            self._ended = True
            try:
                self._span.end()
            except Exception:
                return


class NullObservation:
    def child(
        self,
        name: str,
        as_type: str = "span",
        metadata: dict[str, object] | None = None,
    ) -> "NullObservation":
        return self

    def update(self, **values: object) -> None:
        return

    def fail(self, exception: Exception) -> None:
        return

    def end(self) -> None:
        return


class NoOpObservability:
    def start_request(
        self,
        request_id: str,
        thread_id: UUID,
        project_id: UUID,
    ) -> NullObservation:
        return NullObservation()

    def shutdown(self) -> None:
        return


class LangfuseObservability:
    def __init__(self, client: Any) -> None:
        self._client = client

    def start_request(
        self,
        request_id: str,
        thread_id: UUID,
        project_id: UUID,
    ) -> Observation:
        try:
            span = self._client.start_observation(
                name="agent-chat-request",
                as_type="span",
                metadata={
                    "request_id": request_id,
                    "thread_id": str(thread_id),
                    "project_id": str(project_id),
                },
            )
            return Observation(self._client, span)
        except Exception:
            return NullObservation()

    def shutdown(self) -> None:
        try:
            self._client.shutdown()
        except Exception:
            return


ClientFactory = Callable[..., Any]


def build_observability(settings: Any, client_factory: ClientFactory | None = None):
    public_key = settings.langfuse_public_key
    secret_key = settings.langfuse_secret_key
    if (
        not settings.langfuse_enabled
        or public_key is None
        or secret_key is None
        or not public_key.get_secret_value().strip()
        or not secret_key.get_secret_value().strip()
    ):
        return NoOpObservability()

    if client_factory is None:
        from langfuse import Langfuse

        client_factory = Langfuse
    try:
        client = client_factory(
            public_key=public_key.get_secret_value(),
            secret_key=secret_key.get_secret_value(),
            base_url=settings.langfuse_host,
            environment=settings.langfuse_environment,
            tracing_enabled=True,
        )
        return LangfuseObservability(client)
    except Exception:
        return NoOpObservability()
