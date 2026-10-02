from functools import lru_cache

from fastapi import HTTPException, status

from .config import get_settings
from .errors import LlmDependencyError
from .llm import build_responder


@lru_cache
def get_responder():
    try:
        return build_responder(get_settings())
    except LlmDependencyError as exception:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="LLM provider is unavailable.",
        ) from exception
