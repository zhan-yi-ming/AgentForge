from dataclasses import dataclass
import re

from agentforge_agent.embeddings import tokenize
from agentforge_agent.schemas import ToolProposal


@dataclass(frozen=True)
class RankingScore:
    recall_at_k: float
    reciprocal_rank: float
    hit_rate: float


@dataclass(frozen=True)
class ClaimScore:
    text: str
    token_support: float
    supported: bool


@dataclass(frozen=True)
class FaithfulnessScore:
    score: float
    claims: tuple[ClaimScore, ...]


@dataclass(frozen=True)
class ToolScore:
    selection_accuracy: float
    task_success: float


def score_ranking(
    retrieved_source_ids: list[str],
    relevant_source_ids: set[str],
    k: int,
) -> RankingScore:
    if k < 1:
        raise ValueError("k must be at least 1")
    if not relevant_source_ids:
        raise ValueError("relevant_source_ids must not be empty")

    top_k = retrieved_source_ids[:k]
    hits = relevant_source_ids.intersection(top_k)
    first_relevant_rank = next(
        (
            rank
            for rank, source_id in enumerate(top_k, start=1)
            if source_id in relevant_source_ids
        ),
        None,
    )
    return RankingScore(
        recall_at_k=len(hits) / len(relevant_source_ids),
        reciprocal_rank=0.0 if first_relevant_rank is None else 1.0 / first_relevant_rank,
        hit_rate=1.0 if hits else 0.0,
    )


def score_faithfulness(
    answer: str,
    context: str,
    support_threshold: float = 0.8,
) -> FaithfulnessScore:
    if not 0.0 <= support_threshold <= 1.0:
        raise ValueError("support_threshold must be between 0 and 1")

    claim_texts = [
        claim.strip()
        for claim in re.split(r"(?<=[.!?。！？])\s*", answer.strip())
        if claim.strip()
    ]
    if not claim_texts:
        return FaithfulnessScore(score=0.0, claims=())

    context_tokens = set(tokenize(context))
    claims: list[ClaimScore] = []
    for claim in claim_texts:
        claim_tokens = set(tokenize(claim))
        token_support = (
            0.0
            if not claim_tokens
            else len(claim_tokens.intersection(context_tokens)) / len(claim_tokens)
        )
        claims.append(ClaimScore(
            text=claim,
            token_support=token_support,
            supported=token_support >= support_threshold,
        ))
    supported_count = sum(claim.supported for claim in claims)
    return FaithfulnessScore(
        score=supported_count / len(claims),
        claims=tuple(claims),
    )


def score_tool_result(
    actual: ToolProposal | None,
    expected: dict[str, object] | None,
) -> ToolScore:
    actual_payload = (
        None
        if actual is None
        else actual.model_dump(mode="json", by_alias=True, exclude_none=True)
    )
    actual_action = None if actual_payload is None else actual_payload["actionType"]
    expected_action = None if expected is None else expected.get("actionType")
    return ToolScore(
        selection_accuracy=1.0 if actual_action == expected_action else 0.0,
        task_success=1.0 if actual_payload == expected else 0.0,
    )
