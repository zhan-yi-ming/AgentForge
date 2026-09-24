from types import SimpleNamespace

import pytest

from agentforge_agent.config import Settings
from agentforge_agent.errors import LlmDependencyError
from agentforge_agent.llm import build_responder
from test_llm import context_state


def test_provider_switch_uses_gateway_without_changing_agent_request() -> None:
    calls = []

    def completion(**kwargs):
        calls.append(kwargs)
        return SimpleNamespace(
            choices=[SimpleNamespace(message=SimpleNamespace(content="统一回复"))],
            usage=SimpleNamespace(prompt_tokens=12, completion_tokens=4, total_tokens=16),
        )

    settings = Settings(
        internal_token="test-only-internal-token",
        AGENTFORGE_CORE_INTERNAL_TOKEN="test-only-core-token",
        rag_db_dsn="postgresql://agentforge:agentforge@localhost:5432/agentforge",
        llm_provider="deepseek",
        llm_model="deepseek-flash",
        llm_api_key="local-test-key",
    )
    responder = build_responder(settings, completion_func=completion)

    assert responder(context_state("项目边界？")) == "统一回复"
    assert calls[0]["model"] == "openai/deepseek-flash"
    assert calls[0]["api_base"] == "https://api.deepseek.com"
    assert calls[0]["api_key"] == "local-test-key"
    assert calls[0]["messages"][1]["content"].find("项目边界？") >= 0


def test_transient_primary_failure_uses_one_configured_fallback() -> None:
    calls = []

    def completion(**kwargs):
        calls.append(kwargs["model"])
        if len(calls) == 1:
            raise TimeoutError("primary timed out")
        return SimpleNamespace(
            choices=[SimpleNamespace(message=SimpleNamespace(content="备用回复"))],
            usage=None,
        )

    configured = Settings(
        internal_token="test-only-internal-token",
        AGENTFORGE_CORE_INTERNAL_TOKEN="test-only-core-token",
        rag_db_dsn="postgresql://agentforge:agentforge@localhost:5432/agentforge",
        llm_provider="deepseek",
        llm_model="deepseek-flash",
        llm_api_key="primary-key",
        llm_fallback_provider="qwen",
        llm_fallback_api_key="fallback-key",
    )
    responder = build_responder(configured, completion_func=completion)

    assert responder(context_state("架构？")) == "备用回复"
    assert calls == ["openai/deepseek-flash", "openai/qwen-plus"]


def test_gateway_reports_provider_usage_and_known_cost_without_content() -> None:
    class Observation:
        def __init__(self):
            self.updates = []

        def update(self, **kwargs):
            self.updates.append(kwargs)

    def completion(**kwargs):
        return SimpleNamespace(
            choices=[SimpleNamespace(message=SimpleNamespace(content="私密回答"))],
            usage=SimpleNamespace(prompt_tokens=12, completion_tokens=4, total_tokens=16),
        )

    configured = Settings(
        internal_token="test-only-internal-token",
        AGENTFORGE_CORE_INTERNAL_TOKEN="test-only-core-token",
        rag_db_dsn="postgresql://agentforge:agentforge@localhost:5432/agentforge",
        llm_provider="deepseek",
        llm_model="deepseek-flash",
        llm_api_key="local-test-key",
    )
    observed = Observation()
    answer = build_responder(
        configured, completion_func=completion, cost_func=lambda response: 0.00042
    ).respond_observed(context_state("查询"), observed)

    assert answer == "私密回答"
    assert {"usage_details": {"input": 12, "output": 4, "total": 16}} in observed.updates
    assert {"metadata": {"provider": "deepseek", "cost_usd": 0.00042}} in observed.updates
    assert "私密回答" not in repr(observed.updates)


