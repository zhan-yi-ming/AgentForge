from types import SimpleNamespace
import pytest
from agentforge_agent.config import Settings
from agentforge_agent.llm import build_responder
from test_llm import context_state


def configured(**changes):
    values = dict(internal_token="test-internal-token", AGENTFORGE_CORE_INTERNAL_TOKEN="test-core-internal-token", rag_db_dsn="postgresql://test:test@localhost/test", llm_provider="deepseek", llm_api_key="test-key", llm_model="base-model", llm_base_url="https://api.deepseek.com", llm_fallback_provider=None, request_timeout_seconds=10, llm_routes=[
        dict(name="cheap", endpoint="primary", model="cheap-model", tasks=["FORMAT", "REWRITE", "PLAN", "REVIEW", "ANSWER"], json_output=True, streaming=True, cost_rank=1, latency_rank=2, capability_rank=20),
        dict(name="capable", endpoint="primary", model="capable-model", tasks=["FORMAT", "REWRITE", "PLAN", "REVIEW", "ANSWER"], json_output=True, streaming=True, cost_rank=5, latency_rank=1, capability_rank=90),
    ])
    values.update(changes)
    return Settings(**values)


def response(text):
    return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content=text))], usage=None)


def test_format_uses_cost_preference_and_review_uses_capability():
    responder = build_responder(configured(), completion_func=lambda **kw: response(kw["model"]))
    assert responder(context_state("FORMAT: notes")) == "openai/cheap-model"
    assert responder(context_state("REVIEW: design")) == "openai/capable-model"


def test_plan_answer_is_text_while_tool_intent_uses_json():
    calls = []
    def completion(**kw):
        calls.append(kw)
        return response('{"actionType":"NONE"}' if "response_format" in kw else "Plan steps")
    responder = build_responder(configured(), completion_func=completion)
    assert responder(context_state("PLAN: release")) == "Plan steps"
    assert responder.plan_tool(context_state("Help plan a release")["context_bundle"]) is None
    assert calls[-1]["response_format"] == {"type": "json_object"}
    assert calls[-1]["model"] == "openai/capable-model"


def test_unused_candidate_with_missing_endpoint_fails_closed():
    from agentforge_agent.errors import LlmDependencyError
    settings = configured()
    settings.llm_routes.append(dict(name="unused", endpoint="fallback", model="unused-model", tasks=["ANSWER"], json_output=True, streaming=True, cost_rank=100, latency_rank=100, capability_rank=1))
    with pytest.raises(LlmDependencyError, match="routing is invalid"):
        build_responder(settings, completion_func=lambda **kw: response("unexpected"))


@pytest.mark.parametrize("message,expected", [
    ("REWRITE: notes", "capable-model"), ("PLAN: design", "capable-model"),
    ("ordinary question", "capable-model"),
    ("请将以下内容整理为 Markdown，保留事实，使用一个明确的一级标题，不执行写入：\n\n笔记", "cheap-model"),
    ("请润色笔记", "capable-model"), ("请审查设计", "capable-model"),
    ("请制定计划", "capable-model"), ("Quoted FORMAT: notes", "capable-model"),
])
def test_sync_and_stream_share_task_selection(message, expected):
    def completion(**kw):
        if kw["stream"]:
            return iter([SimpleNamespace(choices=[SimpleNamespace(delta=SimpleNamespace(content=kw["model"]))], usage=None)])
        return response(kw["model"])
    responder = build_responder(configured(), completion_func=completion)
    assert responder(context_state(message)) == "openai/" + expected
    assert "".join(responder.stream(context_state(message))) == "openai/" + expected


