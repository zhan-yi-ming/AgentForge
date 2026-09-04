from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


class ApiModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class ChatRequest(ApiModel):
    project_id: UUID
    user_id: UUID
    message: str = Field(min_length=1, max_length=8000)
    conversation_id: UUID | None = None
    request_id: str = Field(min_length=1, max_length=128)


class ChatResponse(ApiModel):
    conversation_id: UUID
    answer: str
    request_id: str


class HealthResponse(ApiModel):
    status: str
    service: str
