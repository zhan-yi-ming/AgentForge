from uuid import uuid4

from agentforge_agent.chunking import chunk_source
from agentforge_agent.embeddings import HashEmbeddingProvider
from agentforge_agent.ranking import RankableChunk, bm25_rank, reciprocal_rank_fusion
from agentforge_agent.retrieval import _build_context, _deduplicate_sources, _task_targets, cited_sources
from agentforge_agent.rag_store import StoredChunk
from agentforge_agent.schemas import ChatSource, RagSource


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
    assert context.startswith("【来源1】")
    assert len(included) == 1
    assert len(sources) == 1
    assert sources[0].source_id == source_id


def test_plain_list_number_is_not_treated_as_a_source_citation() -> None:
    source = ChatSource(source_type="WIKI", source_id=uuid4(), title="Architecture", excerpt="Java")
    second = ChatSource(source_type="TASK", source_id=uuid4(), title="Release", excerpt="QA")

    assert cited_sources("步骤【1】先检查日志。", [source]) == []
    assert cited_sources("Java 负责写入。【来源1】", [source]) == [source]
    assert cited_sources("仅发布任务要求复核。【来源2】", [source, second]) == [second]


def test_update_targets_come_only_from_included_task_chunks() -> None:
    project_id = uuid4()
    task_id = uuid4()
    chunks = [
        StoredChunk("wiki", project_id, "WIKI", uuid4(), 8, 0, "Wiki", "Documentation"),
        StoredChunk("task", project_id, "TASK", task_id, 3, 0, "Login", "Check rollout"),
    ]

    targets = _task_targets(chunks)

    assert [(target.task_id, target.version, target.title) for target in targets] == [
        (task_id, 3, "Login")
    ]
