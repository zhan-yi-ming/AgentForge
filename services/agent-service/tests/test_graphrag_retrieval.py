from uuid import uuid4
import httpx
import pytest

from agentforge_agent.core_client import CoreApiClient
from agentforge_agent.embeddings import HashEmbeddingProvider
from agentforge_agent.errors import RagDependencyError
from agentforge_agent.rag_store import StoredChunk
from agentforge_agent.retrieval import RetrievalService
from agentforge_agent.schemas import GraphEntity, GraphEvidence, GraphMatch, RagSource, RagSourcesResponse


def test_relation_question_includes_graph_evidence_and_document_citation() -> None:
    project_id, user_id, graph_wiki_id, text_wiki_id = (uuid4() for _ in range(4))
    relation_id, service_id, api_id = (uuid4() for _ in range(3))
    graph_source = RagSource(sourceType="WIKI", sourceId=graph_wiki_id, version=2,
                             title="Architecture", content="Core exposes /tasks.")
    text_source = RagSource(sourceType="WIKI", sourceId=text_wiki_id, version=1,
                            title="Release", content="Release checklist for the team.")
    match = GraphMatch(
        hop=1, relationId=relation_id, relationType="EXPOSES",
        **{"from": GraphEntity(entityId=service_id, entityType="SERVICE", displayName="Core"),
           "to": GraphEntity(entityId=api_id, entityType="API", displayName="Tasks")},
        evidence=GraphEvidence(sourceType="WIKI", sourceId=graph_wiki_id,
                               sourceVersion=2, excerpt="Core exposes /tasks", confidence=0.8),
    )

    class CoreBoundary:
        def fetch_sources(self, *_args):
            return RagSourcesResponse(projectId=project_id, snapshotVersion=1,
                                      sourcesChanged=True, sources=[graph_source, text_source],
                                      requestId="graph-test")

        def fetch_graph(self, *_args):
            return [match]

    class IndexBoundary:
        def __init__(self):
            self.graph_loads = 0

        def snapshot_version(self, _project_id):
            return None

        def synchronize(self, *_args):
            pass

        def search(self, *_args):
            chunk = StoredChunk("text", project_id, "WIKI", text_wiki_id, 1, 0,
                                "Release", "Release checklist for the team.")
            return {"text": chunk}, ["text"], ["text"]

        def load_graph_evidence(self, _project_id, _snapshot_version, matches):
            self.graph_loads += 1
            assert matches == [match]
            return {
                (match.evidence.source_type, match.evidence.source_id, match.evidence.source_version):
                    StoredChunk("graph-source", project_id, "WIKI", graph_wiki_id, 2, 0,
                                "Architecture", "Core exposes /tasks.")
            }

    index = IndexBoundary()
    result = RetrievalService(CoreBoundary(), index, HashEmbeddingProvider(384),
                              top_k=6, candidate_k=6, context_char_budget=600).retrieve(
                                  project_id, user_id, False, "Core API impact", "request-1")

    assert index.graph_loads == 1
    assert "Core EXPOSES Tasks" in result.context
    assert "Core exposes /tasks" in result.context
    assert [source.source_id for source in result.sources] == [text_wiki_id, graph_wiki_id]
    assert "【来源2】" in result.context


def test_core_graph_contract_degrades_only_dependency_failure(monkeypatch) -> None:
    project_id, user_id = uuid4(), uuid4()
    client = CoreApiClient("http://core.invalid", "test-only-core-token", 2)
    requests = []

    def unavailable(url, **kwargs):
        requests.append((url, kwargs))
        return httpx.Response(503, request=httpx.Request("POST", url))

    monkeypatch.setattr(httpx, "post", unavailable)
    assert client.fetch_graph(str(project_id), str(user_id), False, "request-1", "Core impact") == []
    assert requests[0][1]["json"]["projectId"] == str(project_id)
    assert requests[0][1]["headers"]["X-AgentForge-Core-Internal-Token"] == "test-only-core-token"

    def forbidden(url, **_kwargs):
        return httpx.Response(403, request=httpx.Request("POST", url))

    monkeypatch.setattr(httpx, "post", forbidden)
    with pytest.raises(RagDependencyError):
        client.fetch_graph(str(project_id), str(user_id), False, "request-1", "Core impact")

    def wrong_correlation(url, **_kwargs):
        return httpx.Response(200, request=httpx.Request("POST", url), json={
            "projectId": str(uuid4()), "requestId": "request-1", "matches": [],
        })

    monkeypatch.setattr(httpx, "post", wrong_correlation)
    with pytest.raises(RagDependencyError):
        client.fetch_graph(str(project_id), str(user_id), False, "request-1", "Core impact")


