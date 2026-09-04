from functools import lru_cache

from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="AGENTFORGE_AGENT_", extra="ignore")

    internal_token: SecretStr = Field(min_length=16)
    service_name: str = "agentforge-agent-service"


@lru_cache
def get_settings() -> Settings:
    return Settings()
