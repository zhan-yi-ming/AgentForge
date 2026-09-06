from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from agentforge_agent.api import get_observability, get_responder, get_retrieval_service
from agentforge_agent.config import Settings
from agentforge_agent.graph import build_chat_graph
from agentforge_agent.llm import CompatibleLlmResponder
from agentforge_agent.main import app
from agentforge_agent.retrieval import RetrievalResult
from agentforge_agent.observability import (
    LangfuseObservability,
    NoOpObservability,
    build_observability,
)


class FakeLangfuseSpan:
    def __init__(self, trace_id: str, observation_id: str) -> None:
        self.trace_id = trace_id
        self.id = observation_id
        self.updates: list[dict[str, object]] = []
        self.ended = False

    def update(self, **values) -> None:
        self.updates.append(values)

    def end(self) -> None:
        self.ended = True


class FakeLangfuseClient:
    def __init__(self) -> None:
        self.started: list[dict[str, object]] = []
        self.spans: list[FakeLangfuseSpan] = []
        self.shutdown_called = False

    def start_observation(self, **values) -> FakeLangfuseSpan:
        self.started.append(values)
        trace_context = values.get("trace_context") or {}
        span = FakeLangfuseSpan(
            trace_id=trace_context.get("trace_id", "trace-root"),
            observation_id=f"observation-{len(self.spans) + 1}",
        )
        self.spans.append(span)
        return span

    def shutdown(self) -> None:
        self.shutdown_called = True


def test_langfuse_observability_preserves_request_hierarchy_and_safe_ids() -> None:
    client = FakeLangfuseClient()
    observer = LangfuseObservability(client)
    project_id = uuid4()
    thread_id = uuid4()

    request = observer.start_request("request-123", thread_id, project_id)
    agent = request.child("agent", "agent")
    retrieval = agent.child("retrieval", "retriever")
    retrieval.update(output={"status": "completed", "source_count": 2})
    retrieval.end()
    agent.end()
    request.end()

    assert client.started[0] == {
        "name": "agent-chat-request",
        "as_type": "span",
        "metadata": {
            "request_id": "request-123",
            "thread_id": str(thread_id),
            "project_id": str(project_id),
        },
    }
    assert client.started[1]["trace_context"] == {
        "trace_id": "trace-root",
        "parent_span_id": "observation-1",
    }
    assert client.started[2]["trace_context"] == {
        "trace_id": "trace-root",
        "parent_span_id": "observation-2",
    }
    assert client.spans[2].updates == [
        {"output": {"status": "completed", "source_count": 2}}
    ]
    assert all(span.ended for span in client.spans)


def test_observability_failure_is_isolated_from_business_execution() -> None:
    class FailingClient:
        def start_observation(self, **values):
            raise RuntimeError("network response containing secret details")

    observer = LangfuseObservability(FailingClient())

    request = observer.start_request("request-123", uuid4(), uuid4())
    child = request.child("retrieval", "retriever")
    child.update(output={"status": "completed"})
    child.fail(ValueError("private message"))
    child.end()
    request.end()


def settings(**overrides) -> Settings:
    values = {
        "internal_token": "test-only-internal-token",
        "AGENTFORGE_CORE_INTERNAL_TOKEN": "test-only-core-token",
        "rag_db_dsn": "postgresql://agentforge:agentforge@localhost:5432/agentforge",
    }
    values.update(overrides)
    return Settings(**values)


def test_build_observability_is_disabled_without_complete_credentials() -> None:
    assert isinstance(build_observability(settings()), NoOpObservability)
    assert isinstance(
        build_observability(settings(langfuse_enabled=True)), NoOpObservability
    )


def test_build_observability_passes_secrets_without_exposing_them() -> None:
    captured = {}
    client = FakeLangfuseClient()

    def client_factory(**values):
        captured.update(values)
        return client

    observer = build_observability(
        settings(
            langfuse_enabled=True,
            langfuse_public_key="pk-test",
            langfuse_secret_key="sk-test",
            langfuse_host="https://langfuse.example.test",
            langfuse_environment="test",
        ),
        client_factory=client_factory,
    )

    assert isinstance(observer, LangfuseObservability)
    assert captured == {
        "public_key": "pk-test",
        "secret_key": "sk-test",
        "base_url": "https://langfuse.example.test",
        "environment": "test",
        "tracing_enabled": True,
    }