def test_graph_relation_keeps_one_context_slot_when_text_has_both_rankings() -> None:
    project_id, user_id, graph_id, text_a_id, text_b_id = (uuid4() for _ in range(5))
    graph_source = RagSource(sourceType="WIKI", sourceId=graph_id, version=0,
                             title="Graph evidence", content="Core affects Billing.")
    text_sources = [
        RagSource(sourceType="WIKI", sourceId=text_a_id, version=0,
                  title="Text A", content="Core design notes"),
        RagSource(sourceType="WIKI", sourceId=text_b_id, version=0,
                  title="Text B", content="Core release notes"),
    ]
    match = GraphMatch(hop=1, relationId=uuid4(), relationType="AFFECTS",
                       **{"from": GraphEntity(entityId=uuid4(), entityType="ISSUE", displayName="Core"),
                          "to": GraphEntity(entityId=uuid4(), entityType="SERVICE", displayName="Billing")},
                       evidence=GraphEvidence(sourceType="WIKI", sourceId=graph_id,
                                              sourceVersion=0, excerpt="Core affects Billing", confidence=0.9))

    class CoreBoundary:
        def fetch_sources(self, *_args):
            return RagSourcesResponse(projectId=project_id, snapshotVersion=1,
                                      sourcesChanged=True, sources=[graph_source, *text_sources],
                                      requestId="graph-test")

        def fetch_graph(self, *_args):
            return [match]

    class IndexBoundary:
        def snapshot_version(self, _project_id):
            return None

        def synchronize(self, *_args):
            pass

        def search(self, *_args):
            chunks = {
                "a": StoredChunk("a", project_id, "WIKI", text_a_id, 0, 0, "Text A", "Core design notes"),
                "b": StoredChunk("b", project_id, "WIKI", text_b_id, 0, 0, "Text B", "Core release notes"),
            }
            return chunks, ["a", "b"], ["a", "b"]

        def load_graph_evidence(self, _project_id, _snapshot_version, matches):
            assert matches == [match]
            return {
                (match.evidence.source_type, match.evidence.source_id, match.evidence.source_version):
                    StoredChunk("graph-source", project_id, "WIKI", graph_id, 0, 0,
                                "Graph evidence", "Core affects Billing.")
            }

    result = RetrievalService(CoreBoundary(), IndexBoundary(), HashEmbeddingProvider(384),
                              top_k=2, candidate_k=2, context_char_budget=500).retrieve(
                                  project_id, user_id, False, "Core impact", "request-2")
    assert "Core AFFECTS Billing" in result.context
    assert len(result.sources) == 2


def test_unchanged_snapshot_skips_full_sync_and_still_validates_bounded_graph_evidence() -> None:
    project_id, user_id, graph_wiki_id = (uuid4() for _ in range(3))
    match = GraphMatch(
        hop=1, relationId=uuid4(), relationType="EXPOSES",
        **{"from": GraphEntity(entityId=uuid4(), entityType="SERVICE", displayName="Core"),
           "to": GraphEntity(entityId=uuid4(), entityType="API", displayName="Tasks")},
        evidence=GraphEvidence(sourceType="WIKI", sourceId=graph_wiki_id,
                               sourceVersion=2, excerpt="Core exposes /tasks", confidence=0.8),
    )

    class CoreBoundary:
        def __init__(self):
            self.known_snapshot_version = None

        def fetch_sources(self, _project, _user, _admin, _request, known_snapshot_version):
            self.known_snapshot_version = known_snapshot_version
            return RagSourcesResponse(
                projectId=project_id,
                snapshotVersion=7,
                sourcesChanged=False,
                sources=[],
                requestId="graph-test",
            )

        def fetch_graph(self, *_args):
            return [match]

    class IndexBoundary:
        def snapshot_version(self, _project_id):
            return 7

        def synchronize(self, *_args):
            raise AssertionError("unchanged snapshots must not enter the synchronization lock")

        def search(self, *_args):
            return {}, [], []

        def load_graph_evidence(self, _project_id, _snapshot_version, matches):
            assert matches == [match]
            return {
                (match.evidence.source_type, match.evidence.source_id, match.evidence.source_version):
                    StoredChunk("graph-source", project_id, "WIKI", graph_wiki_id, 2, 0,
                                "Architecture", "Core exposes /tasks.")
            }

    core = CoreBoundary()
    result = RetrievalService(
        core, IndexBoundary(), HashEmbeddingProvider(384),
        top_k=6, candidate_k=6, context_char_budget=600,
    ).retrieve(project_id, user_id, False, "Core API impact", "request-1")

    assert core.known_snapshot_version == 7
    assert "Core EXPOSES Tasks" in result.context
    assert result.sources[0].title == "Architecture"


def test_core_source_contract_sends_known_snapshot_and_rejects_invalid_unchanged_response(
        monkeypatch) -> None:
    project_id, user_id = uuid4(), uuid4()
    client = CoreApiClient("http://core.invalid", "test-only-core-token", 2)
    requests = []

    def unchanged(url, **kwargs):
        requests.append((url, kwargs))
        return httpx.Response(200, request=httpx.Request("POST", url), json={
            "projectId": str(project_id),
            "snapshotVersion": 7,
            "sourcesChanged": False,
            "sources": [],
            "requestId": "request-1",
        })

    monkeypatch.setattr(httpx, "post", unchanged)
    response = client.fetch_sources(
        str(project_id), str(user_id), False, "request-1", known_snapshot_version=7,
    )
    assert response.sources_changed is False
    assert requests[0][1]["json"]["knownSnapshotVersion"] == 7

    def mismatched(url, **_kwargs):
        return httpx.Response(200, request=httpx.Request("POST", url), json={
            "projectId": str(project_id),
            "snapshotVersion": 8,
            "sourcesChanged": False,
            "sources": [],
            "requestId": "request-1",
        })

    monkeypatch.setattr(httpx, "post", mismatched)
    with pytest.raises(RagDependencyError):
        client.fetch_sources(
            str(project_id), str(user_id), False, "request-1", known_snapshot_version=7,
        )
