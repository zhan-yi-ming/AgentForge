from agentforge_agent.embeddings import HashEmbeddingProvider
from agentforge_agent.evaluation.dataset import CorpusSource
from agentforge_agent.ranking import RankableChunk, bm25_rank, reciprocal_rank_fusion
from agentforge_agent.schemas import ToolProposal
from agentforge_agent.tool_planner import plan_tool


class CurrentCodeSubject:
    """Deterministic offline adapter over the current production algorithms."""

    def __init__(self, embedding_dimensions: int = 384) -> None:
        self.embedder = HashEmbeddingProvider(embedding_dimensions)

    def retrieve(self, query: str, corpus: tuple[CorpusSource, ...]) -> list[str]:
        rankable = [
            RankableChunk(source.id, f"{source.title}\n{source.content}")
            for source in corpus
        ]
        lexical = bm25_rank(query, rankable, len(rankable))
        query_vector = self.embedder.embed([query])[0]
        document_vectors = self.embedder.embed([chunk.content for chunk in rankable])
        vector = [
            chunk.id
            for _, chunk in sorted(
                zip(document_vectors, rankable, strict=True),
                key=lambda item: (
                    -_dot(query_vector, item[0]),
                    item[1].id,
                ),
            )
        ]
        return reciprocal_rank_fusion([vector, lexical], len(rankable))

    def plan_tool(self, message: str) -> ToolProposal | None:
        return plan_tool(message)


def _dot(left: list[float], right: list[float]) -> float:
    return sum(a * b for a, b in zip(left, right, strict=True))
