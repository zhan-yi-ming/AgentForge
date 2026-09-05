from dataclasses import dataclass
from uuid import UUID

from .config import Settings
from .core_client import CoreApiClient
from .embeddings import HashEmbeddingProvider, OpenAIEmbeddingProvider
from .rag_store import RagStore, StoredChunk
from .ranking import reciprocal_rank_fusion
from .schemas import ChatSource


@dataclass(frozen=True)
class RetrievalResult:
    context: str
    sources: list[ChatSource]


class RetrievalService:
    def __init__(
        self,
        core_client: CoreApiClient,
        store: RagStore,
        embedder,
        top_k: int,
        candidate_k: int,
        context_char_budget: int,
    ) -> None:
        self.core_client = core_client
        self.store = store
        self.embedder = embedder
        self.top_k = top_k
        self.candidate_k = candidate_k
        self.context_char_budget = context_char_budget

    @classmethod
    def from_settings(cls, settings: Settings) -> "RetrievalService":
        if settings.embedding_provider == "openai":
            key = settings.openai_api_key.get_secret_value() if settings.openai_api_key else ""
            embedder = OpenAIEmbeddingProvider(
                key,
                settings.openai_base_url,
                settings.openai_embedding_model,
                settings.embedding_dimensions,
                settings.request_timeout_seconds,
            )
        else:
            embedder = HashEmbeddingProvider(settings.embedding_dimensions)
        return cls(
            core_client=CoreApiClient(
                settings.core_api_url,
                settings.core_internal_token.get_secret_value(),
                settings.request_timeout_seconds,
            ),
            store=RagStore(settings.rag_db_dsn.get_secret_value()),
            embedder=embedder,
            top_k=settings.rag_top_k,
            candidate_k=settings.rag_candidate_k,
            context_char_budget=settings.rag_context_char_budget,
        )

    def retrieve(
        self,
        project_id: UUID,
        user_id: UUID,
        actor_admin: bool,
        query: str,
        request_id: str,
    ) -> RetrievalResult:
        sources = self.core_client.fetch_sources(
            str(project_id),
            str(user_id),
            actor_admin,
            request_id,
        )
        self.store.synchronize(project_id, sources, self.embedder)
        query_embedding = self.embedder.embed([query])[0]
        chunks, vector_ids, lexical_ids = self.store.search(
            project_id,
            query,
            query_embedding,
            self.candidate_k,
        )
        ranked_ids = reciprocal_rank_fusion([vector_ids, lexical_ids], self.top_k)
        ranked_chunks = [chunks[chunk_id] for chunk_id in ranked_ids if chunk_id in chunks]
        context, included = _build_context(ranked_chunks, self.context_char_budget)
        return RetrievalResult(context=context, sources=_deduplicate_sources(included))


class DisabledRetrievalService:
    def retrieve(
        self,
        project_id: UUID,
        user_id: UUID,
        actor_admin: bool,
        query: str,
        request_id: str,
    ) -> RetrievalResult:
        return RetrievalResult(context="", sources=[])


def _build_context(chunks: list[StoredChunk], char_budget: int) -> tuple[str, list[StoredChunk]]:
    blocks: list[str] = []
    included: list[StoredChunk] = []
    used = 0
    for chunk in chunks:
        block = f"[{chunk.source_type}:{chunk.source_id}] {chunk.title}\n{chunk.content.strip()}"
        remaining = char_budget - used
        if remaining <= 0:
            break
        if len(block) > remaining:
            block = block[:remaining].rstrip()
        if block:
            blocks.append(block)
            included.append(chunk)
            used += len(block) + 2
    return "\n\n".join(blocks), included


def _deduplicate_sources(chunks: list[StoredChunk]) -> list[ChatSource]:
    result: list[ChatSource] = []
    seen: set[tuple[str, UUID]] = set()
    for chunk in chunks:
        key = (chunk.source_type, chunk.source_id)
        if key in seen:
            continue
        seen.add(key)
        excerpt = " ".join(chunk.content.split())[:240]
        result.append(ChatSource(
            source_type=chunk.source_type,
            source_id=chunk.source_id,
            title=chunk.title,
            excerpt=excerpt,
        ))
    return result