def test_authentication_failure_never_uses_fallback_or_leaks_upstream_detail() -> None:
    calls = []

    def completion(**kwargs):
        calls.append(kwargs["model"])
        raise ValueError("provider body contained private key")

    configured = Settings(
        internal_token="test-only-internal-token",
        AGENTFORGE_CORE_INTERNAL_TOKEN="test-only-core-token",
        rag_db_dsn="postgresql://agentforge:agentforge@localhost:5432/agentforge",
        llm_provider="deepseek",
        llm_model="deepseek-flash",
        llm_api_key="primary-key",
        llm_fallback_provider="qwen",
        llm_fallback_api_key="fallback-key",
    )
    with pytest.raises(LlmDependencyError) as captured:
        build_responder(configured, completion_func=completion)(context_state("查询"))

    assert calls == ["openai/deepseek-flash"]
    assert "private key" not in str(captured.value)


def test_stream_failure_after_first_token_does_not_switch_provider() -> None:
    calls = []

    def completion(**kwargs):
        calls.append(kwargs["model"])
        def chunks():
            yield SimpleNamespace(
                choices=[SimpleNamespace(delta=SimpleNamespace(content="已开始"))],
                usage=None,
            )
            raise TimeoutError("late provider timeout")
        return chunks()

    configured = Settings(
        internal_token="test-only-internal-token",
        AGENTFORGE_CORE_INTERNAL_TOKEN="test-only-core-token",
        rag_db_dsn="postgresql://agentforge:agentforge@localhost:5432/agentforge",
        llm_provider="deepseek",
        llm_model="deepseek-flash",
        llm_api_key="primary-key",
        llm_fallback_provider="qwen",
        llm_fallback_api_key="fallback-key",
    )
    stream = build_responder(configured, completion_func=completion).stream(context_state("查询"))
    assert next(stream) == "已开始"
    with pytest.raises(LlmDependencyError) as captured:
        list(stream)
    assert calls == ["openai/deepseek-flash"]
    assert "late provider timeout" not in str(captured.value)


def test_stream_timeout_before_text_uses_fallback_once() -> None:
    calls = []

    def completion(**kwargs):
        calls.append(kwargs["model"])
        if len(calls) == 1:
            raise TimeoutError("primary unavailable")
        return iter([SimpleNamespace(
            choices=[SimpleNamespace(delta=SimpleNamespace(content="备用流"))],
            usage=None,
        )])

    configured = Settings(
        internal_token="test-only-internal-token",
        AGENTFORGE_CORE_INTERNAL_TOKEN="test-only-core-token",
        rag_db_dsn="postgresql://agentforge:agentforge@localhost:5432/agentforge",
        llm_provider="deepseek",
        llm_model="deepseek-flash",
        llm_api_key="primary-key",
        llm_fallback_provider="qwen",
        llm_fallback_api_key="fallback-key",
    )
    assert list(build_responder(configured, completion_func=completion).stream(context_state("查询"))) == ["备用流"]
    assert calls == ["openai/deepseek-flash", "openai/qwen-plus"]


def test_action_intent_uses_gateway_json_mode_and_stays_untrusted() -> None:
    calls = []

    def completion(**kwargs):
        calls.append(kwargs)
        return SimpleNamespace(
            choices=[SimpleNamespace(message=SimpleNamespace(content='{"actionType":"CREATE_TASK","title":"检查发布"}'))],
            usage=None,
        )

    configured = Settings(
        internal_token="test-only-internal-token",
        AGENTFORGE_CORE_INTERNAL_TOKEN="test-only-core-token",
        rag_db_dsn="postgresql://agentforge:agentforge@localhost:5432/agentforge",
        llm_provider="deepseek",
        llm_model="deepseek-flash",
        llm_api_key="primary-key",
    )
    bundle = context_state("请创建检查发布任务")["context_bundle"]
    proposal = build_responder(configured, completion_func=completion).plan_tool(bundle)

    assert proposal is not None and proposal.title == "检查发布"
    assert calls[0]["response_format"] == {"type": "json_object"}
    assert calls[0]["max_tokens"] == 512


def test_blank_fallback_example_keeps_gateway_disabled() -> None:
    configured = Settings(
        internal_token="test-only-internal-token",
        AGENTFORGE_CORE_INTERNAL_TOKEN="test-only-core-token",
        rag_db_dsn="postgresql://agentforge:agentforge@localhost:5432/agentforge",
        llm_provider="disabled",
        llm_fallback_provider="",
    )
    assert configured.llm_fallback_provider is None