def test_chat_graph_records_each_agent_node_without_sensitive_content() -> None:
    client = FakeLangfuseClient()
    observer = LangfuseObservability(client)
    project_id = uuid4()
    thread_id = uuid4()
    root = observer.start_request("request-graph", thread_id, project_id)
    agent = root.child("agent", "agent")

    def retriever(project_id, user_id, actor_admin, query, request_id):
        from agentforge_agent.retrieval import RetrievalResult

        return RetrievalResult(context="private retrieved content", sources=[])

    graph = build_chat_graph(
        retriever,
        lambda state: "private model answer",
        observation=agent,
    )
    result = graph.invoke(
        {
            "project_id": project_id,
            "user_id": uuid4(),
            "actor_admin": False,
            "message": "private user message",
            "conversation_id": thread_id,
            "request_id": "request-graph",
        }
    )
    agent.end()
    root.end()

    assert result["answer"] == "private model answer"
    assert [started["name"] for started in client.started[2:]] == [
        "prepare",
        "retrieval",
        "tool",
        "llm",
    ]
    assert [started["as_type"] for started in client.started[2:]] == [
        "chain",
        "retriever",
        "tool",
        "generation",
    ]
    payload = repr(client.started) + repr([span.updates for span in client.spans])
    assert "private user message" not in payload
    assert "private retrieved content" not in payload
    assert "private model answer" not in payload
    assert client.spans[3].updates == [
        {"output": {"status": "completed", "source_count": 0}}
    ]
    assert client.spans[4].updates == [
        {"output": {"status": "completed", "proposed": False, "tool": None}}
    ]
    assert all(span.ended for span in client.spans)


def test_failed_graph_node_closes_with_sanitized_error_type() -> None:
    client = FakeLangfuseClient()
    observer = LangfuseObservability(client)
    root = observer.start_request("request-error", uuid4(), uuid4())
    agent = root.child("agent", "agent")

    def failing_retriever(*args):
        raise RuntimeError("database response containing secret details")

    graph = build_chat_graph(failing_retriever, observation=agent)
    with pytest.raises(RuntimeError, match="secret details"):
        graph.invoke(
            {
                "project_id": uuid4(),
                "user_id": uuid4(),
                "actor_admin": False,
                "message": "private user message",
                "conversation_id": uuid4(),
                "request_id": "request-error",
            }
        )
    agent.fail(RuntimeError("database response containing secret details"))
    agent.end()
    root.fail(RuntimeError("database response containing secret details"))
    root.end()

    retrieval_span = client.spans[3]
    assert retrieval_span.updates == [
        {"level": "ERROR", "status_message": "RuntimeError"}
    ]
    payload = repr([span.updates for span in client.spans])
    assert "secret details" not in payload
    assert all(span.ended for span in client.spans)


def test_json_chat_owns_complete_request_trace_and_generated_thread_id() -> None:
    langfuse_client = FakeLangfuseClient()
    observer = LangfuseObservability(langfuse_client)
    project_id = uuid4()

    class EmptyRetrieval:
        def retrieve(self, *args):
            return RetrievalResult(context="", sources=[])

    class JsonModelResponse:
        content = "private model answer"
        usage_metadata = {
            "input_tokens": 13,
            "output_tokens": 5,
            "total_tokens": 18,
        }

    class JsonModel:
        def invoke(self, messages):
            return JsonModelResponse()

    app.dependency_overrides[get_observability] = lambda: observer
    app.dependency_overrides[get_retrieval_service] = lambda: EmptyRetrieval()
    app.dependency_overrides[get_responder] = lambda: CompatibleLlmResponder(
        JsonModel(), provider="deepseek", model_name="deepseek-v4-flash"
    )
    try:
        response = TestClient(app).post(
            "/internal/v1/chat",
            headers={"X-AgentForge-Internal-Token": "test-only-internal-token"},
            json={
                "projectId": str(project_id),
                "userId": str(uuid4()),
                "message": "private user message",
                "requestId": "request-http",
            },
        )
    finally:
        app.dependency_overrides.pop(get_observability, None)
        app.dependency_overrides.pop(get_retrieval_service, None)
        app.dependency_overrides.pop(get_responder, None)

    assert response.status_code == 200
    thread_id = response.json()["conversationId"]
    assert langfuse_client.started[0]["metadata"] == {
        "request_id": "request-http",
        "thread_id": thread_id,
        "project_id": str(project_id),
    }
    assert [item["name"] for item in langfuse_client.started] == [
        "agent-chat-request",
        "agent",
        "prepare",
        "retrieval",
        "tool",
        "llm",
    ]
    assert langfuse_client.spans[0].updates == [
        {"output": {"status": "completed"}}
    ]
    assert langfuse_client.spans[1].updates == [
        {"output": {"status": "completed"}}
    ]
    assert langfuse_client.spans[5].updates == [
        {
            "model": "deepseek-v4-flash",
            "metadata": {"provider": "deepseek"},
        },
        {"usage_details": {"input": 13, "output": 5, "total": 18}},
        {"output": {"status": "completed"}},
    ]
    assert all(span.ended for span in langfuse_client.spans)


