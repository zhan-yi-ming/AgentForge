from uuid import UUID
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


class ApiModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class ChatRequest(ApiModel):
    project_id: UUID
    user_id: UUID
    actor_admin: bool = False
    message: str = Field(min_length=1, max_length=8000)
    conversation_id: UUID | None = None
    request_id: str = Field(min_length=1, max_length=128)


class ChatSource(ApiModel):
    source_type: Literal["WIKI", "TASK"]
    source_id: UUID
    title: str
    excerpt: str


class ToolProposal(ApiModel):
    action_type: Literal["CREATE_TASK", "UPDATE_TASK"]
    task_id: UUID | None = None
    expected_version: int | None = Field(default=None, ge=0)
    title: str | None = None
    description: str | None = None
    status: Literal["TODO", "IN_PROGRESS", "DONE"] | None = None
    priority: Literal["LOW", "MEDIUM", "HIGH"] | None = None


class ChatResponse(ApiModel):
    conversation_id: UUID
    answer: str
    request_id: str
    sources: list[ChatSource] = Field(default_factory=list)
    tool_proposal: ToolProposal | None = None


class RagSource(ApiModel):
    source_type: Literal["WIKI", "TASK"]
    source_id: UUID
    version: int = Field(ge=0)
    title: str
    content: str


class RagSourcesResponse(ApiModel):
    project_id: UUID
    sources: list[RagSource]
    request_id: str


class HealthResponse(ApiModel):
    status: str
    service: str
