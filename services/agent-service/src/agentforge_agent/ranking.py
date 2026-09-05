from collections import Counter, defaultdict
from dataclasses import dataclass
import math

from .embeddings import tokenize


@dataclass(frozen=True)
class RankableChunk:
    id: str
    content: str


def bm25_rank(query: str, chunks: list[RankableChunk], limit: int) -> list[str]:
    query_terms = tokenize(query)
    if not query_terms or not chunks:
        return []

    documents = [tokenize(chunk.content) for chunk in chunks]
    average_length = sum(len(document) for document in documents) / len(documents)
    document_frequency = Counter(term for document in documents for term in set(document))
    scores: list[tuple[str, float]] = []
    k1 = 1.5
    b = 0.75
    for chunk, document in zip(chunks, documents, strict=True):
        frequencies = Counter(document)
        score = 0.0
        for term in query_terms:
            frequency = frequencies[term]
            if frequency == 0:
                continue
            inverse_frequency = math.log(1 + (len(documents) - document_frequency[term] + 0.5) /
                                         (document_frequency[term] + 0.5))
            denominator = frequency + k1 * (1 - b + b * len(document) / max(average_length, 1.0))
            score += inverse_frequency * frequency * (k1 + 1) / denominator
        if score > 0:
            scores.append((chunk.id, score))
    scores.sort(key=lambda item: (-item[1], item[0]))
    return [chunk_id for chunk_id, _ in scores[:limit]]


def reciprocal_rank_fusion(rankings: list[list[str]], limit: int, rank_constant: int = 60) -> list[str]:
    scores: defaultdict[str, float] = defaultdict(float)
    best_rank: dict[str, int] = {}
    for ranking in rankings:
        for rank, chunk_id in enumerate(ranking, start=1):
            scores[chunk_id] += 1.0 / (rank_constant + rank)
            best_rank[chunk_id] = min(best_rank.get(chunk_id, rank), rank)
    ordered = sorted(scores, key=lambda chunk_id: (-scores[chunk_id], best_rank[chunk_id], chunk_id))
    return ordered[:limit]
