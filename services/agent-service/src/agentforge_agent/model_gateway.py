"""Configured LiteLLM boundary for Agent response and intent generation."""

from collections.abc import Callable, Iterator
from math import isfinite
from types import SimpleNamespace
from typing import Any


class LiteLlmGateway:
    def __init__(
        self,
        *,
        provider: str,
        model: str,
        api_key: str,
        api_base: str | None,
        timeout: float,
        max_tokens: int,
        completion_func: Callable[..., Any],
        response_format: dict[str, str] | None = None,
        fallback: tuple[str, str, str, str] | None = None,
        cost_func: Callable[[Any], float] | None = None,
        stream_cost_func: Callable[[str, dict[str, int]], float] | None = None,
    ) -> None:
        self.provider = provider
        self.model = model
        self.api_key = api_key
        self.api_base = api_base
        self.timeout = timeout
        self.max_tokens = max_tokens
        self.completion_func = completion_func
        self.response_format = response_format
        self.fallback = fallback
        self.cost_func = cost_func
        self.stream_cost_func = stream_cost_func

    def bind(self, **kwargs: Any) -> "LiteLlmGateway":
        return LiteLlmGateway(
            provider=self.provider,
            model=self.model,
            api_key=self.api_key,
            api_base=self.api_base,
            timeout=self.timeout,
            max_tokens=kwargs.get("max_tokens", self.max_tokens),
            completion_func=self.completion_func,
            response_format=kwargs.get("response_format", self.response_format),
            fallback=self.fallback,
            cost_func=self.cost_func,
            stream_cost_func=self.stream_cost_func,
        )

    def _kwargs(self, messages: list[Any], *, stream: bool, fallback: bool = False) -> dict[str, Any]:
        provider, model, api_key, api_base = self.fallback if fallback and self.fallback else (self.provider, self.model, self.api_key, self.api_base or "")
        kwargs: dict[str, Any] = {
            "model": f"openai/{model}",
            "api_key": api_key,
            "messages": [{"role": "system" if index == 0 else "user", "content": message.content}
                         for index, message in enumerate(messages)],
            "timeout": self.timeout,
            "max_tokens": self.max_tokens,
            "stream": stream,
            "num_retries": 0,
        }
        if api_base:
            kwargs["api_base"] = api_base
        if self.response_format:
            kwargs["response_format"] = self.response_format
        if stream:
            kwargs["stream_options"] = {"include_usage": True}
        return kwargs

    def invoke(self, messages: list[Any]) -> Any:
        used_fallback = False
        try:
            response = self.completion_func(**self._kwargs(messages, stream=False))
        except Exception as error:
            if not self.fallback or not _recoverable(error):
                raise
            response = self.completion_func(**self._kwargs(messages, stream=False, fallback=True))
            used_fallback = True
        content = response.choices[0].message.content
        usage = getattr(response, "usage", None)
        provider = self.fallback[0] if used_fallback and self.fallback else self.provider
        model = self.fallback[1] if used_fallback and self.fallback else self.model
        metadata: dict[str, Any] = {"provider": provider, "model": model}
        if usage is not None and self.cost_func is not None:
            try:
                cost = self.cost_func(response)
                if isinstance(cost, (int, float)) and isfinite(cost) and cost > 0:
                    metadata["cost_usd"] = float(cost)
            except Exception:
                pass
        return SimpleNamespace(
            content=content,
            usage_metadata=_usage(usage),
            response_metadata=metadata,
        )

    def stream(self, messages: list[Any]) -> Iterator[Any]:
        emitted = False
        for attempt in (False, True):
            if attempt and not self.fallback:
                break
            try:
                chunks = self.completion_func(**self._kwargs(messages, stream=True, fallback=attempt))
                for chunk in chunks:
                    choices = getattr(chunk, "choices", None) or []
                    content = getattr(choices[0].delta, "content", None) if choices else None
                    if content:
                        emitted = True
                    usage = _usage(getattr(chunk, "usage", None))
                    provider = self.fallback[0] if attempt and self.fallback else self.provider
                    model = self.fallback[1] if attempt and self.fallback else self.model
                    metadata: dict[str, Any] = {"provider": provider, "model": model}
                    if usage and self.stream_cost_func is not None and "input_tokens" in usage and "output_tokens" in usage:
                        try:
                            cost = self.stream_cost_func(f"openai/{model}", usage)
                            if isinstance(cost, (int, float)) and isfinite(cost) and cost > 0:
                                metadata["cost_usd"] = float(cost)
                        except Exception:
                            pass
                    yield SimpleNamespace(content=content, usage_metadata=usage, response_metadata=metadata)
                return
            except Exception as error:
                if emitted or attempt or not self.fallback or not _recoverable(error):
                    raise


def _recoverable(error: Exception) -> bool:
    if isinstance(error, (TimeoutError, ConnectionError)):
        return True
    import litellm

    names = (
        "APIConnectionError", "InternalServerError", "RateLimitError",
        "ServiceUnavailableError", "Timeout",
    )
    types = tuple(
        candidate for name in names
        if isinstance((candidate := getattr(litellm, name, None)), type)
        and issubclass(candidate, Exception)
    )
    if not types:
        raise RuntimeError("LiteLLM transient error types are unavailable.")
    return isinstance(error, types)


def _usage(usage: Any) -> dict[str, int]:
    if usage is None:
        return {}
    result = {}
    for source, target in (("prompt_tokens", "input_tokens"), ("completion_tokens", "output_tokens"), ("total_tokens", "total_tokens")):
        value = getattr(usage, source, None)
        if isinstance(value, int) and value >= 0:
            result[target] = value
    return result