def test_stream_reports_fallback_model_and_usage_cost() -> None:
    class Observation:
        def __init__(self):
            self.updates = []

        def update(self, **kwargs):
            self.updates.append(kwargs)

    calls = []

    def completion(**kwargs):
        calls.append(kwargs["model"])
        if len(calls) == 1:
            raise TimeoutError("primary unavailable")
        return iter([
            SimpleNamespace(choices=[SimpleNamespace(delta=SimpleNamespace(content="备用流"))], usage=None),
            SimpleNamespace(choices=[], usage=SimpleNamespace(prompt_tokens=9, completion_tokens=3, total_tokens=12)),
        ])

    configured = Settings(
        internal_token="test-only-internal-token",
        AGENTFORGE_CORE_INTERNAL_TOKEN="test-only-core-token",
        rag_db_dsn="postgresql://agentforge:agentforge@localhost:5432/agentforge",
        llm_provider="deepseek",
        llm_model="deepseek-flash",
        llm_api_key="primary-key",
        llm_fallback_provider="qwen",
        llm_fallback_api_key="fallback-key",
    )
    observed = Observation()
    stream = build_responder(
        configured,
        completion_func=completion,
        stream_cost_func=lambda model, usage: 0.00031,
    ).stream_observed(context_state("查询"), observed)

    assert list(stream) == ["备用流"]
    assert {"model": "qwen-plus", "metadata": {"provider": "qwen"}} in observed.updates
    assert {"usage_details": {"input": 9, "output": 3, "total": 12}} in observed.updates
    assert {"metadata": {"provider": "qwen", "cost_usd": 0.00031}} in observed.updates


def test_stream_requests_provider_reported_usage() -> None:
    calls = []

    def completion(**kwargs):
        calls.append(kwargs)
        return iter([SimpleNamespace(
            choices=[SimpleNamespace(delta=SimpleNamespace(content="回答"))], usage=None
        )])

    configured = Settings(
        internal_token="test-only-internal-token",
        AGENTFORGE_CORE_INTERNAL_TOKEN="test-only-core-token",
        rag_db_dsn="postgresql://agentforge:agentforge@localhost:5432/agentforge",
        llm_provider="deepseek",
        llm_model="deepseek-flash",
        llm_api_key="primary-key",
    )
    assert list(build_responder(configured, completion_func=completion).stream(context_state("查询"))) == ["回答"]
    assert calls[0]["stream_options"] == {"include_usage": True}


def test_explicit_gpt_provider_requires_model_and_uses_official_route() -> None:
    calls = []

    def completion(**kwargs):
        calls.append(kwargs)
        return SimpleNamespace(
            choices=[SimpleNamespace(message=SimpleNamespace(content="GPT 回复"))], usage=None
        )

    configured = Settings(
        internal_token="test-only-internal-token",
        AGENTFORGE_CORE_INTERNAL_TOKEN="test-only-core-token",
        rag_db_dsn="postgresql://agentforge:agentforge@localhost:5432/agentforge",
        llm_provider="openai",
        llm_base_url="",
        llm_model="gpt-test",
        llm_api_key="local-test-key",
    )
    assert build_responder(configured, completion_func=completion)(context_state("查询")) == "GPT 回复"
    assert calls[0]["model"] == "openai/gpt-test"
    assert "api_base" not in calls[0]


