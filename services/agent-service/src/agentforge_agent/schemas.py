from uuid import UUID
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, ValidationError
from pydantic.alias_generators import to_camel


class ApiModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class ChatRequest(ApiModel):
    project_id: UUID
    user_id: UUID
    actor_admin: bool = False
    message: str = Field(min_length=1, max_length=16000)
    conversation_id: UUID | None = None
    task_type: Literal["FORMAT", "REWRITE", "PLAN", "REVIEW", "ANSWER"] | None = "ANSWER"
    request_id: str = Field(min_length=1, max_length=128)


class ChatSource(ApiModel):
    source_type: Literal["WIKI", "TASK", "REPOSITORY"]
    source_id: UUID
    title: str
    excerpt: str


class ToolProposal(ApiModel):
    action_type: Literal["CREATE_TASK", "UPDATE_TASK"]
    action_workflow_id: UUID | None = None
    task_id: UUID | None = None
    expected_version: int | None = Field(default=None, ge=0)
    title: Annotated[
        str, StringConstraints(strip_whitespace=True, min_length=1, max_length=200)
    ] | None = None
    description: Annotated[
        str, StringConstraints(strip_whitespace=True, min_length=1, max_length=2000)
    ] | None = None
    status: Literal["TODO", "IN_PROGRESS", "DONE"] | None = None
    priority: Literal["LOW", "MEDIUM", "HIGH"] | None = None


def tool_proposal_or_none(**fields: object) -> ToolProposal | None:
    try:
        return ToolProposal(**fields)
    except ValidationError:
        return None


class ChatResponse(ApiModel):
    conversation_id: UUID
    answer: str
    request_id: str
    sources: list[ChatSource] = Field(default_factory=list)
    tool_proposal: ToolProposal | None = None


class ResumeRequest(ApiModel):
    project_id: UUID
    user_id: UUID
    actor_admin: bool = False
    conversation_id: UUID
    action_workflow_id: UUID | None = None
    action_id: UUID
    decision: Literal["APPROVE", "REJECT"]
    idempotency_key: str = Field(
        min_length=1,
        max_length=100,
        pattern=r"^[A-Za-z0-9._:-]+$",
    )
    request_id: str = Field(min_length=1, max_length=128)


class ResumeResponse(ApiModel):
    conversation_id: UUID
    action_workflow_id: UUID | None = None
    action_id: UUID
    decision: Literal["APPROVE", "REJECT"]
    status: Literal["RESUMED"]
    request_id: str


class AbortRequest(ApiModel):
    project_id: UUID
    user_id: UUID
    actor_admin: bool = False
    conversation_id: UUID
    action_workflow_id: UUID | None = None
    request_id: str = Field(min_length=1, max_length=128)


class AbortResponse(ApiModel):
    conversation_id: UUID
    action_workflow_id: UUID
    status: Literal["ABORTED"]
    request_id: str


class RagSource(ApiModel):
    source_type: Literal["WIKI", "TASK"]
    source_id: UUID
    version: int = Field(ge=0)
    title: str
    content: str


class RagSourcesResponse(ApiModel):
    project_id: UUID
    snapshot_version: int = Field(ge=0)
    sources_changed: bool
    sources: list[RagSource]
    request_id: str


class GraphEntity(ApiModel):
    entity_id: UUID
    entity_type: Literal["PROJECT", "SERVICE", "API", "WIKI", "TASK", "ISSUE"]
    display_name: str = Field(min_length=1, max_length=200)
    canonical_entity_id: UUID | None = None


class GraphEvidence(ApiModel):
    source_type: Literal["WIKI", "TASK"]
    source_id: UUID
    source_version: int = Field(ge=0)
    excerpt: str = Field(min_length=1, max_length=2000)
    confidence: float = Field(ge=0, le=1, allow_inf_nan=False)


class GraphMatch(ApiModel):
    hop: int = Field(ge=1, le=2)
    relation_id: UUID
    relation_type: Literal["CONTAINS", "EXPOSES", "DESCRIBES", "MODIFIES", "AFFECTS"]
    from_entity: GraphEntity = Field(alias="from")
    to_entity: GraphEntity = Field(alias="to")
    evidence: GraphEvidence


class GraphRetrievalResponse(ApiModel):
    project_id: UUID
    request_id: str
    matches: list[GraphMatch] = Field(max_length=40)


class HealthResponse(ApiModel):
    status: str
    service: str
