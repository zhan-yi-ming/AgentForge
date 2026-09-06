from functools import lru_cache

from typing import Literal

from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="AGENTFORGE_AGENT_", extra="ignore")

    internal_token: SecretStr = Field(min_length=16)
    core_internal_token: SecretStr = Field(
        validation_alias="AGENTFORGE_CORE_INTERNAL_TOKEN",
        min_length=16,
    )
    service_name: str = "agentforge-agent-service"
    core_api_url: str = "http://localhost:8080"
    rag_db_dsn: SecretStr
    rag_enabled: bool = True
    embedding_provider: Literal["hash"] = "hash"
    embedding_dimensions: int = Field(default=384, ge=384, le=384)
    llm_provider: Literal["disabled", "deepseek", "zhipu", "qwen"] = "disabled"
    llm_api_key: SecretStr | None = None
    llm_base_url: str | None = None
    llm_model: str | None = None
    llm_max_tokens: int = Field(default=800, ge=64, le=4096)
    request_timeout_seconds: float = Field(default=10.0, gt=0, le=60)
    rag_top_k: int = Field(default=6, ge=1, le=20)
    rag_candidate_k: int = Field(default=12, ge=1, le=50)
    rag_context_char_budget: int = Field(default=4000, ge=500, le=12000)


@lru_cache
def get_settings() -> Settings:
    return Settings()
