import subprocess
import json
from uuid import NAMESPACE_URL, uuid4, uuid5

import pytest

from agentforge_agent.config import Settings
from agentforge_agent.embeddings import HashEmbeddingProvider
from agentforge_agent.repository_context import RepositoryContextProvider
from agentforge_agent.retrieval import RetrievalService
from agentforge_agent.schemas import RagSourcesResponse


def _git(root, *args):
    return subprocess.run(
        ["git", "-C", str(root), *args], check=True, capture_output=True, text=True,
    ).stdout.strip()


def test_repository_context_reads_committed_head_not_staged_or_untracked(tmp_path):
    project_id = uuid4()
    repo = tmp_path / "repo"
    repo.mkdir()
    _git(repo, "init", "-q")
    (repo / "README.md").write_text("Core entry is services/core-api.\n", encoding="utf-8")
    _git(repo, "add", "--", "README.md")
    _git(repo, "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
         "commit", "-qm", "Document Core entry")
    (repo / "README.md").write_text("Uncommitted replacement\n", encoding="utf-8")
    _git(repo, "add", "--", "README.md")
    (repo / "notes.txt").write_text("Untracked private note\n", encoding="utf-8")

    matches = RepositoryContextProvider({project_id: repo}).retrieve(project_id, "Core entry")

    assert any("Core entry is services/core-api." in match.content for match in matches)
    assert all("Uncommitted replacement" not in match.content for match in matches)
    assert all("Untracked private note" not in match.content for match in matches)
    assert all(match.source_type == "REPOSITORY" for match in matches)


def test_repository_context_whitelists_documents_and_excludes_secrets(tmp_path):
    project_id = uuid4()
    repo = tmp_path / "repo"
    (repo / "docs" / "04-api").mkdir(parents=True)
    (repo / "src").mkdir()
    _git(repo, "init", "-q")
    (repo / "README.md").write_text("AgentForge project overview\n", encoding="utf-8")
    (repo / "docs" / "04-api" / "core-api.md").write_text(
        "Core API routes for projects\n", encoding="utf-8")
    (repo / "pom.xml").write_text("<project>core-api module</project>\n", encoding="utf-8")
    (repo / "src" / "private.py").write_text("Internal implementation\n", encoding="utf-8")
    (repo / ".env").write_text("PRIVATE_VALUE=hidden\n", encoding="utf-8")
    (repo / "docs" / "04-api" / "secret.md").write_text(
        "api_key = sk-" + "A" * 28 + "\n", encoding="utf-8")
    _git(repo, "add", "--", ".")
    _git(repo, "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
         "commit", "-qm", "Document API routes")

    matches = RepositoryContextProvider({project_id: repo}).retrieve(project_id, "Core API")
    titles = {match.title for match in matches}

    assert "docs/04-api/core-api.md" in titles
    assert "pom.xml" in titles
    assert "src/private.py" not in titles
    assert ".env" not in titles
    assert "docs/04-api/secret.md" not in titles
    assert all("PRIVATE_VALUE" not in match.content for match in matches)


def test_repository_context_has_directory_and_commit_summary_with_fresh_revision(tmp_path):
    project_id = uuid4()
    repo = tmp_path / "repo"
    (repo / "docs" / "04-api").mkdir(parents=True)
    _git(repo, "init", "-q")
    (repo / "README.md").write_text("Project entry\n", encoding="utf-8")
    (repo / "docs" / "04-api" / "core-api.md").write_text("Core API\n", encoding="utf-8")
    _git(repo, "add", "--", ".")
    _git(repo, "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
         "commit", "-qm", "Document project API")
    provider = RepositoryContextProvider({project_id: repo})

    before = provider.retrieve(project_id, "API")
    assert any("docs/04-api" in item.content and "Directory" in item.title for item in before)
    assert any("Document project API" in item.content and "Commit" in item.title for item in before)
    assert provider.retrieve(uuid4(), "API") == []

    (repo / "README.md").write_text("Updated project entry\n", encoding="utf-8")
    _git(repo, "add", "--", "README.md")
    _git(repo, "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
         "commit", "-qm", "Refresh project entry")
    after = provider.retrieve(project_id, "API")
    assert {item.revision for item in before} != {item.revision for item in after}
    assert {item.source_id for item in before}.isdisjoint(
        {item.source_id for item in after})


@pytest.mark.parametrize("start_at_new", [False, True])
def test_repository_context_pins_one_commit_when_head_moves_mid_read(tmp_path, start_at_new):
    project_id = uuid4()
    repo = tmp_path / "repo"
    repo.mkdir()
    _git(repo, "init", "-q")
    (repo / "README.md").write_text("Old committed snapshot\n", encoding="utf-8")
    _git(repo, "add", "--", "README.md")
    _git(repo, "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
         "commit", "-qm", "Initial snapshot")
    old_revision = _git(repo, "rev-parse", "HEAD")
    (repo / "README.md").write_text("New committed snapshot\n", encoding="utf-8")
    _git(repo, "add", "--", "README.md")
    _git(repo, "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
         "commit", "-qm", "Updated snapshot")
    new_revision = _git(repo, "rev-parse", "HEAD")

    initial_revision = new_revision if start_at_new else old_revision
    moved_revision = old_revision if start_at_new else new_revision
    initial_content = "New committed snapshot" if start_at_new else "Old committed snapshot"
    _git(repo, "reset", "--hard", initial_revision)

    class MovingHeadProvider(RepositoryContextProvider):
        moved = False

        def _git(self, root, *args, **kwargs):
            result = super()._git(root, *args, **kwargs)
            if args == ("rev-parse", "HEAD") and not self.moved:
                self.moved = True
                _git(repo, "reset", "--hard", moved_revision)
            return result

    matches = MovingHeadProvider({project_id: repo}).retrieve(project_id, "snapshot")
    readme = next(item for item in matches if item.title == "README.md")

    assert initial_content in readme.content
    assert all(item.revision == initial_revision for item in matches)
    assert readme.source_id == uuid5(
        NAMESPACE_URL, f"{project_id}:{initial_revision}:README.md",
    )
    commit_contents = [item.content for item in matches if item.title.startswith("Commit ")]
    assert any("Updated snapshot" in content for content in commit_contents) is start_at_new