def test_fallback_without_usage_reports_actual_provider() -> None:
    class Observation:
        def __init__(self):
            self.updates = []

        def update(self, **kwargs):
            self.updates.append(kwargs)

    calls = 0
    def completion(**kwargs):
        nonlocal calls
        calls += 1
        if calls == 1:
            raise TimeoutError("unavailable")
        return SimpleNamespace(
            choices=[SimpleNamespace(message=SimpleNamespace(content="备用"))], usage=None
        )

    configured = Settings(
        internal_token="test-only-internal-token",
        AGENTFORGE_CORE_INTERNAL_TOKEN="test-only-core-token",
        rag_db_dsn="postgresql://agentforge:agentforge@localhost:5432/agentforge",
        llm_provider="deepseek",
        llm_model="deepseek-flash",
        llm_api_key="primary-key",
        llm_fallback_provider="qwen",
        llm_fallback_api_key="fallback-key",
    )
    observed = Observation()
    build_responder(configured, completion_func=completion).respond_observed(context_state("查询"), observed)
    assert {"model": "qwen-plus", "metadata": {"provider": "qwen"}} in observed.updates


def test_enabled_fallback_requires_enabled_primary() -> None:
    configured = Settings(
        internal_token="test-only-internal-token",
        AGENTFORGE_CORE_INTERNAL_TOKEN="test-only-core-token",
        rag_db_dsn="postgresql://agentforge:agentforge@localhost:5432/agentforge",
        llm_provider="disabled",
        llm_fallback_provider="qwen",
        llm_fallback_api_key="fallback-key",
    )
    with pytest.raises(LlmDependencyError, match="fallback"):
        build_responder(configured, completion_func=lambda **kwargs: None)


def test_gpt_provider_rejects_stale_other_provider_base_url() -> None:
    configured = Settings(
        internal_token="test-only-internal-token",
        AGENTFORGE_CORE_INTERNAL_TOKEN="test-only-core-token",
        rag_db_dsn="postgresql://agentforge:agentforge@localhost:5432/agentforge",
        llm_provider="openai",
        llm_api_key="local-test-key",
        llm_model="gpt-test",
        llm_base_url="https://api.deepseek.com",
    )
    with pytest.raises(LlmDependencyError, match="base URL"):
        build_responder(configured, completion_func=lambda **kwargs: None)


def test_litellm_sdk_calls_configured_compatible_endpoint() -> None:
    import json
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
    from threading import Thread

    requests = []

    class Provider(BaseHTTPRequestHandler):
        def do_POST(self):
            length = int(self.headers["Content-Length"])
            body = json.loads(self.rfile.read(length))
            requests.append((self.path, body["model"], body["messages"][1]["content"]))
            response = json.dumps({
                "id": "chatcmpl-test", "object": "chat.completion", "created": 1,
                "model": "deepseek-flash",
                "choices": [{"index": 0, "finish_reason": "stop", "message": {"role": "assistant", "content": "本地 SDK 回复"}}],
                "usage": {"prompt_tokens": 7, "completion_tokens": 4, "total_tokens": 11},
            }).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(response)))
            self.end_headers()
            self.wfile.write(response)

        def log_message(self, *args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Provider)
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        configured = Settings(
            internal_token="test-only-internal-token",
            AGENTFORGE_CORE_INTERNAL_TOKEN="test-only-core-token",
            rag_db_dsn="postgresql://agentforge:agentforge@localhost:5432/agentforge",
            llm_provider="deepseek", llm_model="deepseek-flash",
            llm_api_key="local-test-key", llm_base_url=f"http://127.0.0.1:{server.server_port}/v1",
        )
        answer = build_responder(configured)(context_state("架构边界？"))
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)

    assert answer == "本地 SDK 回复"
    assert requests[0][0] == "/v1/chat/completions"
    assert requests[0][1] == "deepseek-flash"
    assert "架构边界？" in requests[0][2]


def test_litellm_timeout_still_falls_back_if_an_optional_error_name_is_absent(monkeypatch) -> None:
    import litellm

    timeout = litellm.Timeout("provider unavailable", model="deepseek-flash", llm_provider="openai")
    monkeypatch.delattr(litellm, "ServiceUnavailableError")
    calls = []

    def completion(**kwargs):
        calls.append(kwargs["model"])
        if len(calls) == 1:
            raise timeout
        return SimpleNamespace(
            choices=[SimpleNamespace(message=SimpleNamespace(content="备用"))], usage=None
        )

    configured = Settings(
        internal_token="test-only-internal-token",
        AGENTFORGE_CORE_INTERNAL_TOKEN="test-only-core-token",
        rag_db_dsn="postgresql://agentforge:agentforge@localhost:5432/agentforge",
        llm_provider="deepseek", llm_model="deepseek-flash", llm_api_key="primary-key",
        llm_fallback_provider="qwen", llm_fallback_api_key="fallback-key",
    )
    assert build_responder(configured, completion_func=completion)(context_state("查询")) == "备用"
    assert calls == ["openai/deepseek-flash", "openai/qwen-plus"]