def test_stream_chat_keeps_request_trace_open_through_generation() -> None:
    langfuse_client = FakeLangfuseClient()
    observer = LangfuseObservability(langfuse_client)

    class EmptyRetrieval:
        def retrieve(self, *args):
            return RetrievalResult(context="", sources=[])

    class StreamChunk:
        def __init__(self, content, usage_metadata=None):
            self.content = content
            self.usage_metadata = usage_metadata

    class StreamingModel:
        def stream(self, messages):
            yield StreamChunk("private chunk one")
            yield StreamChunk(
                "private chunk two",
                {
                    "input_tokens": 17,
                    "output_tokens": 6,
                    "total_tokens": 23,
                },
            )

    app.dependency_overrides[get_observability] = lambda: observer
    app.dependency_overrides[get_retrieval_service] = lambda: EmptyRetrieval()
    app.dependency_overrides[get_responder] = lambda: CompatibleLlmResponder(
        StreamingModel(), provider="deepseek", model_name="deepseek-v4-flash"
    )
    try:
        response = TestClient(app).post(
            "/internal/v1/chat/stream",
            headers={"X-AgentForge-Internal-Token": "test-only-internal-token"},
            json={
                "projectId": str(uuid4()),
                "userId": str(uuid4()),
                "message": "private stream message",
                "requestId": "request-stream",
            },
        )
    finally:
        app.dependency_overrides.pop(get_observability, None)
        app.dependency_overrides.pop(get_retrieval_service, None)
        app.dependency_overrides.pop(get_responder, None)

    assert response.status_code == 200
    events = [__import__("json").loads(line) for line in response.text.splitlines()]
    assert [event["type"] for event in events] == [
        "metadata",
        "delta",
        "delta",
        "complete",
    ]
    assert langfuse_client.started[0]["metadata"]["thread_id"] == events[0][
        "conversationId"
    ]
    assert [item["name"] for item in langfuse_client.started] == [
        "agent-chat-request",
        "agent",
        "prepare",
        "retrieval",
        "tool",
        "llm",
    ]
    payload = repr(langfuse_client.started) + repr(
        [span.updates for span in langfuse_client.spans]
    )
    assert "private stream message" not in payload
    assert "private chunk one" not in payload
    assert langfuse_client.spans[5].updates == [
        {
            "model": "deepseek-v4-flash",
            "metadata": {"provider": "deepseek"},
        },
        {"usage_details": {"input": 17, "output": 6, "total": 23}},
        {"output": {"status": "completed"}},
    ]
    assert all(span.ended for span in langfuse_client.spans)


def test_llm_generation_records_provider_model_and_reported_token_usage() -> None:
    class ModelResponse:
        content = "private model answer"
        usage_metadata = {
            "input_tokens": 21,
            "output_tokens": 8,
            "total_tokens": 29,
        }

    class UsageModel:
        def invoke(self, messages):
            return ModelResponse()

    client = FakeLangfuseClient()
    observer = LangfuseObservability(client)
    generation = observer.start_request("request-usage", uuid4(), uuid4()).child(
        "llm", "generation"
    )
    responder = CompatibleLlmResponder(
        UsageModel(), provider="deepseek", model_name="deepseek-v4-flash"
    )

    answer = responder.respond_observed(
        {"normalized_message": "private message", "retrieved_context": "private context"},
        generation,
    )

    assert answer == "private model answer"
    assert client.spans[1].updates == [
        {
            "model": "deepseek-v4-flash",
            "metadata": {"provider": "deepseek"},
        },
        {"usage_details": {"input": 21, "output": 8, "total": 29}},
    ]
    assert "private model answer" not in repr(client.spans[1].updates)