def test_retrieval_merges_repository_evidence_into_cited_context(tmp_path):
    project_id, user_id = uuid4(), uuid4()
    repo = tmp_path / "repo"
    repo.mkdir()
    _git(repo, "init", "-q")
    (repo / "README.md").write_text("Core entry is services/core-api.\n", encoding="utf-8")
    _git(repo, "add", "--", "README.md")
    _git(repo, "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
         "commit", "-qm", "Document Core entry")

    class CoreBoundary:
        def fetch_sources(self, *_args):
            return RagSourcesResponse(projectId=project_id, snapshotVersion=1,
                                      sourcesChanged=True, sources=[],
                                      requestId="repository-test")

        def fetch_graph(self, *_args):
            return []

    class IndexBoundary:
        def snapshot_version(self, _project_id):
            return None

        def synchronize(self, *_args):
            pass

        def search(self, *_args):
            return {}, [], []

    result = RetrievalService(
        CoreBoundary(), IndexBoundary(), HashEmbeddingProvider(384),
        top_k=2, candidate_k=4, context_char_budget=500,
        repository_provider=RepositoryContextProvider({project_id: repo}),
    ).retrieve(project_id, user_id, False, "Core entry", "repo-request")

    assert "Core entry is services/core-api." in result.context
    assert "【来源1】" in result.context
    assert result.sources[0].source_type == "REPOSITORY"
    assert result.sources[0].source_id
    assert result.task_targets == ()


def test_repository_bindings_are_server_configured_and_unique(monkeypatch, tmp_path):
    project_id = uuid4()
    repo = tmp_path / "repo"
    repo.mkdir()
    _git(repo, "init", "-q")
    (repo / "README.md").write_text("Configured project entry\n", encoding="utf-8")
    _git(repo, "add", "--", "README.md")
    _git(repo, "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
         "commit", "-qm", "Document configured entry")
    binding = {"projectId": str(project_id), "path": str(repo)}
    monkeypatch.setenv("AGENTFORGE_AGENT_REPOSITORIES", json.dumps([binding]))

    settings = Settings()
    provider = RepositoryContextProvider.from_settings(settings)
    assert provider.retrieve(project_id, "Configured project")
    assert provider.retrieve(uuid4(), "Configured project") == []

    monkeypatch.setenv("AGENTFORGE_AGENT_REPOSITORIES", json.dumps([{**binding, "displayName": "Unused"}]))
    with pytest.raises(ValueError):
        Settings()

    monkeypatch.setenv("AGENTFORGE_AGENT_REPOSITORIES", json.dumps([binding, binding]))
    with pytest.raises(ValueError):
        Settings()


def test_repository_context_rejects_sensitive_directory_even_with_safe_filename(tmp_path):
    project_id = uuid4()
    repo = tmp_path / "repo"
    (repo / "docs" / "04-api" / "private").mkdir(parents=True)
    _git(repo, "init", "-q")
    (repo / "README.md").write_text("Public project entry\n", encoding="utf-8")
    (repo / "docs" / "04-api" / "private" / "ops.md").write_text(
        "Confidential operations map\n", encoding="utf-8")
    _git(repo, "add", "--", ".")
    _git(repo, "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
         "commit", "-qm", "Add documentation")

    matches = RepositoryContextProvider({project_id: repo}).retrieve(project_id, "operations")

    assert all("private" not in item.title.lower() for item in matches)
    assert all("Confidential operations map" not in item.content for item in matches)


def test_large_api_document_is_bounded_without_hiding_other_repository_evidence(tmp_path):
    project_id = uuid4()
    repo = tmp_path / "repo"
    (repo / "docs" / "04-api").mkdir(parents=True)
    _git(repo, "init", "-q")
    (repo / "README.md").write_text("Core entry overview\n", encoding="utf-8")
    (repo / "docs" / "04-api" / "core-api.md").write_text(
        "Core API reference\n" + "A" * 24_000, encoding="utf-8")
    _git(repo, "add", "--", ".")
    _git(repo, "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
         "commit", "-qm", "Document API reference")

    matches = RepositoryContextProvider({project_id: repo}).retrieve(project_id, "Core API")

    assert any(item.title == "README.md" for item in matches)
    api = next(item for item in matches if item.title == "docs/04-api/core-api.md")
    assert api.content.startswith("Core API reference")
    assert len(api.content.encode("utf-8")) <= 16_384


def test_large_committed_tree_keeps_early_readme_evidence(tmp_path):
    project_id = uuid4()
    repo = tmp_path / "repo"
    (repo / "src").mkdir(parents=True)
    _git(repo, "init", "-q")
    (repo / "README.md").write_text("Repository entry survives large tree\n", encoding="utf-8")
    for index in range(3000):
        (repo / "src" / f"module_{index:04d}_{'x' * 45}.txt").write_text(
            "Not whitelisted\n", encoding="utf-8")
    _git(repo, "add", "--", ".")
    _git(repo, "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
         "commit", "-qm", "Document large repository")

    matches = RepositoryContextProvider({project_id: repo}).retrieve(project_id, "entry")

    assert any(item.title == "README.md" and "survives large tree" in item.content
               for item in matches)