@pytest.mark.parametrize(
    ("error_name", "should_fallback"),
    [("Timeout", True), ("RateLimitError", True), ("InternalServerError", True),
     ("AuthenticationError", False), ("BadRequestError", False)],
)
def test_litellm_error_policy_uses_fallback_only_for_transient_failures(error_name, should_fallback) -> None:
    import litellm

    error = getattr(litellm, error_name)(
        message="provider private response", model="deepseek-flash", llm_provider="openai"
    )
    calls = []

    def completion(**kwargs):
        calls.append(kwargs["model"])
        if len(calls) == 1:
            raise error
        return SimpleNamespace(
            choices=[SimpleNamespace(message=SimpleNamespace(content="备用"))], usage=None
        )

    configured = Settings(
        internal_token="test-only-internal-token",
        AGENTFORGE_CORE_INTERNAL_TOKEN="test-only-core-token",
        rag_db_dsn="postgresql://agentforge:agentforge@localhost:5432/agentforge",
        llm_provider="deepseek", llm_model="deepseek-flash", llm_api_key="primary-key",
        llm_fallback_provider="qwen", llm_fallback_api_key="fallback-key",
    )
    responder = build_responder(configured, completion_func=completion)
    if should_fallback:
        assert responder(context_state("查询")) == "备用"
        assert calls == ["openai/deepseek-flash", "openai/qwen-plus"]
    else:
        with pytest.raises(LlmDependencyError) as captured:
            responder(context_state("查询"))
        assert calls == ["openai/deepseek-flash"]
        assert "private response" not in str(captured.value)


def test_fallback_provider_failure_is_not_retried() -> None:
    import litellm

    calls = []
    def completion(**kwargs):
        calls.append(kwargs["model"])
        raise litellm.Timeout("provider unavailable", model="deepseek-flash", llm_provider="openai")

    configured = Settings(
        internal_token="test-only-internal-token",
        AGENTFORGE_CORE_INTERNAL_TOKEN="test-only-core-token",
        rag_db_dsn="postgresql://agentforge:agentforge@localhost:5432/agentforge",
        llm_provider="deepseek", llm_model="deepseek-flash", llm_api_key="primary-key",
        llm_fallback_provider="qwen", llm_fallback_api_key="fallback-key",
    )
    with pytest.raises(LlmDependencyError):
        build_responder(configured, completion_func=completion)(context_state("查询"))
    assert calls == ["openai/deepseek-flash", "openai/qwen-plus"]


def test_zero_cost_estimate_is_reported_as_unknown() -> None:
    class Observation:
        def __init__(self):
            self.updates = []
        def update(self, **kwargs):
            self.updates.append(kwargs)

    def completion(**kwargs):
        return SimpleNamespace(
            choices=[SimpleNamespace(message=SimpleNamespace(content="回答"))],
            usage=SimpleNamespace(prompt_tokens=5, completion_tokens=2, total_tokens=7),
        )

    configured = Settings(
        internal_token="test-only-internal-token",
        AGENTFORGE_CORE_INTERNAL_TOKEN="test-only-core-token",
        rag_db_dsn="postgresql://agentforge:agentforge@localhost:5432/agentforge",
        llm_provider="deepseek", llm_model="deepseek-flash", llm_api_key="primary-key",
    )
    observed = Observation()
    build_responder(configured, completion_func=completion, cost_func=lambda response: 0).respond_observed(
        context_state("查询"), observed
    )
    assert all("cost_usd" not in update.get("metadata", {}) for update in observed.updates)