def test_application_shutdown_flushes_observability(monkeypatch) -> None:
    import agentforge_agent.main as main_module

    client = FakeLangfuseClient()
    observer = LangfuseObservability(client)
    monkeypatch.setattr(main_module, "get_observability", lambda: observer)

    with TestClient(main_module.create_app()) as local_client:
        assert local_client.get("/health").status_code == 200

    assert client.shutdown_called is True


def test_stream_llm_error_closes_trace_without_upstream_details() -> None:
    class EmptyRetrieval:
        def retrieve(self, *args):
            return RetrievalResult(context="", sources=[])

    class FailingStreamingModel:
        def stream(self, messages):
            raise RuntimeError("upstream payload containing secret details")
            yield

    langfuse_client = FakeLangfuseClient()
    observer = LangfuseObservability(langfuse_client)
    app.dependency_overrides[get_observability] = lambda: observer
    app.dependency_overrides[get_retrieval_service] = lambda: EmptyRetrieval()
    app.dependency_overrides[get_responder] = lambda: CompatibleLlmResponder(
        FailingStreamingModel(), provider="deepseek", model_name="deepseek-v4-flash"
    )
    try:
        response = TestClient(app).post(
            "/internal/v1/chat/stream",
            headers={"X-AgentForge-Internal-Token": "test-only-internal-token"},
            json={
                "projectId": str(uuid4()),
                "userId": str(uuid4()),
                "message": "private stream message",
                "requestId": "request-stream-error",
            },
        )
    finally:
        app.dependency_overrides.pop(get_observability, None)
        app.dependency_overrides.pop(get_retrieval_service, None)
        app.dependency_overrides.pop(get_responder, None)

    events = [__import__("json").loads(line) for line in response.text.splitlines()]
    assert [event["type"] for event in events] == ["metadata", "error"]
    assert events[-1]["message"] == "LLM provider is unavailable."
    assert langfuse_client.spans[5].updates[-1] == {
        "level": "ERROR",
        "status_message": "LlmDependencyError",
    }
    payload = repr([span.updates for span in langfuse_client.spans])
    assert "secret details" not in payload
    assert all(span.ended for span in langfuse_client.spans)


def test_stream_context_unknown_error_closes_request_and_agent_trace() -> None:
    class UnexpectedRetrievalFailure:
        def retrieve(self, *args):
            raise RuntimeError("upstream payload containing secret details")

    langfuse_client = FakeLangfuseClient()
    observer = LangfuseObservability(langfuse_client)
    app.dependency_overrides[get_observability] = lambda: observer
    app.dependency_overrides[get_retrieval_service] = lambda: UnexpectedRetrievalFailure()
    app.dependency_overrides[get_responder] = lambda: (lambda state: "unused")
    try:
        with pytest.raises(RuntimeError, match="upstream payload"):
            TestClient(app).post(
                "/internal/v1/chat/stream",
                headers={"X-AgentForge-Internal-Token": "test-only-internal-token"},
                json={
                    "projectId": str(uuid4()),
                    "userId": str(uuid4()),
                    "message": "private stream message",
                    "requestId": "request-stream-context-error",
                },
            )
    finally:
        app.dependency_overrides.pop(get_observability, None)
        app.dependency_overrides.pop(get_retrieval_service, None)
        app.dependency_overrides.pop(get_responder, None)

    request_span, agent_span = langfuse_client.spans[:2]
    expected_error = {
        "level": "ERROR",
        "status_message": "RuntimeError",
    }
    assert request_span.updates[-1] == expected_error
    assert agent_span.updates[-1] == expected_error
    assert request_span.ended is True
    assert agent_span.ended is True
    assert "secret details" not in repr(
        [span.updates for span in langfuse_client.spans]
    )
