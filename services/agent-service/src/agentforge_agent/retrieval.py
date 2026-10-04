from dataclasses import dataclass
import re
from uuid import UUID

from .config import Settings
from .core_client import CoreApiClient
from .embeddings import HashEmbeddingProvider
from .rag_store import RagStore, StoredChunk
from .ranking import RankableChunk, bm25_rank, reciprocal_rank_fusion
from .repository_context import RepositoryContextProvider
from .schemas import ChatSource, GraphMatch, RagSource


@dataclass(frozen=True)
class RetrievalResult:
    context: str
    sources: list[ChatSource]
    task_targets: tuple["TaskTarget", ...] = ()


@dataclass(frozen=True)
class TaskTarget:
    task_id: UUID
    version: int
    title: str


class RetrievalService:
    def __init__(
        self,
        core_client: CoreApiClient,
        store: RagStore,
        embedder,
        top_k: int,
        candidate_k: int,
        context_char_budget: int,
        repository_provider: RepositoryContextProvider | None = None,
    ) -> None:
        self.core_client = core_client
        self.store = store
        self.embedder = embedder
        self.top_k = top_k
        self.candidate_k = candidate_k
        self.context_char_budget = context_char_budget
        self.repository_provider = repository_provider

    @classmethod
    def from_settings(cls, settings: Settings) -> "RetrievalService":
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
            repository_provider=RepositoryContextProvider.from_settings(settings),
        )

    def retrieve(
        self,
        project_id: UUID,
        user_id: UUID,
        actor_admin: bool,
        query: str,
        request_id: str,
    ) -> RetrievalResult:
        snapshot = self.core_client.fetch_sources(
            str(project_id),
            str(user_id),
            actor_admin,
            request_id,
        )
        sources = snapshot.sources
        self.store.synchronize(project_id, snapshot.snapshot_version, sources, self.embedder)
        query_embedding = self.embedder.embed([query])[0]
        chunks, vector_ids, lexical_ids = self.store.search(
            project_id,
            snapshot.snapshot_version,
            query,
            query_embedding,
            self.candidate_k,
        )
        graph_matches = self.core_client.fetch_graph(
            str(project_id), str(user_id), actor_admin, request_id, query,
        ) if query.strip() else []
        graph_chunks = _graph_chunks(project_id, sources, graph_matches)
        chunks.update({chunk.id: chunk for chunk in graph_chunks})
        graph_ids = [chunk.id for chunk in graph_chunks]
        repository_matches = (
            self.repository_provider.retrieve(project_id, query)
            if self.repository_provider is not None and query.strip() else []
        )
        repository_chunks = [
            StoredChunk(f"repository:{match.source_id}", project_id, "REPOSITORY",
                        match.source_id, 0, 0, match.title,
                        f"Repository HEAD {match.revision[:12]}\n{match.content}")
            for match in repository_matches
        ]
        chunks.update({chunk.id: chunk for chunk in repository_chunks})
        repository_ids = bm25_rank(
            query, [RankableChunk(chunk.id, f"{chunk.title}\n{chunk.content}")
                    for chunk in repository_chunks], self.candidate_k,
        )
        ranked_ids = reciprocal_rank_fusion(
            [vector_ids, lexical_ids, graph_ids, repository_ids], self.top_k,
        )
        if graph_ids and ranked_ids and not any(chunk_id in graph_ids for chunk_id in ranked_ids):
            ranked_ids[-1] = graph_ids[0]
        if repository_ids and ranked_ids and not any(chunk_id in repository_ids for chunk_id in ranked_ids):
            if len(ranked_ids) > 1 and ranked_ids[-1] in graph_ids:
                ranked_ids[-2] = repository_ids[0]
            else:
                ranked_ids[-1] = repository_ids[0]
        ranked_chunks = [chunks[chunk_id] for chunk_id in ranked_ids if chunk_id in chunks]
        context, included = _build_context(ranked_chunks, self.context_char_budget)
        return RetrievalResult(
            context=context,
            sources=_deduplicate_sources(included),
            task_targets=_task_targets([chunk for chunk in included if not chunk.id.startswith("graph:")]),
        )


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
    source_numbers: dict[tuple[str, UUID], int] = {}
    used = 0
    for chunk in chunks:
        source_key = (chunk.source_type, chunk.source_id)
        number = source_numbers.get(source_key, len(source_numbers) + 1)
        prefix = f"【来源{number}】 [{chunk.source_type}:{chunk.source_id}] {chunk.title}\n"
        content = chunk.content.strip()
        remaining = char_budget - used
        if remaining <= 0:
            break
        if not content or remaining <= len(prefix):
            break
        block = prefix + content
        if len(block) > remaining:
            block = block[:remaining].rstrip()
        if block:
            blocks.append(block)
            included.append(chunk)
            source_numbers[source_key] = number
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
        content = chunk.content.split("\n", 1)[0] if chunk.id.startswith("graph:") else chunk.content
        excerpt = " ".join(content.split())[:240]
        result.append(ChatSource(
            source_type=chunk.source_type,
            source_id=chunk.source_id,
            title=chunk.title,
            excerpt=excerpt,
        ))
    return result


def _graph_chunks(project_id, sources: list[RagSource], matches: list[GraphMatch]) -> list[StoredChunk]:
    authorized = {(source.source_type, source.source_id): source for source in sources}
    result: list[StoredChunk] = []
    seen: set[str] = set()
    for match in matches[:40]:
        evidence = match.evidence
        source = authorized.get((evidence.source_type, evidence.source_id))
        if source is None or source.version != evidence.source_version or evidence.excerpt not in source.content:
            continue
        identity = f"graph:{match.relation_id}:{evidence.source_id}"
        if identity in seen:
            continue
        seen.add(identity)
        content = (f"{evidence.excerpt}\nGraph relation: "
                   f"{match.from_entity.display_name} {match.relation_type} "
                   f"{match.to_entity.display_name} (hop {match.hop}, confidence {evidence.confidence:.2f}).")
        result.append(StoredChunk(identity, project_id, source.source_type, source.source_id,
                                  source.version, 0, source.title, content))
    return result


def cited_sources(answer: str, candidates: list[ChatSource] | tuple[ChatSource, ...]) -> list[ChatSource]:
    """Return only authorized sources explicitly numbered in the completed answer."""
    result: list[ChatSource] = []
    seen: set[int] = set()
    for match in re.finditer(r"【来源([1-9][0-9]*)】", answer):
        index = int(match.group(1)) - 1
        if 0 <= index < len(candidates) and index not in seen:
            result.append(candidates[index])
            seen.add(index)
    return result


def _task_targets(chunks: list[StoredChunk]) -> tuple[TaskTarget, ...]:
    result: list[TaskTarget] = []
    seen: set[UUID] = set()
    for chunk in chunks:
        if chunk.source_type == "TASK" and chunk.source_id not in seen:
            result.append(TaskTarget(chunk.source_id, chunk.source_version, chunk.title))
            seen.add(chunk.source_id)
    return tuple(result)