@pytest.mark.parametrize("bad", ["missing_task", "json", "stream", "duplicate_name", "disabled", "unknown_field"])
def test_invalid_routing_never_calls_provider(bad):
    from agentforge_agent.errors import LlmDependencyError
    settings = configured()
    if bad == "missing_task":
        for item in settings.llm_routes:
            item["tasks"].remove("ANSWER")
    elif bad == "json":
        settings.llm_routes[0]["json_output"] = False
    elif bad == "stream":
        settings.llm_routes[0]["streaming"] = False
    elif bad == "duplicate_name":
        settings.llm_routes[1]["name"] = "cheap"
    elif bad == "disabled":
        settings.llm_provider = "disabled"
    else:
        settings.llm_routes[0]["api_key"] = "forbidden-test-value"
    def unexpected(**kw):
        pytest.fail("invalid configuration called provider")
    with pytest.raises(LlmDependencyError, match="routing is invalid"):
        build_responder(settings, completion_func=unexpected)


def test_routed_fallback_is_bounded_and_preserves_actual_usage():
    calls = []
    class Observation:
        def __init__(self):
            self.metadata = {}
            self.usage = None
        def update(self, **kw):
            self.metadata.update(kw.get("metadata", {}))
            if "model" in kw: self.model = kw["model"]
            if "usage_details" in kw: self.usage = kw["usage_details"]
    def completion(**kw):
        calls.append(kw)
        if kw["model"] == "openai/cheap-model":
            raise TimeoutError("test-only-upstream-secret")
        result = response("Recovered")
        result.usage = SimpleNamespace(prompt_tokens=10, completion_tokens=2, total_tokens=12)
        return result
    observation = Observation()
    responder = build_responder(configured(), completion_func=completion, cost_func=lambda result: .003)
    assert responder.respond_observed(context_state("FORMAT: notes"), observation) == "Recovered"
    assert [item["model"] for item in calls] == ["openai/cheap-model", "openai/capable-model"]
    assert all(item["num_retries"] == 0 and item["timeout"] == 10 for item in calls)
    assert observation.model == "capable-model"
    assert observation.usage == {"input": 10, "output": 2, "total": 12}
    assert observation.metadata["cost_usd"] == .003
    assert observation.metadata["task_type"] == "FORMAT"
    assert "test-only-upstream-secret" not in str(observation.metadata)


def test_routed_stream_does_not_switch_after_text():
    from agentforge_agent.errors import LlmDependencyError
    calls = []
    def completion(**kw):
        calls.append(kw["model"])
        def chunks():
            yield SimpleNamespace(choices=[SimpleNamespace(delta=SimpleNamespace(content="Partial"))], usage=None)
            raise TimeoutError("test-only-upstream-secret")
        return chunks()
    stream = build_responder(configured(), completion_func=completion).stream(context_state("FORMAT: notes"))
    assert next(stream) == "Partial"
    with pytest.raises(LlmDependencyError) as error:
        next(stream)
    assert "test-only-upstream-secret" not in str(error.value)
    assert calls == ["openai/cheap-model"]


def test_routed_backup_failure_does_not_loop():
    from agentforge_agent.errors import LlmDependencyError
    calls = []
    def completion(**kw):
        calls.append(kw["model"])
        raise TimeoutError("test-only-upstream-secret")
    with pytest.raises(LlmDependencyError):
        build_responder(configured(), completion_func=completion)(context_state("FORMAT: notes"))
    assert calls == ["openai/cheap-model", "openai/capable-model"]


def test_routing_uses_independent_provider_credentials():
    settings = configured(llm_fallback_provider="qwen", llm_fallback_api_key="test-other-key", llm_fallback_base_url="https://dashscope.aliyuncs.com/compatible-mode/v1")
    settings.llm_routes[1]["endpoint"] = "fallback"
    calls = []
    def completion(**kw):
        calls.append(kw)
        return response("Independent destination")
    assert build_responder(settings, completion_func=completion)(context_state("REVIEW: design")) == "Independent destination"
    assert calls[0]["api_key"] == "test-other-key"
    assert calls[0]["api_base"] == "https://dashscope.aliyuncs.com/compatible-mode/v1"


