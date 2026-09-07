from types import SimpleNamespace
from uuid import uuid4

import pytest

from agentforge_agent.config import Settings
from agentforge_agent.context import ContextManager
from agentforge_agent.errors import LlmDependencyError
from agentforge_agent.llm import CompatibleLlmResponder, build_responder
from agentforge_agent.retrieval import RetrievalResult
from agentforge_agent.schemas import ToolProposal


class FakeChatModel:
    def __init__(self, response_content="项目由 Java 和 Python 协作。") -> None:
        self.response_content = response_content
        self.messages = None

    def invoke(self, messages):
        self.messages = messages
        return SimpleNamespace(content=self.response_content)

    def stream(self, messages):
        self.messages = messages
        for content in self.response_content:
            yield SimpleNamespace(content=content)


def settings(**overrides) -> Settings:
    values = {
        "internal_token": "test-only-internal-token",
        "AGENTFORGE_CORE_INTERNAL_TOKEN": "test-only-core-token",
        "rag_db_dsn": "postgresql://agentforge:agentforge@localhost:5432/agentforge",
    }
    values.update(overrides)
    return Settings(**values)


def context_state(message="hello", retrieved_context="", proposal=None):
    bundle = ContextManager.build(
        project_id=uuid4(),
        user_id=uuid4(),
        actor_admin=False,
        message=message,
        conversation_id=uuid4(),
        request_id="request-llm",
    )
    bundle = ContextManager.with_retrieval(
        bundle, RetrievalResult(context=retrieved_context, sources=[])
    )
    bundle = ContextManager.with_tool(bundle, proposal)
    return {"context_bundle": bundle}


def test_compatible_responder_sends_question_and_retrieved_context() -> None:
    model = FakeChatModel()
    responder = CompatibleLlmResponder(model)

    answer = responder(context_state(
        "谁负责写入？", "[WIKI:1] Architecture\nJava owns writes."
    ))

    assert answer == "项目由 Java 和 Python 协作。"
    assert model.messages[0].content.startswith("你是 AgentForge")
    assert "谁负责写入？" in model.messages[1].content
    assert "Java owns writes." in model.messages[1].content


def test_compatible_responder_streams_native_model_chunks() -> None:
    model = FakeChatModel(["第一段", "，第二段"])
    responder = CompatibleLlmResponder(model)

    chunks = list(
        responder.stream(context_state("架构？", "Java owns writes."))
    )

    assert chunks == ["第一段", "，第二段"]
    assert "架构？" in model.messages[1].content


def test_compatible_responder_does_not_feed_tool_context_back_to_model() -> None:
    model = FakeChatModel()
    proposal = ToolProposal(
        action_type="CREATE_TASK",
        title="Private tool proposal title",
        description="Private tool proposal description",
        status="TODO",
        priority="HIGH",
    )

    CompatibleLlmResponder(model)(
        context_state("summarize", "Project context", proposal)
    )

    prompt = model.messages[1].content
    assert "Project context" in prompt
    assert "Private tool proposal title" not in prompt
    assert "Private tool proposal description" not in prompt


@pytest.mark.parametrize(
    ("provider", "expected_url", "expected_model"),
    [
        ("deepseek", "https://api.deepseek.com", "deepseek-v4-flash"),
        ("zhipu", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash-250414"),
        ("qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
    ],
)
def test_build_responder_maps_provider_to_non_openai_endpoint(
    provider, expected_url, expected_model
) -> None:
    captured = {}

    def fake_factory(**kwargs):
        captured.update(kwargs)
        return FakeChatModel()

    responder = build_responder(
        settings(llm_provider=provider, llm_api_key="local-test-key"),
        model_factory=fake_factory,
    )

    assert isinstance(responder, CompatibleLlmResponder)
    assert captured["base_url"] == expected_url
    assert captured["model"] == expected_model
    assert captured["api_key"].get_secret_value() == "local-test-key"
    assert "openai.com" not in captured["base_url"]
    assert captured["max_tokens"] == 800
    assert captured["max_retries"] == 1


def test_build_responder_applies_configured_max_tokens() -> None:
    captured = {}

    def fake_factory(**kwargs):
        captured.update(kwargs)
        return FakeChatModel()

    build_responder(
        settings(
            llm_provider="deepseek",
            llm_api_key="local-test-key",
            llm_max_tokens=321,
        ),
        model_factory=fake_factory,
    )

    assert captured["max_tokens"] == 321


def test_enabled_provider_requires_local_api_key() -> None:
    with pytest.raises(LlmDependencyError, match="API key"):
        build_responder(settings(llm_provider="deepseek"))


def test_provider_rejects_openai_base_url_override() -> None:
    with pytest.raises(LlmDependencyError, match="OpenAI service"):
        build_responder(
            settings(
                llm_provider="deepseek",
                llm_api_key="local-test-key",
                llm_base_url="https://api.openai.com/v1",
            ),
            model_factory=lambda **kwargs: FakeChatModel(),
        )


@pytest.mark.parametrize("content", ["", [], [{"type": "text", "text": "  "}]])
def test_responder_rejects_empty_model_content(content) -> None:
    responder = CompatibleLlmResponder(FakeChatModel(content))

    with pytest.raises(LlmDependencyError, match="valid text"):
        responder(context_state())


def test_responder_sanitizes_upstream_failure() -> None:
    class FailingModel:
        def invoke(self, messages):
            raise RuntimeError("upstream body containing secret details")

    with pytest.raises(LlmDependencyError, match="unavailable") as captured:
        CompatibleLlmResponder(FailingModel())(context_state())

    assert "secret details" not in str(captured.value)
