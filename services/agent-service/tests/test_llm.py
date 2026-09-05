from types import SimpleNamespace

import pytest

from agentforge_agent.config import Settings
from agentforge_agent.errors import LlmDependencyError
from agentforge_agent.llm import CompatibleLlmResponder, build_responder


class FakeChatModel:
    def __init__(self, response_content="项目由 Java 和 Python 协作。") -> None:
        self.response_content = response_content
        self.messages = None

    def invoke(self, messages):
        self.messages = messages
        return SimpleNamespace(content=self.response_content)


def settings(**overrides) -> Settings:
    values = {
        "internal_token": "test-only-internal-token",
        "AGENTFORGE_CORE_INTERNAL_TOKEN": "test-only-core-token",
        "rag_db_dsn": "postgresql://agentforge:agentforge@localhost:5432/agentforge",
    }
    values.update(overrides)
    return Settings(**values)


def test_compatible_responder_sends_question_and_retrieved_context() -> None:
    model = FakeChatModel()
    responder = CompatibleLlmResponder(model)

    answer = responder(
        {
            "normalized_message": "谁负责写入？",
            "retrieved_context": "[WIKI:1] Architecture\nJava owns writes.",
        }
    )

    assert answer == "项目由 Java 和 Python 协作。"
    assert model.messages[0].content.startswith("你是 AgentForge")
    assert "谁负责写入？" in model.messages[1].content
    assert "Java owns writes." in model.messages[1].content


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
        responder({"normalized_message": "hello", "retrieved_context": ""})


def test_responder_sanitizes_upstream_failure() -> None:
    class FailingModel:
        def invoke(self, messages):
            raise RuntimeError("upstream body containing secret details")

    with pytest.raises(LlmDependencyError, match="unavailable") as captured:
        CompatibleLlmResponder(FailingModel())(
            {"normalized_message": "hello", "retrieved_context": ""}
        )

    assert "secret details" not in str(captured.value)
