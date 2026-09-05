from uuid import uuid4

from agentforge_agent.chunking import chunk_source
from agentforge_agent.embeddings import HashEmbeddingProvider
from agentforge_agent.ranking import RankableChunk, bm25_rank, reciprocal_rank_fusion
from agentforge_agent.retrieval import _build_context, _deduplicate_sources
from agentforge_agent.rag_store import StoredChunk
from agentforge_agent.schemas import RagSource


def test_chunk_source_is_stable_and_keeps_source_metadata() -> None:
    project_id = uuid4()
    source_id = uuid4()
    source = RagSource(
        sourceType="WIKI",
        sourceId=source_id,
        version=3,
        title="Architecture",
        content="# Core\n\nJava owns writes.\n\nPython owns retrieval.",
    )

    chunks = chunk_source(project_id, source, max_chars=32, overlap_chars=8)

    assert chunks
    assert [chunk.chunk_index for chunk in chunks] == list(range(len(chunks)))
    assert all(chunk.project_id == project_id for chunk in chunks)
    assert all(chunk.source_id == source_id and chunk.source_version == 3 for chunk in chunks)


def test_hash_embedding_is_normalized_and_repeatable() -> None:
    provider = HashEmbeddingProvider(64)
    first, second = provider.embed(["Java permissions", "Java permissions"])
    assert first == second
    assert abs(sum(value * value for value in first) - 1.0) < 1e-9


def test_bm25_and_rrf_rank_relevant_chunks_deterministically() -> None:
    chunks = [
        RankableChunk("wiki", "Java authentication permissions"),
        RankableChunk("task", "React markdown preview"),
    ]
    assert bm25_rank("authentication", chunks, 5) == ["wiki"]
    assert reciprocal_rank_fusion([["wiki", "task"], ["task", "wiki"]], 2) == ["task", "wiki"]


def test_context_budget_and_source_deduplication() -> None:
    source_id = uuid4()
    chunks = [
        StoredChunk("1", uuid4(), "WIKI", source_id, 1, 0, "Architecture", "first block"),
        StoredChunk("2", uuid4(), "WIKI", source_id, 1, 1, "Architecture", "second block"),
    ]
    context, included = _build_context(chunks, 80)
    sources = _deduplicate_sources(included)
    assert len(context) <= 80
    assert len(sources) == 1
    assert sources[0].source_id == source_id