def test_same_model_name_fallback_reports_actual_provider():
    settings = configured(llm_fallback_provider="qwen", llm_fallback_api_key="test-other-key", llm_fallback_base_url="https://dashscope.aliyuncs.com/compatible-mode/v1")
    settings.llm_routes[1].update(endpoint="fallback", model="cheap-model")
    class Observation:
        metadata = {}
        def update(self, **kw):
            self.metadata = {**self.metadata, **kw.get("metadata", {})}
    def completion(**kw):
        if kw["api_key"] == "test-key": raise TimeoutError("temporary")
        return response("Recovered")
    observed = Observation()
    assert build_responder(settings, completion_func=completion).respond_observed(context_state("FORMAT: notes"), observed) == "Recovered"
    assert observed.metadata["provider"] == "qwen"


def test_duplicate_destination_slots_do_not_create_self_fallback():
    settings = configured(llm_fallback_provider="deepseek", llm_fallback_api_key="test-key", llm_fallback_base_url="https://api.deepseek.com")
    settings.llm_routes[1].update(endpoint="fallback", model="cheap-model")
    assert build_responder(settings, completion_func=lambda **kw: response("One destination"))(context_state("FORMAT: notes")) == "One destination"


def test_route_metadata_survives_replacing_observation_updates():
    class Observation:
        metadata = {}
        def update(self, **kw):
            if "metadata" in kw: self.metadata = kw["metadata"]
    observed = Observation()
    build_responder(configured(), completion_func=lambda **kw: response("Answer")).respond_observed(context_state("FORMAT: notes"), observed)
    assert observed.metadata["task_type"] == "FORMAT"
    assert observed.metadata["provider"] == "deepseek"


def test_http_json_and_ndjson_use_routed_responder():
    import json
    from uuid import uuid4
    from fastapi.testclient import TestClient
    from agentforge_agent.main import app
    from agentforge_agent.api import get_responder, get_retrieval_service, get_conversation_memory, get_action_runtime
    from agentforge_agent.config import get_settings
    from agentforge_agent.context import ConversationMemory, TokenCounter
    from agentforge_agent.action_runtime import ActionWorkflowRuntime
    from langgraph.checkpoint.memory import InMemorySaver
    from agentforge_agent.retrieval import RetrievalResult
    def completion(**kw):
        if kw["stream"]:
            return iter([SimpleNamespace(choices=[SimpleNamespace(delta=SimpleNamespace(content=kw["model"]))], usage=None)])
        return response(kw["model"])
    settings = configured()
    responder = build_responder(settings, completion_func=completion)
    class Retrieval:
        def retrieve(self, *args): return RetrievalResult(context="", sources=[])
    old = dict(app.dependency_overrides)
    app.dependency_overrides.update({get_settings: lambda: settings, get_responder: lambda: responder,
        get_retrieval_service: lambda: Retrieval(), get_conversation_memory: lambda: ConversationMemory(recent_turns=4, summary_token_budget=800, max_sessions=10, token_counter=TokenCounter()),
        get_action_runtime: lambda: ActionWorkflowRuntime(InMemorySaver())})
    try:
        client = TestClient(app)
        body = dict(projectId=str(uuid4()), userId=str(uuid4()), message="FORMAT: notes", requestId="routing-http-test")
        headers = {"X-AgentForge-Internal-Token": "test-internal-token"}
        sync = client.post("/internal/v1/chat", json=body, headers=headers)
        stream = client.post("/internal/v1/chat/stream", json=body, headers=headers)
        assert sync.status_code == 200
        assert sync.json()["answer"] == "openai/cheap-model"
        assert stream.status_code == 200
        events = [json.loads(line) for line in stream.text.splitlines()]
        assert "".join(event["text"] for event in events if event["type"] == "delta") == "openai/cheap-model"
        assert events[-1]["type"] == "complete"
        assert events[-1]["toolProposal"] is None
    finally:
        app.dependency_overrides.clear()
        app.dependency_overrides.update(old)
