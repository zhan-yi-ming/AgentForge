"""Deployment-owned task routing; model output never chooses destinations."""
from typing import Literal
from pydantic import BaseModel, ConfigDict, Field

TaskType = Literal["FORMAT", "REWRITE", "PLAN", "REVIEW", "ANSWER"]

class ModelCandidate(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: str = Field(pattern=r"^[a-z][a-z0-9_-]{0,39}$")
    endpoint: Literal["primary", "fallback"]
    model: str = Field(min_length=1, max_length=100, pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]*$")
    tasks: list[TaskType] = Field(min_length=1, max_length=5)
    json_output: bool = False
    streaming: bool = False
    cost_rank: int = Field(ge=1, le=100)
    latency_rank: int = Field(ge=1, le=100)
    capability_rank: int = Field(ge=1, le=100)


def ordered(candidates, task):
    def key(item):
        if task == "FORMAT":
            return item.cost_rank, item.latency_rank, -item.capability_rank, item.name
        if task == "REWRITE":
            return item.latency_rank, item.cost_rank, -item.capability_rank, item.name
        return -item.capability_rank, item.cost_rank, item.latency_rank, item.name
    return sorted((item for item in candidates if task in item.tasks), key=key)


class RouteObservation:
    """Keep route and actual provider metadata together for every update."""
    def __init__(self, delegate):
        self.delegate = delegate
        self.metadata = {}

    def update(self, **values):
        if "metadata" in values:
            self.metadata.update(values["metadata"])
            values["metadata"] = dict(self.metadata)
        self.delegate.update(**values)


class RoutedResponder:
    def __init__(self, responders, decisions):
        self.responders = responders
        self.decisions = decisions

    def __call__(self, state):
        return self.respond_observed(state, None)

    def _select(self, state, observation):
        task = state["context_bundle"].working.task_type
        if observation is not None:
            observation = RouteObservation(observation)
            chosen = self.decisions[task]
            observation.update(metadata={"task_type": task, "route": chosen.name,
                "cost_rank": chosen.cost_rank, "latency_rank": chosen.latency_rank,
                "capability_rank": chosen.capability_rank})
        return self.responders[task], observation

    def respond_observed(self, state, observation):
        responder, observed = self._select(state, observation)
        return responder.respond_observed(state, observed)

    def stream(self, state):
        yield from self.stream_observed(state, None)

    def stream_observed(self, state, observation):
        responder, observed = self._select(state, observation)
        yield from responder.stream_observed(state, observed)

    def plan_tool(self, bundle):
        return self.responders["PLAN"].plan_tool(bundle)
